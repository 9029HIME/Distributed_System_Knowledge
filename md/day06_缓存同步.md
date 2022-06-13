# 72-常用的缓存同步策略

1. 被动同步

   发现没有命中缓存后，查询数据库，再同步到缓存。这个方式比较简单粗暴，适合更新频率低，时效性低的数据。

2. 同步双写

   修改数据库的同时修改缓存，保证他俩在极短的时间内是同步的。但是对代码的侵入性很高，适合更新频率高、实时性高的数据。

3. 异步通知

   修改数据库后，写入一条消息给消费者，消费者拿到消息后修改缓存数据。这种方式比较便捷，可以在一个统一的代码块确定逻辑。但是时效性一般，属于1和2的折中做法：

   ![image](https://user-images.githubusercontent.com/48977889/173282196-dcdeafff-e823-4b13-9cdc-2584349d0514.png)

# Canal

## 73-Canal机制

![image](https://user-images.githubusercontent.com/48977889/173282402-40fbd968-3e44-4926-a8bb-a4cfd875c929.png)

![image](https://user-images.githubusercontent.com/48977889/173282441-93462d03-034b-4116-b186-dec8f31edfb0.png)

Canal是基于Mysql的主从同步机制实现的，Canal实例将自己伪装成一个Slave，不停地获取Master的binlog，再把得到的binlog信息转发给Canal客户端，客户端根据消息的内容进行缓存同步。在MQ机制里，需要服务实例主动将消息写Broker，再由Broker发送给消费端，有一定的代码侵入性。**而Canal是直接监听数据库，将数据库里的消息变更发送给消费者，更加便捷。**

## 74-Canal使用

开启主从同步后：

1. 搭建Canal服务器。
2. Springboot引入Canal依赖。
3. 数据库实体类加入Canal注解，标记字段映射关系。
4. 实现EntryHandler方法，泛型指定为3.的数据库实体，注入IOC容器。