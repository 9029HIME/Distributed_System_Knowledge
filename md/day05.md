# 55-Redis单机存储问题

1. 数据丢失问题

   Redis作为直接操作内存的缓存工具，不幸宕机的话数据有可能会丢失，**需要依靠持久化机制来避免**。

2. 并发能力问题

   Redis虽然基于1个主线程+IO多路复用的机制来完成缓存数据的读写，但海量请求过来单机Redis还是撑不住的，**需要依靠主从集群来缓解**。

3. 故障恢复问题

   Redis集群其中一个节点宕机后，需要有完善的机制来维持高可用性，**通过哨兵机制来实现**。

4. 存储能力问题

   为了提高存储能力，可以采用**类似Es、Kafka那样的数据分片+备份的形式，分散到每个节点上**。

# Redis持久化

Redis有2种持久化方案：RDB、AOF。

## 56-RDB

即Redis Database Backup File。RDB方式的过程是Redis主进程开启1个进程，然后子进程将Redis运行时内存存储的数据**写入一个持久化文件**里，当Redis重启后，从磁盘读取这个持久化文件，达到恢复数据的效果。这个持久化文件可以理解为一个快照，也叫RDB文件。

对于RDB文件的生成，其实有2种方式：

1. 通过redis-cli，运行save命令

   但是这种做法实际上是让Redis主进程来写RDB文件，要记得操作磁盘肯定比操作内存慢得多，如果数据量大的话会导致主进程没有时间去响应Redis的读写请求，从而导致阻塞超时，所以一般不用这种方式。

2. 通过redis-cli，运行bgsave命令

   通过这个命令，可以让Redis主进程派生一个子进程，子进程来完成RDB的写入，不会影响主进程的读写操作。

3. 关闭redis时，会进行一次RDB的写入（除了kill -9）

4. redis默认有一套规则来**运行时触发RDB（派生子进程 ）**，在redis.conf配置文件里：

   ```
   save 900 1
   save 300 10
   save 60 10000
   ```

   这里的意思分别是：在900秒内发生1个key的操作，就进行RDB写入，在300秒内发生10个key的操作，就进行RDB写入，在60秒内发生10000个key的操作，就进行RDB写入。这些条件都是**或的关系**，任意一个触发都会进行RBD写入。

## 57-RDB的一些细节

通过配置文件可以设置RDB的其他细节：

```
rdbcompression yes #是否在写入rdb文件时进行压缩
dbfilename dump.rdb	#rdb文件名
dir ./	#rdb所在路径
```

redis的启动也是通过配置文件的路径+文件名找到RDB文件进行读取，不过这个压缩存储虽然减轻了磁盘存储压力，但是也增加了CPU的消耗，对于磁盘空间充足的情况下，没必要开启。

![image](https://user-images.githubusercontent.com/48977889/171335281-711d54c3-f4fa-48a6-b320-5b1ac8d27a51.png)

对于子进程来说，共享了主进程的页表，从而操作与主进程一样的虚拟空间（联动[01 内存管理.md](https://github.com/9029HIME/OS/blob/master/src/04 内存/01 内存管理.md)），子进程主要的作用是读取数据写入RDB文件。在子进程读取期间，读取数据所在的内存区域需要变成只读。当有新的请求过来操作数据时，要将该数据在内存中完整拷贝一份副本，之后主线程的所有操作都针对这个副本来进行。**当然，在RDB写入操作完成后，子进程会被shutdown，并且之前被派生的只读数据也会被释放空间（我猜的）**。

在一个极端情况下，子进程执行RDB操作比较慢的时候，发生了对所有数据的写请求...这时候会导致Redis的内存占用直接飙升，因为很多数据都要进行拷贝来满足主线程的操作。当然这只是一种极端情况，这个说法只是提醒我们要对Redis的操作内存作出冗余。

## 58-RDB的数据丢失问题

结合知识点56来看，虽然说Redis正常shutdown会触发RDB，运行时也会根据操作规则进行动态的RDB。但Redis在运行时以外宕机或者被kill -9后，在RDB空档期产生的数据可以说是完全丢失了，因为它们根本没被写入RDB文件。等Redis重启读取RDB文件后也不会读到它们。

## 59-AOF

即Append Only File，AOF和RDB有点类似，也是将数据写入一个文件，**也是可以通过主进程或子进程来完成**，RDB存储的是key和value，而AOF存储的是Redis的**写、改、删操作记录**，当Redis启动时会读取AOF的操作记录，根据操作记录的顺序生成值，注意是生成！！！RDB的话更像是直接导入值，这也是为什么AOF的恢复会比RDB要慢一些。 

AOF默认是关闭的，需要修改配置文件来开启：

```
appendonlyfile yes #开启aof
appendfilename "appendonly.aof" #aof文件
```

对于AOF的触发，也是有一套规则：

```
appendfsync 策略值
```

策略值包含awalys、everysec、no。

1. awalys代表每次进行一个写操作，**主进程**会立即将操作记录写入aof文件里，注意写入aof文件是一个磁盘操作了。虽然与RDB相比它是针对1条数据，但这样做就有点类似MySQL的写操作了，每次数据的写都要主进程去写磁盘，自然效率是没那么高的，也会影响到接下来的请求效率。
1. everysec代表主进程会将操作写入AOF缓冲区，**主进程**每隔1秒钟子进程将AOF缓冲区的数据写入aof文件里。在1秒钟的空档期内Redis宕机的话会导致空档期的产生的数据丢失，可靠性稍微弱一些。
1. no代表主进程会将操作写入AOF缓冲区，并且由OS负责将缓冲区的数据写入aof文件里，有点像kafka的消息刷盘机制。当然OS什么时候写入呢？这个我们无从得知，只能让OS自己控制。

因为AOF记录的是操作记录，那么在一定时间内，操作记录的数量和内容会变得冗余，比如在一段时间内操作了set num 1、set num 2、set num 3，然后对num这个key就不再操作了。那么对于aof文件来说前两个操作记录是没意义的。对于这种情况redis提供了AOF的重写机制，用来重新整理、润色、优化aof文件的内容。通过执行bgrewriteaof命令或者**阈值触发**，主进程就会派生1个子进程来完成aof文件的重写。这里的阈值有2个，可以通过配置文件更改：

1. auto-aof-rewrite-percentage 60

   当**aof文件大小**比**上次重写后的大小**多出60%，触发重写。

2. auto-aof-rewrite-min-size 64mb

   当**aof文件大小**在64mb大小以上，才会触发重写

这2个规则是**和**的关系，必须满足两个才会触发文件重写。当然，子进程重写过程中肯定会**面临主进程处理请求的情况**，这种时候产生的新值还是要同步到aof文件里的，但具体怎么做呢？主进程会将这些新值的aof记录写入**AOF重写缓冲区**，当子进程重写AOF完成后，主进程会调用一个**信号处理函数**，这时主进程会将AOF重写缓冲区的数据追加到重写完成后的AOF文件里。**在执行信号处理函数的主进程是不会处理客户端请求的**。

# Redis主从集群

先给我的Ubuntu01和Ubuntu02安装好Redis，值得注意的是，在这个知识点下的Redis主从集群只是为了解决海量请求的并发能力（写Master，读Replica），但不能解决存储能力问题，数据的存储并没有分片和备份，每个节点上的信息都是一致的（不像Es和Kafka）。

## 60-1主2从集群搭建

1. 关闭AOF，开启ADB模式。

2. 在redis配置文件里声明自己的ip地址，三台机子都要，这里以master为例：

   ![image](https://user-images.githubusercontent.com/48977889/172096489-eb411eae-b939-42aa-8a48-ae69e4b33230.png)

3. 在从节点的配置上添加replicaof ${主机ip} ${端口}的配置。

4. 在主节点配置文件上修改bind为 bind 0.0.0.0，表示只允许这两个ip的节点称为自己的子节点。

5. 启动主节点，然后依次启动子节点，可以看到主节点的日志信息：

   ```bash
   kjg@kjg-PC:/usr/local/redis/redis-6.2.4$ redis-server /usr/local/redis/redis-6.2.4/redis.conf
   9786:C 06 Jun 2022 13:05:25.336 # oO0OoO0OoO0Oo Redis is starting oO0OoO0OoO0Oo
   9786:C 06 Jun 2022 13:05:25.336 # Redis version=6.2.4, bits=64, commit=00000000, modified=0, pid=9786, just started
   9786:C 06 Jun 2022 13:05:25.336 # Configuration loaded
   9786:M 06 Jun 2022 13:05:25.338 * Increased maximum number of open files to 10032 (it was originally set to 1024).
   9786:M 06 Jun 2022 13:05:25.338 * monotonic clock: POSIX clock_gettime
                   _._                                                  
              _.-``__ ''-._                                             
         _.-``    `.  `_.  ''-._           Redis 6.2.4 (00000000/0) 64 bit
     .-`` .-```.  ```\/    _.,_ ''-._                                  
    (    '      ,       .-`  | `,    )     Running in standalone mode
    |`-._`-...-` __...-.``-._|'` _.-'|     Port: 6379
    |    `-._   `._    /     _.-'    |     PID: 9786
     `-._    `-._  `-./  _.-'    _.-'                                   
    |`-._`-._    `-.__.-'    _.-'_.-'|                                  
    |    `-._`-._        _.-'_.-'    |           https://redis.io       
     `-._    `-._`-.__.-'_.-'    _.-'                                   
    |`-._`-._    `-.__.-'    _.-'_.-'|                                  
    |    `-._`-._        _.-'_.-'    |                                  
     `-._    `-._`-.__.-'_.-'    _.-'                                   
         `-._    `-.__.-'    _.-'                                       
             `-._        _.-'                                           
                 `-.__.-'                                               
   
   9786:M 06 Jun 2022 13:05:25.340 # Server initialized
   9786:M 06 Jun 2022 13:05:25.340 # WARNING overcommit_memory is set to 0! Background save may fail under low memory condition. To fix this issue add 'vm.overcommit_memory = 1' to /etc/sysctl.conf and then reboot or run the command 'sysctl vm.overcommit_memory=1' for this to take effect.
   9786:M 06 Jun 2022 13:05:25.341 * Loading RDB produced by version 6.2.4
   9786:M 06 Jun 2022 13:05:25.341 * RDB age 2 seconds
   9786:M 06 Jun 2022 13:05:25.341 * RDB memory usage when created 0.77 Mb
   9786:M 06 Jun 2022 13:05:25.341 * DB loaded from disk: 0.000 seconds
   9786:M 06 Jun 2022 13:05:25.341 * Ready to accept connections
   9786:M 06 Jun 2022 13:05:51.754 * Replica 192.168.120.121:6379 asks for synchronization
   9786:M 06 Jun 2022 13:05:51.754 * Partial resynchronization not accepted: Replication ID mismatch (Replica asked for '278c509688bfd003b2b7f89f0c4b192aa691cc84', my replication IDs are '732e949266ba35794e4cb90ac23c96a480559491' and '0000000000000000000000000000000000000000')
   9786:M 06 Jun 2022 13:05:51.754 * Replication backlog created, my new replication IDs are '840015749c430ae7a7e62b35f45e35bae5650731' and '0000000000000000000000000000000000000000'
   9786:M 06 Jun 2022 13:05:51.754 * Starting BGSAVE for SYNC with target: disk
   9786:M 06 Jun 2022 13:05:51.755 * Background saving started by pid 9944
   9944:C 06 Jun 2022 13:05:51.760 * DB saved on disk
   9944:C 06 Jun 2022 13:05:51.761 * RDB: 0 MB of memory used by copy-on-write
   9786:M 06 Jun 2022 13:05:51.777 * Background saving terminated with success
   9786:M 06 Jun 2022 13:05:51.778 * Synchronization with replica 192.168.120.121:6379 succeeded
   9786:M 06 Jun 2022 13:06:32.097 * Replica 192.168.120.122:6379 asks for synchronization
   9786:M 06 Jun 2022 13:06:32.098 * Full resync requested by replica 192.168.120.122:6379
   9786:M 06 Jun 2022 13:06:32.098 * Starting BGSAVE for SYNC with target: disk
   9786:M 06 Jun 2022 13:06:32.102 * Background saving started by pid 10018
   10018:C 06 Jun 2022 13:06:32.111 * DB saved on disk
   10018:C 06 Jun 2022 13:06:32.112 * RDB: 0 MB of memory used by copy-on-write
   9786:M 06 Jun 2022 13:06:32.181 * Background saving terminated with success
   9786:M 06 Jun 2022 13:06:32.181 * Synchronization with replica 192.168.120.122:6379 succeeded
   ```

6. 此时在主节点通过redis客户端命令可以看到整个集群的信息：

   ```bash
   kjg@kjg-PC:/usr/local/redis/redis-6.2.4$ redis-cli 
   127.0.0.1:6379> info replication
   # Replication
   role:master
   connected_slaves:2
   slave0:ip=192.168.120.121,port=6379,state=online,offset=252,lag=1
   slave1:ip=192.168.120.122,port=6379,state=online,offset=252,lag=1
   master_failover_state:no-failover
   master_replid:840015749c430ae7a7e62b35f45e35bae5650731
   master_replid2:0000000000000000000000000000000000000000
   master_repl_offset:252
   second_repl_offset:-1
   repl_backlog_active:1
   repl_backlog_size:1048576
   repl_backlog_first_byte_offset:1
   repl_backlog_histlen:252
   127.0.0.1:6379> 
   ```

7. 如果在子节点进行写操作时，会发现抛出异常，可以看到redis主从集群里，主节点用来写，子节点只能用来读：

   ```bash
   kjg1@ubuntu01:/usr/local/redis/redis-6.2.4$ redis-cli 
   127.0.0.1:6379> set num 123
   (error) READONLY You can't write against a read only replica.
   127.0.0.1:6379> 
   ```

## 61-主从集群同步过程

![image](https://user-images.githubusercontent.com/48977889/172101088-7e018176-c734-4c2c-9e2a-8a517b328345.png)

值得注意的是：

0. 每一个redis节点都有自己的replid（唯一）和offset，当子节点连接主节点时，需要将自己的replid和offset传过去。

1. 子节点第一次连接主节点，采用全量同步。后续的数据采用增量同步，增量同步是通过master的子进程将repl_baklog的**命令**发送到对应的子节点，子节点依次执行命令以达到数据同步的效果。主节点通过子节点连接时带过来的replid判断是不是自己的子节点，如果不是则走全量同步流程（第一、第二阶段）。如果是，则看子节点的offset和自己的offset差多少，并将数据增量同步过去（第三阶段）。

2. 当然，连接成功后，后续的信息同步也是基于增量同步的，包括子节点重启后再次连接master（在第一阶段子节点已经将主节点的replid保存下来了）。

3. repl_baklog本质是一个环形队列缓冲区，主节点写满后会从头开始覆盖数据，一旦子节点宕机太久或者太久没向主节点拿数据，有可能导致这个子节点offset之后的数据被主节点覆盖掉了，这时候就无法做增量同步了，只能重新做一次全量同步：

   正常情况（能做增量）：

   ![image](https://user-images.githubusercontent.com/48977889/172103055-2b955847-59ff-4c45-a6dc-2a1c748cc30c.png)

   数据被覆盖情况（只能做全量）：

   ![image](https://user-images.githubusercontent.com/48977889/172103091-3f887eda-c924-402a-9b58-51feb01d110c.png)

## 62-主从集群的优化

1. 在主节点配置文件内加上repl-diskless-sync yes，表示无磁盘复制。在进行第一次全量同步的时候RDB文件不会在磁盘生成，而是直接写到网络缓冲区发送给从节点，这种实用于弱磁盘、高带宽的主节点。**属于针对全量同步的优化**

2. 适当提高repl_baklog的大小，发现子节点宕机后尽快恢复子节点。**属于尽量避免全量同步的优化**

3. 限制1个主节点的从节点数量，如果实在太多子节点，可以采用主-从-从的架构，二级从节点也是采用replicaof配置连接一级从节点，不过低级从节点的弱一致性的概率会变高。**属于减轻主节点的同步压力的优化**

   ![image](https://user-images.githubusercontent.com/48977889/172103975-09d79da3-2c32-4734-b9b0-aded3a87a316.png)

# Redis哨兵

哨兵Sentinel是Redis提供的一个监控实例，这里的Sentinel和知识点25的Sentinel不是同一个东西。Redis哨兵主要提供了监控集群健康状态、故障转移、订阅发布集群信息的功能。利用心跳机制监控集群状态，排查出故障的Master，再通过故障转移从Slave中选出一个新Master，维护集群的高可用性。当然，这时候集群节点发生了变更，Redis哨兵也会通过订阅发布机制告诉Redis客户端哪些是Master，哪些是Slave。

## 63-哨兵监控

哨兵是基于心跳机制监控Redis集群中节点的状态，每隔1秒向**每个节点**发送ping命令（Redis的ping pong），如果某个哨兵发现某个节点未在**规定时间内**响应pong，就认为这个节点**主观下线**。

当超过quorum个哨兵都认为这个节点主观下线，那么这个节点就变成**客观下线**。当Master客观下线后，哨兵开启故障转移功能。quorum值最好超过哨兵数的一半（类似Zookeeper的不可用依据）。

![image](https://user-images.githubusercontent.com/48977889/172303270-063970ec-64bc-4831-ad12-59fc151ec4ed.png)

## 64-哨兵故障恢复

哨兵需要选一个Slave来当新Master，那改选谁呢？其实哨兵有自己的一套规范，优先级如下：

1. 判断Slave与Master的断连时长，如果超过(down-after-milliseconds * 10)，则排除该Slave。
2. 判断Slave的slave-priority值，越小优先级越高，为0表示不参选Master，默认所有节点是一样的，所以默认情况下这个值不作为依据。
3. 如果slave-priority值一致，则判断Slave的offset值，值越大优先级越高（数据越新）。
4. 如果offset值也一样，则判断Slave的run id大小，越小优先级越高，这个其实不重要，offset才是最主要的。

当选好新Master后，故障转移的步骤如下：

1. 给新Master执行replicaof no one指令，表示该节点不再是Slave了。
2. 给其他Slave执行replica of ${新Master IP} ${新Master Port}指令，表示称为新Master的Slave。此时其他节点就会与新Master开启**增量同步**。
3. 当旧Master恢复后，哨兵会将旧Master的配置文件改为replica of ${新Master IP} ${新Master Port}，使其成为Slave。

## 65-哨兵集群的搭建

哨兵集群的搭建倒是没那么麻烦，只要多个哨兵都监控同一个集群的主节点，这些哨兵就会自动维护成一个哨兵集群。

这里在Ubuntu01搭建一个哨兵集群，一台机子开启3个哨兵实例：

1. 首先新建三个哨兵的配置文件目录 s1 s2 s3

2. 分别在对应目录下新建哨兵的配置文件sentinel.conf

   ```
   port 27001	#哨兵实例所在端口
   sentinel announce-ip 192.168.120.121 #哨兵实例所在ip
   sentinel monitor mymaster 192.168.120.161 6379 2 #指定redis集群的master所在地址，mymaster代表自定义的master名称，最后这个2代表quorum
   sentinel down-after-milliseconds mymaster 5000 
   sentinel failover-timeout mymaster 60000 #故障恢复的超时时间
   dir "/home/kjg1/s1"	#哨兵的工作目录
   ```

   当然，s2和s3的哨兵配置文件略微不同，改改端口和工作目录就好了。

3. 启动知识点60搭建好的Redis主从集群。

3. 在Ubuntu01执行redis-sentinel ${配置文件路径}命令，开启3个哨兵实例

```bash
kjg1@ubuntu01:~$ redis-sentinel s1/sentinel.conf 
2275:X 08 Jun 2022 12:49:18.520 # oO0OoO0OoO0Oo Redis is starting oO0OoO0OoO0Oo
2275:X 08 Jun 2022 12:49:18.520 # Redis version=6.2.4, bits=64, commit=00000000, modified=0, pid=2275, just started
2275:X 08 Jun 2022 12:49:18.520 # Configuration loaded
2275:X 08 Jun 2022 12:49:18.522 * Increased maximum number of open files to 10032 (it was originally set to 1024).
2275:X 08 Jun 2022 12:49:18.522 * monotonic clock: POSIX clock_gettime
                _._                                                  
           _.-``__ ''-._                                             
      _.-``    `.  `_.  ''-._           Redis 6.2.4 (00000000/0) 64 bit
  .-`` .-```.  ```\/    _.,_ ''-._                                  
 (    '      ,       .-`  | `,    )     Running in sentinel mode
 |`-._`-...-` __...-.``-._|'` _.-'|     Port: 27001
 |    `-._   `._    /     _.-'    |     PID: 2275
  `-._    `-._  `-./  _.-'    _.-'                                   
 |`-._`-._    `-.__.-'    _.-'_.-'|                                  
 |    `-._`-._        _.-'_.-'    |           https://redis.io       
  `-._    `-._`-.__.-'_.-'    _.-'                                   
 |`-._`-._    `-.__.-'    _.-'_.-'|                                  
 |    `-._`-._        _.-'_.-'    |                                  
  `-._    `-._`-.__.-'_.-'    _.-'                                   
      `-._    `-.__.-'    _.-'                                       
          `-._        _.-'                                           
              `-.__.-'                                               

2275:X 08 Jun 2022 12:49:18.525 # Sentinel ID is cc261e005dab1ab299a306ac716ea970d649c01b
2275:X 08 Jun 2022 12:49:18.525 # +monitor master mymaster 192.168.120.161 6379 quorum 2
2275:X 08 Jun 2022 12:49:18.529 * +slave slave 192.168.120.121:6379 192.168.120.121 6379 @ mymaster 192.168.120.161 6379
2275:X 08 Jun 2022 12:49:18.534 * +slave slave 192.168.120.122:6379 192.168.120.122 6379 @ mymaster 192.168.120.161 6379
2275:X 08 Jun 2022 12:49:23.578 # +sdown slave 192.168.120.122:6379 192.168.120.122 6379 @ mymaster 192.168.120.161 6379
```

```bash
kjg1@ubuntu01:~$ redis-sentinel s2/sentinel.conf 
2307:X 08 Jun 2022 12:50:45.149 # oO0OoO0OoO0Oo Redis is starting oO0OoO0OoO0Oo
2307:X 08 Jun 2022 12:50:45.149 # Redis version=6.2.4, bits=64, commit=00000000, modified=0, pid=2307, just started
2307:X 08 Jun 2022 12:50:45.149 # Configuration loaded
2307:X 08 Jun 2022 12:50:45.151 * Increased maximum number of open files to 10032 (it was originally set to 1024).
2307:X 08 Jun 2022 12:50:45.151 * monotonic clock: POSIX clock_gettime
                _._                                                  
           _.-``__ ''-._                                             
      _.-``    `.  `_.  ''-._           Redis 6.2.4 (00000000/0) 64 bit
  .-`` .-```.  ```\/    _.,_ ''-._                                  
 (    '      ,       .-`  | `,    )     Running in sentinel mode
 |`-._`-...-` __...-.``-._|'` _.-'|     Port: 27002
 |    `-._   `._    /     _.-'    |     PID: 2307
  `-._    `-._  `-./  _.-'    _.-'                                   
 |`-._`-._    `-.__.-'    _.-'_.-'|                                  
 |    `-._`-._        _.-'_.-'    |           https://redis.io       
  `-._    `-._`-.__.-'_.-'    _.-'                                   
 |`-._`-._    `-.__.-'    _.-'_.-'|                                  
 |    `-._`-._        _.-'_.-'    |                                  
  `-._    `-._`-.__.-'_.-'    _.-'                                   
      `-._    `-.__.-'    _.-'                                       
          `-._        _.-'                                           
              `-.__.-'                                               

2307:X 08 Jun 2022 12:50:45.158 # Sentinel ID is 635f34cf4fad025ed3014ab4db02cc27efc1a10a
2307:X 08 Jun 2022 12:50:45.158 # +monitor master mymaster 192.168.120.161 6379 quorum 2
2307:X 08 Jun 2022 12:50:45.176 * +slave slave 192.168.120.121:6379 192.168.120.121 6379 @ mymaster 192.168.120.161 6379
2307:X 08 Jun 2022 12:50:45.183 * +slave slave 192.168.120.122:6379 192.168.120.122 6379 @ mymaster 192.168.120.161 6379
2307:X 08 Jun 2022 12:50:46.248 * +sentinel sentinel cc261e005dab1ab299a306ac716ea970d649c01b 192.168.120.121 27001 @ mymaster 192.168.120.161 6379
2307:X 08 Jun 2022 12:50:50.213 # +sdown slave 192.168.120.122:6379 192.168.120.122 6379 @ mymaster 192.168.120.161 6379
2307:X 08 Jun 2022 12:50:56.884 * +sentinel sentinel 077c4aa60be9f3366998cb11b5b3d5dca592ac39 192.168.120.121 27003 @ mymaster 192.168.120.161 6379
```

```bash
kjg1@ubuntu01:~$ redis-sentinel s1/sentinel.conf 
2275:X 08 Jun 2022 12:49:18.520 # oO0OoO0OoO0Oo Redis is starting oO0OoO0OoO0Oo
2275:X 08 Jun 2022 12:49:18.520 # Redis version=6.2.4, bits=64, commit=00000000, modified=0, pid=2275, just started
2275:X 08 Jun 2022 12:49:18.520 # Configuration loaded
2275:X 08 Jun 2022 12:49:18.522 * Increased maximum number of open files to 10032 (it was originally set to 1024).
2275:X 08 Jun 2022 12:49:18.522 * monotonic clock: POSIX clock_gettime
                _._                                                  
           _.-``__ ''-._                                             
      _.-``    `.  `_.  ''-._           Redis 6.2.4 (00000000/0) 64 bit
  .-`` .-```.  ```\/    _.,_ ''-._                                  
 (    '      ,       .-`  | `,    )     Running in sentinel mode
 |`-._`-...-` __...-.``-._|'` _.-'|     Port: 27001
 |    `-._   `._    /     _.-'    |     PID: 2275
  `-._    `-._  `-./  _.-'    _.-'                                   
 |`-._`-._    `-.__.-'    _.-'_.-'|                                  
 |    `-._`-._        _.-'_.-'    |           https://redis.io       
  `-._    `-._`-.__.-'_.-'    _.-'                                   
 |`-._`-._    `-.__.-'    _.-'_.-'|                                  
 |    `-._`-._        _.-'_.-'    |                                  
  `-._    `-._`-.__.-'_.-'    _.-'                                   
      `-._    `-.__.-'    _.-'                                       
          `-._        _.-'                                           
              `-.__.-'                                               

2275:X 08 Jun 2022 12:49:18.525 # Sentinel ID is cc261e005dab1ab299a306ac716ea970d649c01b
2275:X 08 Jun 2022 12:49:18.525 # +monitor master mymaster 192.168.120.161 6379 quorum 2
2275:X 08 Jun 2022 12:49:18.529 * +slave slave 192.168.120.121:6379 192.168.120.121 6379 @ mymaster 192.168.120.161 6379
2275:X 08 Jun 2022 12:49:18.534 * +slave slave 192.168.120.122:6379 192.168.120.122 6379 @ mymaster 192.168.120.161 6379
2275:X 08 Jun 2022 12:49:23.578 # +sdown slave 192.168.120.122:6379 192.168.120.122 6379 @ mymaster 192.168.120.161 6379
2275:X 08 Jun 2022 12:50:47.166 * +sentinel sentinel 635f34cf4fad025ed3014ab4db02cc27efc1a10a 192.168.120.121 27002 @ mymaster 192.168.120.161 6379
2275:X 08 Jun 2022 12:50:56.884 * +sentinel sentinel 077c4aa60be9f3366998cb11b5b3d5dca592ac39 192.168.120.121 27003 @ mymaster 192.168.120.161 6379
```

从这个+sentinel可以看到，**只要多个哨兵都监控同一个集群的主节点，这些哨兵就会自动维护成一个哨兵集群。**

## 66-哨兵监控的演示

1. shutdown Master，看一下哨兵的日志（：

   ```bash
   2307:X 08 Jun 2022 12:50:45.158 # Sentinel ID is 635f34cf4fad025ed3014ab4db02cc27efc1a10a
   2307:X 08 Jun 2022 12:50:45.158 # +monitor master mymaster 192.168.120.161 6379 quorum 2
   2307:X 08 Jun 2022 12:50:45.176 * +slave slave 192.168.120.121:6379 192.168.120.121 6379 @ mymaster 192.168.120.161 6379
   2307:X 08 Jun 2022 12:50:45.183 * +slave slave 192.168.120.122:6379 192.168.120.122 6379 @ mymaster 192.168.120.161 6379
   2307:X 08 Jun 2022 12:50:46.248 * +sentinel sentinel cc261e005dab1ab299a306ac716ea970d649c01b 192.168.120.121 27001 @ mymaster 192.168.120.161 6379
   2307:X 08 Jun 2022 12:50:50.213 # +sdown slave 192.168.120.122:6379 192.168.120.122 6379 @ mymaster 192.168.120.161 6379
   2307:X 08 Jun 2022 12:50:56.884 * +sentinel sentinel 077c4aa60be9f3366998cb11b5b3d5dca592ac39 192.168.120.121 27003 @ mymaster 192.168.120.161 6379
   2307:X 08 Jun 2022 12:53:37.191 # +sdown master mymaster 192.168.120.161 6379
   2307:X 08 Jun 2022 12:53:37.247 # +odown master mymaster 192.168.120.161 6379 #quorum 2/2
   2307:X 08 Jun 2022 12:53:37.247 # +new-epoch 1
   2307:X 08 Jun 2022 12:53:37.247 # +try-failover master mymaster 192.168.120.161 6379
   2307:X 08 Jun 2022 12:53:37.252 # +vote-for-leader 635f34cf4fad025ed3014ab4db02cc27efc1a10a 1
   2307:X 08 Jun 2022 12:53:37.257 # 077c4aa60be9f3366998cb11b5b3d5dca592ac39 voted for 635f34cf4fad025ed3014ab4db02cc27efc1a10a 1
   2307:X 08 Jun 2022 12:53:37.257 # cc261e005dab1ab299a306ac716ea970d649c01b voted for 635f34cf4fad025ed3014ab4db02cc27efc1a10a 1
   2307:X 08 Jun 2022 12:53:37.309 # +elected-leader master mymaster 192.168.120.161 6379
   2307:X 08 Jun 2022 12:53:37.309 # +failover-state-select-slave master mymaster 192.168.120.161 6379
   2307:X 08 Jun 2022 12:53:37.376 # +selected-slave slave 192.168.120.121:6379 192.168.120.121 6379 @ mymaster 192.168.120.161 6379
   2307:X 08 Jun 2022 12:53:37.376 * +failover-state-send-slaveof-noone slave 192.168.120.121:6379 192.168.120.121 6379 @ mymaster 192.168.120.161 6379
   2307:X 08 Jun 2022 12:53:37.477 * +failover-state-wait-promotion slave 192.168.120.121:6379 192.168.120.121 6379 @ mymaster 192.168.120.161 6379
   2307:X 08 Jun 2022 12:53:37.975 # +promoted-slave slave 192.168.120.121:6379 192.168.120.121 6379 @ mymaster 192.168.120.161 6379
   2307:X 08 Jun 2022 12:53:37.975 # +failover-state-reconf-slaves master mymaster 192.168.120.161 6379
   2307:X 08 Jun 2022 12:53:38.058 # +failover-end master mymaster 192.168.120.161 6379
   2307:X 08 Jun 2022 12:53:38.058 # +switch-master mymaster 192.168.120.161 6379 192.168.120.121 6379
   2307:X 08 Jun 2022 12:53:38.058 * +slave slave 192.168.120.122:6379 192.168.120.122 6379 @ mymaster 192.168.120.121 6379
   2307:X 08 Jun 2022 12:53:38.058 * +slave slave 192.168.120.161:6379 192.168.120.161 6379 @ mymaster 192.168.120.121 6379
   2307:X 08 Jun 2022 12:53:43.095 # +sdown slave 192.168.120.161:6379 192.168.120.161 6379 @ mymaster 192.168.120.121 6379
   2307:X 08 Jun 2022 12:53:43.095 # +sdown slave 192.168.120.122:6379 192.168.120.122 6379 @ mymaster 192.168.120.121 6379
   
   ```

   哨兵发现Master没有pong，便认为Master主观下线了（sdown）。当超过quorum个哨兵都认为Master主观下线后，哨兵便认为Master客观下线了（odown）。然后进行故障恢复（try-failover），首先这个哨兵将新Msater的票投给635f34cf4fad025ed3014ab4db02cc27efc1a10a，其他哨兵也是这样，因此最终决定新Master是Ubuntu01（192.168.120.121）。

   选出新Master，只需1个哨兵来进行故障转移，对Ubuntu01的redis执行slaveof no one（+failover-state-send-slaveof-noone），Ubuntu01的Redis此刻变成Master模式：

   ```bash
   2144:M 08 Jun 2022 12:53:37.477 * Discarding previously cached master state.
   2144:M 08 Jun 2022 12:53:37.477 # Setting secondary replication ID to 0387cf0d82cd7d3072b8f8e54e77d45da1e128e6, valid up to offset: 41624. New replication ID is 195516c057be00bd61ea440bb3b8dbada742734f
   2144:M 08 Jun 2022 12:53:37.477 * MASTER MODE enabled (user request from 'id=7 addr=192.168.120.121:59532 laddr=192.168.120.121:6379 fd=10 name=sentinel-635f34cf-cmd age=172 idle=0 flags=x db=0 sub=0 psub=0 multi=4 qbuf=188 qbuf-free=40766 argv-mem=4 obl=45 oll=0 omem=0 tot-mem=61468 events=r cmd=exec user=default redir=-1')
   2144:M 08 Jun 2022 12:53:37.481 # CONFIG REWRITE executed with success.
   ```

   然后将旧Master标记为slave（+failover-state-reconf-slaves），将Ubuntu02的Redis执行slaveof命令，让它称为新Master的Slave（+slave slave 192.168.120.122:6379）。

2. 此时重启旧Master，查看集群信息：

   ```bash
   kjg@kjg-PC:/usr/local/redis/redis-6.2.4$ redis-cli 
   127.0.0.1:6379> info replication
   # Replication
   role:slave
   master_host:192.168.120.121
   master_port:6379
   master_link_status:up
   master_last_io_seconds_ago:0
   master_sync_in_progress:0
   slave_repl_offset:326851
   slave_priority:100
   slave_read_only:1
   replica_announced:1
   connected_slaves:1
   slave0:ip=192.168.120.122,port=6379,state=online,offset=326706,lag=0
   master_failover_state:no-failover
   master_replid:195516c057be00bd61ea440bb3b8dbada742734f
   master_replid2:0000000000000000000000000000000000000000
   master_repl_offset:326851
   second_repl_offset:-1
   repl_backlog_active:1
   repl_backlog_size:1048576
   repl_backlog_first_byte_offset:309607
   repl_backlog_histlen:17245
   127.0.0.1:6379> 
   ```

   甚至可以在旧Master的配置文件上发现成为Slave的配置：

   ![image](https://user-images.githubusercontent.com/48977889/172537726-d9aa2d02-9f30-43e0-9588-98479d07d14e.png)

## 67-RestTemplate整合哨兵订阅

RestTemplate的哨兵订阅，底层是基于Lettuce进行的

1. 添加配置：

   ```yaml
   spring:
   	redis:
   		sentinel:
   			master: mymaster
   			nodes:
   				- 192.168.120.121:27001
   				- 192.168.120.121:27002
   				- 192.168.120.121:27003
   ```

   **加上这个配置后，就不用配Redis节点的地址了。对于客户端来说，只需关心哨兵集群的地址信息**

2. 添加配置类

   ```java
   @Bean
   public LettuceClientConfigurationBuilderCustomizer configurationBuilderCustomizer(){
       return configBuilder -> configBuilder.readFrom(ReadFrom.REPLICA_PREFERRED);
   }
   ```

   ReadFrom包括：

   1. MASTER：从主节点读
   2. MASTER_PREFERRED：优先从主节点读
   3. REPLICA：从子节点读
   4. REPLICA_PREFERRED：优先从子节点读

3. 配置完成后，使用RedisTemplate进行操作时，写操作会自动转向主节点，读操作会根据2.的配置动态选择。

当然，在实际生产中发现并没有使用客户端整合哨兵订阅，而是使用传统的连接Redis服务的方式。**我猜是阿里云已经做好了配置，我们直接通过客户端连接Redis服务后，对这个服务的请求会被阿里云动态转发到不同的Redis节点上，当然这只是猜测。**

