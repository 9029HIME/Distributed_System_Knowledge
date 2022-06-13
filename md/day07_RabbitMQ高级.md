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