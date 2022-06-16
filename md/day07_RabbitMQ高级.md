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

# RabbitMQ的延迟消息

RabbitMQ不提供默认的延迟消息，但是可以通过死信交换机变相实现延迟消息。

## 81-什么是死信？ 

我这里下一个定义，满足其中1个条件的消息，就被称为死信，**死信在默认情况下会被RabbitMQ清除**：

1. 消息被Consumer响应了reject。
2. 消息被Consumer响应了nack，并且这个消息的requeue是false。
3. 消息的TTL耗尽。
4. 消息所在的Queue的TTL耗尽。
5. Queue里的消息堆积满了，此时又来了一个消息，那么最早到的未被消费的消息可能会变成死信。

## 82-死信交换机

RabbitMQ允许为Queue绑定1个死信交换机与1个死信Key，当这个Queue的某个消息变成死信后，Queue会将该死信发送到这个Queue对应的死信交换机上，并传入绑定好的死信Key。死信交换机会根据死信Key将死信放入死信Queue里。

这个有点熟悉，不就是知识点79的RepublishMessageRecoverer嘛？是有点像，不过RepublishMessageRecoverer是消费者将消息转发到另一个Exchange，而死信交换机是被Queue转发死信，两者也是有一定区别的：

RepublishMessageRecoverer：

![image](https://user-images.githubusercontent.com/48977889/173750580-a276a68e-f04a-45f8-a009-a1191a0b94c4.png)

死信交换机：

![image](https://user-images.githubusercontent.com/48977889/173750757-704c95ac-c4d3-48c1-984e-9581f1b2c625.png)

## 83-基于死信交换机+TTL实现延迟消息

如果想用死信交换机与TTL实现延迟消息，那么必须要明确一点：这个消息一开始不会被Consumer所消费，这个消息就是要被等待超时...存入死信Queue的，然后在死信Queue里被Consumer消费：

1. 声明普通交换机和普通队列：

   ```java
   @Bean
   public DirectExchange simpleExchange(){
       return new DirectExchange("simpleExchange");
   }
   
   @Bean
   public Queue simpleQueue(){
       return QueueBuilder.durable("simpleQueue")
           .ttl(10000) // 这是队列的ttl
           .deadLetterExchange("dlExchange") // 指定这个Queue死信交换机
           .deadLetterRoutingKey("dlKey")	// 指定死信的Key
           .build();
   }
   
   @Bean
   public Binding simpleBinding(){
       return BindingBuilder.bind(simpleQueue()).to(simpleExchange()).with("simple") //通过key=simple来绑定交换机与队列的关系
   }
   
   @Bean
   @Bean
   public DirectExchange dlExchange(){
       return new DirectExchange("dlExchange");
   }
   
   @Bean
   public Queue dlQueue(){
       return QueueBuilder.durable("dlQueue").build();
   }
   
   @Bean
   public Binding dlBinding(){
       return BindingBuilder.bind(dlQueue()).to(dlExchange()).with("dlKey");
   }
   ```

2. 消费者监听dlQueue的消息：

   ```java
   @Component
   public class DlListener{
       @RabbitListener(bindings = @QueueBinding(
           value = @Queue(name = "dlQueue", durable = "true"),
           exchange = @Exchange(name = "dlExchange"),
           key = "dlKey"
       ))
       public void listenDlQueue(){
           sout("消费者接收到死信队列的消息");
       }
   }
   ```

   

## 84-DelayExchange

但是通过死信交换机来实现延迟消息会比较麻烦，首先要有一个不会消费的Queue，再定义1个死信Exchange，稍有不慎就会开发错误，引发Bug。

推荐使用一个开箱即用的工具：DelayExchange。它是1个RabbitMQ社区的插件，封装了原生Exchange，允许DelayExchange在内存中存储消息，到达时间后才放入队列里。使用之前需要在RabbitMQ服务器上安装这个插件。

在Java代码中可以通过@Exchange(name="",delayed="true")或ExchangeBuilder().delayed()创建DelayExchange。

对DelayExchange发消息时需要加一个名为“x-delay”的Header来控制消息TTL：

```java
MessageBuilder.setHeader("x-delay",10000);
```

但是DelayExchange本质是将消息困住，不放入Queue，此时Broker会给Producer返回publisher-return ack。因此需要对知识点76的ReturnCallback做一个改造，**避免Producer误以为消息发送失败了，然后重复发送**：

```java
@Configuration
@slf4j
public class ReturnCallBackConfig implements ApplicationContextAware{
    
    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException{
        RabbitTemplate rabbitTemplate = applicationContext.getBean(RabbitTemplate.class);
        rabbitTemplate.setReturnCallBack((message, replyCode, replyText, exchange, routingKey)->{
            
            if(message.getMessageProperties().getReceivedDelay() > 0){
                return;
            }
            
            log.info("消息进入Queue失败，消息：{},应答码：{},原因：{},交换机：{},路由key：{}", message, replyCode, eplyText, exchange, routingKey);
        });
    }
}
```



# 消息堆积与惰性队列

## 85-消息堆积的避免

当消息的生产速度大于消费速度，就有可能产生消息在Queue上堆积的情况。当发生消息堆积，最先到达Queue的消息会变成死信，如果不采用死信交换机进行兜底处理的话消息会被丢弃掉，这对于消息可靠性来说是不能接受的。因此，对于避免消息堆积，可以从以下3点入手：

1. 增加Consumer数量，提高总的消费速度。
2. 增加单个Consumer的线程数量，提高单个Consumer的消费速度。
3. 使用惰性队列。

当然，如果有死信交换机来兜底的话就更好了。

## 86-惰性队列

那什么是惰性队列呢？了解惰性队列之前，先了解一下RabbitMQ的普通队列：

对于普通队列来说，如果没有开启消息的持久化，消息到达Broker后默认是存放到内存里，目的就是为了增加消息的吞吐量。但是呢，内存作为比磁盘更低一级的存储硬件，容量是有限的。RabbitMQ设置了一个默认阈值：40%，即内存占用到达40%后，Broker会处于类似STW的状态，阻止接收Producer的消息，然后将内存里的消息写入磁盘，写入完成后才接收Producer的信息。也就是说在高消息写入的场景下，如果使用普通队列（没有开启消息持久化），Broker的消息处理能力呈现波浪形，低点到高点代表正在处理消息，高点到低点代表正在持久化消息。**在这种场景下消息吞吐量会降低**。

那么再说说惰性队列，惰性队列是RabbitMQ在3.6.0版本后提出的概念，它的特点是：

1. 接收到消息后，会立即将消息写入磁盘。
2. 消费消息时，将消息从磁盘读取到内存，再发送给消费者。

因为磁盘的存储能力比内存高得多，因此惰性队列适合在高消息堆积的场景下使用，**但是在低消息堆积的场景下吞吐量会变低**。

惰性队列的声明很简单，只需指定lazy属性即可：

```java
@Bean
public Queue lazyQueue(){
    return QueueBuilder.duarable("lazyQueue")
        .lazy()
        .build();
}
```

**也可以将运行时队列改为惰性队列，这个可以查看具体的rabbitmqctl命令。**