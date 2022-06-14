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

消息到达Broker后，针对可靠性主要有3点要考虑：1.交换机的持久化 2.队列的持久化 3.消息的持久化。这3个在SpringAMQP里默认都是true的，也就是说消息到达队列并返回ACK给Producer后，意味着在Broker已经持久化成功了。

## 78-Broker到Consumer

Consumer可以向Broker回复3种应答：ack（消费成功）、nack（消费失败，需要重试）、reject（消费失败，丢弃消息）。Broker和Consumer的可靠性是基于**Consumer到Broker的ack和nack**来完成的。SpringAMQP对于应答支持3种模式：

```yaml
spring:
	rabbitmq:
		listener:
			simple:
				prefetch: 1
				acknowledge-mode: auto
```

1. manual，需要在业务代码结束后调用api来手动ack或nack。
2. auto：自动ack，由AOP代理RabbitListener方法，当这个方法正常执行，没有抛运行时异常后，自动ack。抛出异常后返回nack。有点类似声明式事务。**默认就是auto。**
3. none：Broker不会等待Consumer任何响应，Broker会认为Consumer 100%消费成功，投递出去的消息会被Broker立即删除。

**当一段时间后，Broker发现Consumer还未ack或回复nack（非none），则会向Consumer重新发送该消息。**

## 79-Consumer失败重拾机制

在非none的前提下，消费者消费失败或超时后Broker会重新发送。如果引起失败的原因是必定出现的，或者说在一段时间内必定会出现的，**就会引起Consumer不断地nack，然后Broker不断地往Consumer重发消息的情况，这样子是很浪费IO性能的**。因此需要一个机制来确保**一段时间内**Broker不会往Consumer重发消息，而是等待Consumer自行把消息处理好，直至超时才重发。**Spring提供了这样的retry机制，当消费者出现异常时会本地重试，而不是无限制的响应nack。**

```yaml
spring:
	rabbitmq:
		listener:
			simple:
				prefetch: 1
				acknowledge-mode: auto
				retry:
					enabled: true #开启重试机制
					initial-interval: 1000 #初始的失败等待时长(ms)
					multiplier: 2 #下次失败等待时长因子 实际等待时长 = (last-interval * multiplier)
					max-attempts: 3 #最大重试次数
					stateless: false #如果业务中包含事务，这里要为false
```

上面的配置，只会重试3次，这3次的重试时长分别是：1s、2s、4s，那么当重试次数到达阈值后还未ack，消费者会怎么处理呢？此时消费者会触发失败处理策略，有3种，默认使用RejectAndDontRequeueRecoverer。

1. RejectAndDontRequeueRecoverer：向Broker返回reject，告诉Broker这条消息丢掉了。
2. ImmediateRequeueMessageRecoverer：向Broker返回nack，又开始反复踢皮球（同一条消息不断重发）。
3. RepublishMessageRecoverer：将失败消息和**Java异常栈信息**投递到指定交换机。

也就是说默认情况下，重试机制用完会就reject，**此时消息就真的丢掉了**。为了保证消息的可靠性，但同时也要避免不断重发和nack到来的性能浪费，我们需要一种兜底的方案，比如将这类消息发送到1个指定的错误消息交换机，又或者自定义MessageRecoverer进行兜底处理，并对这些错误消息进行人工介入。

## 80-所以说，如何保证消息的可靠性呢？

发生消息丢失是要避免的，为了保证消息的可靠性，可以分别从Producer、Consumer、Broker入手：

1. 开启Producer的ack机制，当然最好是异步等待。当回调时发现消息投递失败了，进行重新投递。
2. Broker开启Exchange、Queue、消息的持久化。
3. Consumer对消费成功的消息回复ack，消费失败的消息回复nack，并且失败到一定次数后要进行兜底方案（视情况回复reject），对这些错误消息进行人工介入。