# 75-RabbitMQ常见问题

1. 消息可靠性

   消息是否会丢，如何确保消息至少被消费一次？

2. 延迟消息

   如何发送一条延迟消息

3. 消息堆积问题

   生产速度远大于消费速度时，Broker积压的消息太多该怎么办？

4. 高可用问题

   为了避免单点故障，如何搭建RabbitMQ集群？

# RabbitMQ的消息可靠性

这里建议搭配kafka笔记学习：

[]: https://github.com/9029HIME/Kafka_Learn/blob/master/src/mds/02%20kafka%E7%9A%84%E6%B6%88%E6%81%AF%E4%B8%A2%E5%A4%B1.md	"kafka的消息丢失.md"

## 76-Producer到Broker

对于RabbitMQ来说，消息从Producer到Broker有两种丢失情况：1. 消息未到达Exchange 2.消息未到达Queue。Broker对Producer提供了3种确认来保证消息的可靠性：

![image](https://user-images.githubusercontent.com/48977889/173288428-365780d2-2d1f-441c-8d3d-8ec0302c8d1b.png)

1. publisher-confirm ack：消息到达了Queue。
2. publisher-confirm nack：消息未到达Exchange。
3. publisher-return ack：消息到达了Exchange，但未到达Queue。

只有收到confirm ack后，Producer才能认为消息可靠了。对于另外两种不可靠情况，Producer可以自定义解决方案。

关于开启Producer的失败重试机制，有以下几步：

1. 添加配置：

   ```yaml
   spring:
   	rabbitmq:
   		publisher-confirm-type: correlated #针对confirm的确认，#simple，同步等待Broker的确认，直至超时 #correlated，异步等待，需要自定义ConfirmCallBack，Broker确认后会回调这个Callback。
   		publisher-returns: true #针对return的确认，和confirm确认类似，只不过回调的是ReturnCallBack。
   		template:
   			mandatory: true #调用ReturnCallBack，为false则丢弃消息，Producer不管了。
   ```

2. 编写ReturnCallBack：

   1个RabbitTemplate对应1个ReturnCallBack，因此在RabbitTemplate被注入IOC容器后，就要对其他进行ReturnCallBack定义

   ```java
   @Configuration
   @slf4j
   public class ReturnCallBackConfig implements ApplicationContextAware{
       
       @Override
       public void setApplicationContext(ApplicationContext applicationContext) throws BeansException{
           RabbitTemplate rabbitTemplate = applicationContext.getBean(RabbitTemplate.class);
           rabbitTemplate.setReturnCallBack((message, replyCode, replyText, exchange, routingKey)->{
               log.info("消息进入Queue失败，消息：{},应答码：{},原因：{},交换机：{},路由key：{}", message, replyCode, eplyText, exchange, routingKey);
           });
       }
   }
   ```

3. 编写CorrelationData：

   1个发送行为对应1个CorrelationData，在RabbitTemplate发送时传入这个CorrelationData参数，即可实现Confirm的回调

   ```java
   @Autowired
   private RabbitTemplate rabbitTemplate;
   
   public void testCorrelationData(){
       String msg = "hello";
       String msgId = UUID.randomUUID().toString();
       CorrelationData correlationData = new CorrelationData(msgId);
       correlationData.getFuture().addCallback(
           result -> {
               if(result.isAck()){
                   log.info("消息已到达Queue，ID：{}",correlationData.getId());
               }else{
                   log.info("消息到达Exchange，ID：{}，原因：{}",correlationData.getId(),result.getReason());
               }
           },
           ex -> log.error("消息发送异常,ID:{},原因:{}",correlationData.getId(),ex.getMessage());
       );
       rabbitTemplate.convertAndSend("","",msg,correlationData);
   }
   ```

综上所述，基于Producer与Broker的ack机制，可以实现消息的重新发送，从而防止消息丢失。

## 77-到了Broker后

## 78-Broker到Consumer

## 79-Consumer失败重拾机制