# 36-分布式事务案例

![image](https://user-images.githubusercontent.com/48977889/169951147-143e95c3-8700-453d-9053-d5cea47e9e12.png)

1、2、3这3个操作都是独立的事务，3个操作组成一个完整的业务逻辑。如果1和2都成功了，3扣库存失败，就会引起脏库存这个生产问题，因此在微服务架构中需要一个组件来管理微服务之间的事务，使得多个独立的子事务共同组成**1个分布式事务**。

案例演示：

启动seata-account-service、seata-order-service、seata-storage-service这3个项目，有以下代码：

seata-order-service：

```java
@RestController
@RequestMapping("order")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/createOrder")
    public ResponseEntity<Long> createOrder(@RequestBody Order order){
        Long orderId = orderService.create(order);
        return ResponseEntity.status(HttpStatus.CREATED).body(orderId);
    }
}


@Service
public class OrderServiceImpl implements OrderService {

    private final AccountClient accountClient;
    private final StorageClient storageClient;
    private final OrderMapper orderMapper;

    public OrderServiceImpl(AccountClient accountClient, StorageClient storageClient, OrderMapper orderMapper) {
        this.accountClient = accountClient;
        this.storageClient = storageClient;
        this.orderMapper = orderMapper;
    }

    @Override
    @Transactional
    public Long create(Order order) {
        // 创建订单
        orderMapper.insert(order);
        try {
            // 扣用户余额
            accountClient.deduct(order.getUserId(), order.getMoney());
            // 扣库存
            storageClient.deduct(order.getCommodityCode(), order.getCount());

        } catch (FeignException e) {
            log.error("下单失败，原因:{}", e.contentUTF8(), e);
            throw new RuntimeException(e.contentUTF8(), e);
        }
        return order.getId();
    }
}
```

seata-storage-service：

```java
@RestController
@RequestMapping("storage")
public class StorageController {

    private final StorageService storageService;

    public StorageController(StorageService storageService) {
        this.storageService = storageService;
    }

    /**
     * 扣减库存
     * @param code 商品编号
     * @param count 要扣减的数量
     * @return 无
     */
    @PutMapping("/{code}/{count}")
    public ResponseEntity<Void> deduct(@PathVariable("code") String code,@PathVariable("count") Integer count){
        storageService.deduct(code, count);
        return ResponseEntity.noContent().build();
    }
}

@Service
public class StorageServiceImpl implements StorageService {

    @Autowired
    private StorageMapper storageMapper;

    @Transactional
    @Override
    public void deduct(String commodityCode, int count) {
        log.info("开始扣减库存");
        try {
            storageMapper.deduct(commodityCode, count);
        } catch (Exception e) {
            throw new RuntimeException("扣减库存失败，可能是库存不足！", e);
        }
        log.info("扣减库存成功");
    }
}
```

seata-account-service：

```java
@RestController
@RequestMapping("account")
public class AccountController {

    @Autowired
    private AccountTCCService accountService;

    @PutMapping("/{userId}/{money}")
    public ResponseEntity<Void> deduct(@PathVariable("userId") String userId, @PathVariable("money") Integer money){
        accountService.deduct(userId, money);
        return ResponseEntity.noContent().build();
    }
}

@Slf4j
public class AccountTCCServiceImpl implements AccountTCCService {

    @Autowired
    private AccountMapper accountMapper;
    @Autowired
    private AccountFreezeMapper freezeMapper;

    @Override
    @Transactional
    public void deduct(String userId, int money) {
        // 1.扣减可用余额
        accountMapper.deduct(userId, money);
    }
}
```

## 37-正常的分布式业务流程

从seata-order-service出发，新建1个订单：

```http
POST localhost:8082/order/createOrder
Content-Type: application/json

{
  "userId": "user202103032042012",
  "commodityCode": "100202003032041",
  "count": "2",
  "money": "200"
}
```

请求完成后，数据库的结果：

![image](https://user-images.githubusercontent.com/48977889/169955340-9200d7f4-6f95-40ae-a39c-e4bfa04c36f8.png)

![image](https://user-images.githubusercontent.com/48977889/169955360-4148e831-edd8-4deb-bff9-070c93b70adf.png)

![image](https://user-images.githubusercontent.com/48977889/169955381-dde06a62-2e9a-406b-a003-714cb86ba42b.png)

用户余额扣了200，库存减少2，生成了1条订单记录，整个业务流程是没问题的。

## 38-异常的分布式业务流程

我先回滚数据库状态：

![image](https://user-images.githubusercontent.com/48977889/169955621-33d71f3e-560d-4c94-b66a-ec3e3bb401e4.png)



![image](https://user-images.githubusercontent.com/48977889/169955630-0b0773fc-d440-4397-a694-d61e7ca018db.png)



![image](https://user-images.githubusercontent.com/48977889/169955647-68e14baf-c45d-4252-9431-9c6c728ea1a5.png)

假设现在我要买100个，明显库存是不够的，在seata-storage-service的deduct()流程里会触发异常，导致库存表没刷新上：

```http
POST localhost:8082/order/createOrder
Content-Type: application/json

{
  "userId": "user202103032042012",
  "commodityCode": "100202003032041",
  "count": "100",
  "money": "200"
}
```

![image](https://user-images.githubusercontent.com/48977889/169955340-9200d7f4-6f95-40ae-a39c-e4bfa04c36f8.png)

![image](https://user-images.githubusercontent.com/48977889/169955630-0b0773fc-d440-4397-a694-d61e7ca018db.png)

![image](https://user-images.githubusercontent.com/48977889/169955647-68e14baf-c45d-4252-9431-9c6c728ea1a5.png)

结果：由于orderservice的远程调用有trycatch处理，当发现扣库存服务响应失败后，新增的订单会进行回滚。但是！！！减余额的调用是正常的，导致最终用户的余额被扣了，库存和订单数没有影响，这是很严重的生产问题。

# CAP与BASE

在分布式系统下，一个业务跨越多个服务和数据库，每一个服务的数据都是一个独立的**子事务**，要保证1个业务的所有子事务最终状态一致，这样的事务就是分布式事务。在了解分布式事务之前，有必要回顾一下CAP与BASE理论

## 39-CAP

分布式系统的3个指标，分别是：

1. Consistency：一致性
   1. 访问分布式系统中任意1个节点，得到的数据必须与其他节点的一致。
2. Availability：可用性
   1. 访问集群中任意1个节点，必须得到响应，而不是超时和拒绝。
3. Partition Tolerance：分区容错性。
   1. 集群中的1个节点挂了，其他健康节点也要正常服务，使得整个集群可用（不会因为1个节点挂了，导致整个服务不可用）。

在分布式系统中，P是必须要满足的，但C和A只能满足1个，原因很好理解，从分区容错场景下推理有以下场景：

![image](https://user-images.githubusercontent.com/48977889/169958580-2e585fb7-0b38-4e58-bef6-7d01366eae68.png)

假设集群中存在node01、node02、node03节点，node03节点与其他节点的网络挂掉了，此时由于P的保证，其他两个节点仍是可访问，整个集群都是可用的。

如果要保证这个集群有一致性，整个集群需要等待node3恢复，并且将数据同步给node3。在此之前访问node1和node2是没问题的，他们两个的数据是一致的。但是访问node3的话请求会阻塞直至node3数据同步完成，请求时间超过timeout后甚至会拒绝访问。这就与A特性冲突了。

如果要保证这个集群有可用性，请求node03时候会立即响应里面的数据，但由于node3和其他节点的数据没同步，请求得到的结果可能是旧数据，这又与C冲突了。

所以在分布式系统中，一般有CP和AP两种特性的系统，具体使用哪个要看具体场景，比如ES是CP特性，Kafka可以通过配置决定是AP还是CP。

## 40-BASE理论

BASE理论是对CAP理论的一种解决方案，也可以理解为CAP的补充，主要包含3个思想：

Base Avaliable：基本可用，当集群出现故障时，允许损失部分节点的可用性，保持整个集群的基本可用。

Soft State：软状态，在一定时间内，允许临时的节点信息不一致状态。

Eventually Consistent：最终一致性，在软状态结束后，集群内的节点数据必须要同步。

**BASE更像是对A和P矛盾的一种调和**，比如ES集群，当发现节点不可用后，ES集群会将该节点剔除，这个节点就变得不可用了，这时CP模式。但是一旦发现该节点又可用了，ES会将这个被剔除的节点重新加入到集群中，重新分片与同步数据。也就是说虽然ES是CP模式，但也不是完全地不保证可用性，还是能保证Base Avaliable的。

## 41-BASE与分布式事务与Seata提出

对于分布式事务来说，最终想要的是各个子事务的结果保持一致，要么都成功要么都失败。落实到CAP理论可以将**全局事务**划分出的每个**子事务**都看成1个节点进行管理，落实到BASE理论可以对每个子事务的结果进行优化、同步。对于分布式事务来说，有两种模式：

1. AP模式：各个子事务分别提交，允许提交后出现结果不一致的情况，但这只是临时的（**软状态**）。对于软状态采用弥补措施修复数据，达成**最终一致**。
2. CP模式：各个子事务执行后**先别提交**，相互等待，同时回滚同时提交，从而达成**强一致**。但是在事务互相等待的过程中，整个业务流程处于**基本可用状态（弱可用）**。

可以看到，落实CAP与BASE理论的分布式事务处理有一个关键操作：**事务间的通信**。不管是AP还是CP模式，都需要一个协调者来获取子事务间的状态，从而决定下一步操作，这个协调者就是Seata框架。

# Seata

## 42-Seata三大角色：

![image](https://user-images.githubusercontent.com/48977889/170185286-07269a1e-6843-4e81-a0c4-8275ec54a4eb.png)

1. TC：即Transaction Coordinator，也就是知识点41提出的事务协调者，它维护着全局事务、子事务的状态，**决定全局事务的提交和回滚**。
2. TM：即Transaction Manager事务管理者，用来定义全局事务的范围，划分子事务有哪些，向TC**提交全局事务的开启行为**。
3. RM：即Resource Manager资源管理器，管理分支事务的开始，向TC报告分支事务的状态。

## 43-Seata四种分布式事务解决方案：

知识点42可以看到，Seata会根据子事务结果做进一步操作，那么这个进一步操作到底是什么呢？根据对Seata的配置有以下解决方案：

1. XA模式：强一致性分阶段事务模式（CP），牺牲一定可用性，无业务侵入。
2. TCC模式：最终一致性分阶段事务模式（AP），有业务侵入。
3. AT模式：最终一致性分阶段事务模式（AP），无业务侵入，**Seata的默认模式**。
4. SAGA模式：长事务模式，有业务侵入。

当然，这四种模式会在下面的知识点慢慢介绍。

## 44-部署TC：

所谓分布式事务的协调者，TC其实需要单独部署：

1. 解压Seata：

   ![image](https://user-images.githubusercontent.com/48977889/170188185-f3d12a26-9b95-4115-b354-1d03e8f85cb4.png)

2. Seata需要作为服务注册到注册中心里，比如注册进nacos：

   打开seata目录下的conf/registry.conf文件，修改注册配置：

   ```
   registry {
     # file 、nacos 、eureka、redis、zk、consul、etcd3、sofa
     type = "nacos"	#使用nacos
   
     nacos {
       application = "seata-tc-server"	#seata作为微服务，注册进nacos的名字
       serverAddr = "127.0.0.1:8848"	#nacos地址
       group = "DEFAULT_GROUP"			#因为微服务注册进nacos默认是DEFAULT_GROUP，这里要保持一致
       namespace = ""
       cluster = "MY"					#自定义集群名称
       username = "nacos"				#nacos账密
       password = "nacos"
     }
     
   }
   ```

3. Seata使用nacos的配置管理：

   打开seata目录下的conf/registry.conf文件，修改注册配置：

   ```
   config {
     # file、nacos 、apollo、zk、consul、etcd3
     type = "nacos"  #使用nacos配置
   
     nacos {
       serverAddr = "127.0.0.1:8848"	#nacos地址
       namespace = ""	
       group = "SEATA_GROUP"			#配置所属的组
       username = "nacos"					#nacos账密
       password = "nacos"
       dataId = "seataServer.properties"	#使用组内哪一个配置
     }
    
   }
   ```

4. 在nacos添加seata的配置：

   ```properties
   # 数据存储方式，db代表数据库
   store.mode=db
   store.db.datasource=druid
   store.db.dbType=mysql
   store.db.driverClassName=com.mysql.cj.jdbc.Driver
   store.db.url=jdbc:mysql://127.0.0.1:3306/seata?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true
   store.db.user=root
   store.db.password=123456
   store.db.minConn=5
   store.db.maxConn=30
   store.db.globalTable=global_table
   store.db.branchTable=branch_table
   store.db.queryLimit=100
   store.db.lockTable=lock_table
   store.db.maxWait=5000
   # 事务、日志等配置
   server.recovery.committingRetryPeriod=1000
   server.recovery.asynCommittingRetryPeriod=1000
   server.recovery.rollbackingRetryPeriod=1000
   server.recovery.timeoutRetryPeriod=1000
   server.maxCommitRetryTimeout=-1
   server.maxRollbackRetryTimeout=-1
   server.rollbackRetryTimeoutUnlockEnable=false
   server.undo.logSaveDays=7
   server.undo.logDeletePeriod=86400000
   
   # 客户端与服务端传输方式
   transport.serialization=seata
   transport.compressor=none
   # 关闭metrics功能，提高性能
   metrics.enabled=false
   metrics.registryType=compact
   metrics.exporterList=prometheus
   metrics.exporterPrometheusPort=9898	
   ```

   ![image](https://user-images.githubusercontent.com/48977889/170190239-def96e0f-200c-4383-aaa0-d446cea41645.png)

5. 创建TC的数据库seata库，导入以下ddl：

   ```mysql
   SET NAMES utf8mb4;
   SET FOREIGN_KEY_CHECKS = 0;
   
   -- ----------------------------
   -- 分支事务表
   -- ----------------------------
   DROP TABLE IF EXISTS `branch_table`;
   CREATE TABLE `branch_table`  (
     `branch_id` bigint(20) NOT NULL,
     `xid` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
     `transaction_id` bigint(20) NULL DEFAULT NULL,
     `resource_group_id` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci NULL DEFAULT NULL,
     `resource_id` varchar(256) CHARACTER SET utf8 COLLATE utf8_general_ci NULL DEFAULT NULL,
     `branch_type` varchar(8) CHARACTER SET utf8 COLLATE utf8_general_ci NULL DEFAULT NULL,
     `status` tinyint(4) NULL DEFAULT NULL,
     `client_id` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci NULL DEFAULT NULL,
     `application_data` varchar(2000) CHARACTER SET utf8 COLLATE utf8_general_ci NULL DEFAULT NULL,
     `gmt_create` datetime(6) NULL DEFAULT NULL,
     `gmt_modified` datetime(6) NULL DEFAULT NULL,
     PRIMARY KEY (`branch_id`) USING BTREE,
     INDEX `idx_xid`(`xid`) USING BTREE
   ) ENGINE = InnoDB CHARACTER SET = utf8 COLLATE = utf8_general_ci ROW_FORMAT = Compact;
   
   -- ----------------------------
   -- 全局事务表
   -- ----------------------------
   DROP TABLE IF EXISTS `global_table`;
   CREATE TABLE `global_table`  (
     `xid` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
     `transaction_id` bigint(20) NULL DEFAULT NULL,
     `status` tinyint(4) NOT NULL,
     `application_id` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci NULL DEFAULT NULL,
     `transaction_service_group` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci NULL DEFAULT NULL,
     `transaction_name` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NULL DEFAULT NULL,
     `timeout` int(11) NULL DEFAULT NULL,
     `begin_time` bigint(20) NULL DEFAULT NULL,
     `application_data` varchar(2000) CHARACTER SET utf8 COLLATE utf8_general_ci NULL DEFAULT NULL,
     `gmt_create` datetime NULL DEFAULT NULL,
     `gmt_modified` datetime NULL DEFAULT NULL,
     PRIMARY KEY (`xid`) USING BTREE,
     INDEX `idx_gmt_modified_status`(`gmt_modified`, `status`) USING BTREE,
     INDEX `idx_transaction_id`(`transaction_id`) USING BTREE
   ) ENGINE = InnoDB CHARACTER SET = utf8 COLLATE = utf8_general_ci ROW_FORMAT = Compact;
   
   SET FOREIGN_KEY_CHECKS = 1;
   ```

6. 在seata的lib路径下存放mysql的驱动

   ![截图_dde-file-manager_20220525203731](https://user-images.githubusercontent.com/48977889/170264939-d40f9666-09d4-4bde-9987-e8af44ba431a.png)
   
7. 直接./seata-server.sh 启动seata：

   ![image](https://user-images.githubusercontent.com/48977889/170265881-828ddc72-f908-4504-96a2-a5ba63efd9ad.png)

## 45-微服务集成Seata：

1. 微服务引入依赖：

   ```xml
   <dependency>
       <groupId>com.alibaba.cloud</groupId>
       <artifactId>spring-cloud-starter-alibaba-seata</artifactId>
       <exclusions>
           <exclusion>
               <artifactId>seata-spring-boot-starter</artifactId>
               <groupId>io.seata</groupId>
           </exclusion>
       </exclusions>
   </dependency>
   <dependency>
       <groupId>io.seata</groupId>
       <artifactId>seata-spring-boot-starter</artifactId>
       <version>${seata.version}</version>
   </dependency>
   ```

2. 添加配置：

   ```yaml
   seata:
     registry:
       type: nacos
       nacos:
         server-addr: 127.0.0.1:8848
         namespace: ""
         group: DEFAULT_GROUP
         application: seata-tc-server
         username: nacos
         password: nacos
     tx-service-group: seata-demo # 事务组名称
     service:
       vgroup-mapping: # 事务组与cluster的映射关系
         seata-demo: MY
   ```

3. 启动微服务后，可以看到Seata日志显示有服务接入：

   ​	![image](https://user-images.githubusercontent.com/48977889/170268340-0a5e052d-a439-497c-8a3b-c735b1e1a84f.png)

# Seata使用

## 46-XA模式

XA规范是一套分布式事务处理标准，基于所有主流数据库都提供了XA规范的支持。

![image](https://user-images.githubusercontent.com/48977889/170418207-bb9c4c39-bfad-43eb-9959-328720891c57.png)

XA规范主要分为两个阶段

第一阶段：

1. TC告诉RM执行子事务。
2. RM向TC反馈事务执行结果。

第二阶段：

1. TC判断各个RM的子事务结果，看看是否有失败。
2. 告诉RM下一步该走什么，如果子事务都成功，就让RM都提交自己的事务。如果有1个子事务失败，就让RM都回滚自己的事务。

Seata对XA的实现，实际上做了一层封装：

![image](https://user-images.githubusercontent.com/48977889/170419251-6c8182ca-8795-4007-86d6-1c64ea285ea7.png)

1. TM向TC注册全局事务，告诉TC自己有都少个子事务。
2. TM负责发起微服务之间的请求，RM**拦截并代理**这个请求，向TC注册子事务。
3. RM执行数据库事务，执行完成后不执行提交，而是向TC报告事务结果。
4. TM发现自己的RM都结束完成了，向TC报告子事务都执行完毕。
5. TC检查各个子事务状态，决定微服务们执行提交还是回滚。

XA模式的优缺点：

起码很多数据库本身就支持XA规范，开放了对应的接口给语言调用。不过要依赖数据库提供的实现，虽然所以没有代码侵入，但有一定的局限性。比如强一致性导致子事务之间都在等待，从而变成弱可用状态；比如不支持没有XA规范的数据库使用。

## 47-XA模式的使用

基于知识点46，已经了解了XA模式的流程和优缺点，接下来就是使用Seata来实现XA模式的事务处理。

1. 修改**参与分布式事务**的服务实例的配置文件，声明Seata使用XA模式：

   ```yaml
   seata:
     data-source-proxy-mode: XA
   ```

2. 全局事务的入口加上@GlobalTransactional注解：

   对于知识点36的案例来说，全局事务入口就是public Long create(Order order)方法：

   ```java
   @Override
   @GlobalTransactional
   public Long create(Order order) {
       // 创建订单
       orderMapper.insert(order);
       try {
           // 扣用户余额
           accountClient.deduct(order.getUserId(), order.getMoney());
           // 扣库存
           storageClient.deduct(order.getCommodityCode(), order.getCount());
   
       } catch (FeignException e) {
           log.error("下单失败，原因:{}", e.contentUTF8(), e);
           throw new RuntimeException(e.contentUTF8(), e);
       }
       return order.getId();
   }
   ```

   **被标记这个注解的方法就是TM，在Feign调用到下游时会传入1个XID，下游服务在调用@Transactional方法时会将XID作为子事务的凭证，注册进TC。**

3. 启动Nacos、Seata、3个服务实例，先跑一次正常的：

   ```http
   POST localhost:8082/order/createOrder
   Content-Type: application/json
   
   {
     "userId": "user202103032042012",
     "commodityCode": "100202003032041",
     "count": "2",
     "money": "200"
   }
   ```

   结果是正常的：

   

![image](https://user-images.githubusercontent.com/48977889/169955340-9200d7f4-6f95-40ae-a39c-e4bfa04c36f8.png)

![image](https://user-images.githubusercontent.com/48977889/169955360-4148e831-edd8-4deb-bff9-070c93b70adf.png)

![image](https://user-images.githubusercontent.com/48977889/169955381-dde06a62-2e9a-406b-a003-714cb86ba42b.png)

4. 再跑一个异常、会报库存数不足的：

```http
POST localhost:8082/order/createOrder
Content-Type: application/json

{
  "userId": "user202103032042012",
  "commodityCode": "100202003032041",
  "count": "10",
  "money": "200"
}

###

http://localhost:8082/order/createOrder

HTTP/1.1 500 
Content-Type: application/json
Transfer-Encoding: chunked
Date: Thu, 26 May 2022 05:29:04 GMT
Connection: close

{
  "timestamp": "2022-05-26T05:29:04.614+00:00",
  "status": 500,
  "error": "Internal Server Error",
  "message": "",
  "path": "/order/createOrder"
}
Response file saved.
> 2022-05-26T132904.500.json

Response code: 500; Time: 214ms; Content length: 131 bytes
```

​	此时会发现数据库的数据没有变，不会出现知识点38那样的结果，说明三个表都进行回滚了。而且在应用日志上也能看到回滚的信息：

Order：

```java
05-26 13:29:04:576  INFO 12593 --- [h_RMROLE_1_4_16] i.s.c.r.p.c.RmBranchRollbackProcessor    : rm handle branch rollback process:xid=172.17.0.1:8091:8421997982558396444,branchId=8421997982558396446,branchType=XA,resourceId=jdbc:mysql://localhost:3306/seata_demo,applicationData=null
05-26 13:29:04:576  INFO 12593 --- [h_RMROLE_1_4_16] io.seata.rm.AbstractRMHandler            : Branch Rollbacking: 172.17.0.1:8091:8421997982558396444 8421997982558396446 jdbc:mysql://localhost:3306/seata_demo
05-26 13:29:04:580  INFO 12593 --- [h_RMROLE_1_4_16] i.s.rm.datasource.xa.ResourceManagerXA   : 172.17.0.1:8091:8421997982558396444-8421997982558396446 was rollbacked
05-26 13:29:04:581  INFO 12593 --- [h_RMROLE_1_4_16] io.seata.rm.AbstractRMHandler            : Branch Rollbacked result: PhaseTwo_Rollbacked
05-26 13:29:04:605  INFO 12593 --- [nio-8082-exec-6] i.seata.tm.api.DefaultGlobalTransaction  : Suspending current transaction, xid = 172.17.0.1:8091:8421997982558396444
05-26 13:29:04:606  INFO 12593 --- [nio-8082-exec-6] i.seata.tm.api.DefaultGlobalTransaction  : [172.17.0.1:8091:8421997982558396444] rollback status: Rollbacked
05-26 13:29:04:609 ERROR 12593 --- [nio-8082-exec-6] o.a.c.c.C.[.[.[/].[dispatcherServlet]    : Servlet.service() for servlet [dispatcherServlet] in context with path [] threw exception [Request processing failed; nested exception is java.lang.RuntimeException: {"timestamp":"2022-05-26T05:29:04.535+00:00","status":500,"error":"Internal Server Error","message":"","path":"/storage/100202003032041/10"}] with root cause

feign.FeignException$InternalServerError: [500] during [PUT] to [http://seata-storage-service/storage/100202003032041/10] [StorageClient#deduct(String,Integer)]: [{"timestamp":"2022-05-26T05:29:04.535+00:00","status":500,"error":"Internal Server Error","message":"","path":"/storage/100202003032041/10"}]
```

Account：

```java
05-26 13:29:04:461 DEBUG 12490 --- [nio-8083-exec-5] c.i.account.mapper.AccountMapper.deduct  : ==>  Preparing: update account_tbl set money = money - 200 where user_id = ?
05-26 13:29:04:462 DEBUG 12490 --- [nio-8083-exec-5] c.i.account.mapper.AccountMapper.deduct  : ==> Parameters: user202103032042012(String)
05-26 13:29:04:464 DEBUG 12490 --- [nio-8083-exec-5] c.i.account.mapper.AccountMapper.deduct  : <==    Updates: 1
05-26 13:29:04:563  INFO 12490 --- [h_RMROLE_1_5_16] i.s.c.r.p.c.RmBranchRollbackProcessor    : rm handle branch rollback process:xid=172.17.0.1:8091:8421997982558396444,branchId=8421997982558396448,branchType=XA,resourceId=jdbc:mysql://localhost:3306/seata_demo,applicationData=null
05-26 13:29:04:563  INFO 12490 --- [h_RMROLE_1_5_16] io.seata.rm.AbstractRMHandler            : Branch Rollbacking: 172.17.0.1:8091:8421997982558396444 8421997982558396448 jdbc:mysql://localhost:3306/seata_demo
05-26 13:29:04:567  INFO 12490 --- [h_RMROLE_1_5_16] i.s.rm.datasource.xa.ResourceManagerXA   : 172.17.0.1:8091:8421997982558396444-8421997982558396448 was rollbacked
05-26 13:29:04:569  INFO 12490 --- [h_RMROLE_1_5_16] io.seata.rm.AbstractRMHandler            : Branch Rollbacked result: PhaseTwo_Rollbacked
```

## 48-AT模式

![image](https://user-images.githubusercontent.com/48977889/170477671-c5d83aca-0374-4da5-8aa0-d3d5f2aa97fe.png)

1. TM向TC注册全局事务，告诉TC自己有都少个子事务。
2. TM负责发起微服务之间的请求，RM**拦截并代理**这个请求，向TC注册子事务。

到这里为止其实和XA没什么区别，重点是下面：

3. RM记录事务前的记录快照。
4. 执行事务，事务完成后**直接提交**。
5. RM向TC报告子事务完毕
6. TM发现所有子事务完毕，向TC报告全局事务完毕。
7. TC检查各个子事务的状态
   1. 都成功：RM删除快照。
   2. 有失败：每个RM根据快照的内容回滚数据，然后删除快照。

这样就有点意思了，想一想，AT模式的分布式事务更依赖于TC来完成，一旦1-4步骤完成后，子事务对于数据库来说就是一个一个独立完成好的事务，那么在4之后7之前的操作都会使得数据在业务层面上“变脏了”。在数据库眼里它们是干净的，但在分布式事务了，已经变脏了。AT模式是典型的时候弥补型、最终一致性型，会导致业务数据存在软状态（变脏了）。AT模式在事务提交后、TC确认前会有一个临时的软状态，此时对软状态会有脏读和脏写问题。先说脏写：

### AT脏写

![image-20220526193628535](markdown-img/day04.assets/image-20220526193628535.png)

1. 全局事务A的子事务1对id=1的money进行操作，先获取**事务锁**。
2. 全局事务A的子事务1更改id=1的money为90，由于是AT模式，直接提交。
3. 全局事务A的子事务1释放**事务锁**。
4. 事务2对id=1的money进行操作，获取到3.释放的**事务锁**。
5. 事务2将id=1的money-10，最终结果是80，提交事务，释放**事务锁**。
6. 此时TC发现全局事务A有其他子事务失败了，向RM提出回滚。
7. RM进行回滚，将2.之前的数据快照拿出来，进行恢复。
8. id=1的money从80变成了100。**事务2的操作被覆盖了**，因为事务2在全局事务A还未结束前对id=1的数据进行了**脏写**。

 其实脏写最主要的原因是**作为上层设计的分布式事务**与**作为下层设计的其他事务**没有做到隔离，Seata为了防止AT脏写提出了“全局锁的概念”：

![image](https://user-images.githubusercontent.com/48977889/170483902-dfc36cb0-3194-4110-b732-0ed52f4c4756.png)

0. 全局锁是Seata维护的列表，用来保存**某个子事务**对**某张表**的**某行数据**进行了操作。
1. 全局事务A的子事务1对id=1的money进行操作，先获取**事务锁**。
2. 全局事务A的子事务1更改id=1的money为90，在提交之前，先占有全局锁。
3. 占有全局锁成功，子事务1提交事务，释放事务锁。
4. 事务2对id=1的money进行操作，获取到3.释放的**事务锁**。
5. 事务2将id=1的money-10，最终结果是80。
6. 事务2在提交事务之前，尝试占用全局锁，结果发现全局锁被占用了，不停地重试等待。
7. 此时TC发现全局事务A有其他子事务失败了，向事务1的RM提出回滚。
8. RM恢复快照数据时，需要**事务锁**，但此时事务锁被事务2持有，事务2等待这条记录的全局锁，但全局锁又被事务1占有...这样就陷入了死锁困境了。
9. 不过好在Seata对全局锁的等待是有timeout的，默认是30次，每次10ms，也就是说事务2正在300ms后会释放**事务锁**，直接失败回滚。
10. 此时事务1就能重新获取**事务锁**，执行恢复快照数据的事务了。

总的来说，AT也是通过互斥性来防止脏写的发生，XA和AT虽然都有互斥性，但AT的互斥粒度更小一点，XA是DB的行锁。AT是全局锁，不过只是Seata层面维护的行锁，并且只限定**Seata事务对该行的读写操作**，DB锁是针对所有事务对该行的读写操作，**其他不归Seata管的事务还是可以进行“脏写”的**，在互斥粒度上，AT很明显比XA要小。 

### AT脏读

和AT脏写差不多，当事务1提交事务后（数据库层面），事务1回滚之前（分布式事务层面）。事务2拿到事务1提交的结果进行判断，此时这个结果在数据库眼里是干净的，但在业务流程上已经变脏了。

同样地，AT的读隔离也是通过全局锁判断，不过也是有2个前提：事务2必须是Seata管理的事务，并且读语句必须是select for update。事务2在操作select for update语句时，会检查select的行是否被全局锁占用了，如果是则回滚本地事务，通过 while 循环不断地重新竞争获取事务锁和全局锁。通过Seata源码可以判断，当**发现Seata管理的事务**执行select for update后，会走以下流程：

io.seata.rm.datasource.exec.SelectForUpdateExecutor#doExecute

```java
public T doExecute(Object... args) throws Throwable {
    Connection conn = statementProxy.getConnection();
    // ... ...
    try {
        // ... ...
        while (true) {
            try {
                // ... ...
                if (RootContext.inGlobalTransaction() || RootContext.requireGlobalLock()) {
                    // Do the same thing under either @GlobalTransactional or @GlobalLock, 
                    // that only check the global lock  here.
                    statementProxy.getConnectionProxy().checkLock(lockKeys);
                } else {
                    throw new RuntimeException("Unknown situation!");
                }
                break;
            } catch (LockConflictException lce) {
                if (sp != null) {
                    conn.rollback(sp);
                } else {
                    conn.rollback();
                }
                // trigger retry
                lockRetryController.sleep(lce);
            }
        }
    } finally {
        // ...
    }
```

### AT优点

可以直接提交，占用事务锁的时间少，高可用。

### AT缺点

有软状态，在软状态的情况下有可能会被其他事务脏写脏读，其他事务是Seata管理事务的话有可能引起脏回滚（如果事务2又进行了回滚，又要执行恢复快照数据为90了，那事务1不是白回滚了？），非Seata管理的事务有可能引起脏写脏读，对事务异常状态包容性不高，**需要在业务上尽量避免脏写脏读的发生**。

## 49-AT使用

1. 在TC的数据库导入全局锁ddl：

   ```mysql
   DROP TABLE IF EXISTS `lock_table`;
   CREATE TABLE `lock_table`  (
     `row_key` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
     `xid` varchar(96) CHARACTER SET utf8 COLLATE utf8_general_ci NULL DEFAULT NULL,
     `transaction_id` bigint(20) NULL DEFAULT NULL,
     `branch_id` bigint(20) NOT NULL,
     `resource_id` varchar(256) CHARACTER SET utf8 COLLATE utf8_general_ci NULL DEFAULT NULL,
     `table_name` varchar(32) CHARACTER SET utf8 COLLATE utf8_general_ci NULL DEFAULT NULL,
     `pk` varchar(36) CHARACTER SET utf8 COLLATE utf8_general_ci NULL DEFAULT NULL,
     `gmt_create` datetime NULL DEFAULT NULL,
     `gmt_modified` datetime NULL DEFAULT NULL,
     PRIMARY KEY (`row_key`) USING BTREE,
     INDEX `idx_branch_id`(`branch_id`) USING BTREE
   ) ENGINE = InnoDB CHARACTER SET = utf8 COLLATE = utf8_general_ci ROW_FORMAT = Compact;
   
   
   SET FOREIGN_KEY_CHECKS = 1;
   ```

2. 在参与分布式事务的服务实例的数据库导入unlog的ddl：

   ```mysql
   DROP TABLE IF EXISTS `undo_log`;
   CREATE TABLE `undo_log`  (
     `branch_id` bigint(20) NOT NULL COMMENT 'branch transaction id',
     `xid` varchar(100) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL COMMENT 'global transaction id',
     `context` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL COMMENT 'undo_log context,such as serialization',
     `rollback_info` longblob NOT NULL COMMENT 'rollback info',
     `log_status` int(11) NOT NULL COMMENT '0:normal status,1:defense status',
     `log_created` datetime(6) NOT NULL COMMENT 'create datetime',
     `log_modified` datetime(6) NOT NULL COMMENT 'modify datetime',
     UNIQUE INDEX `ux_undo_log`(`xid`, `branch_id`) USING BTREE
   ) ENGINE = InnoDB CHARACTER SET = utf8 COLLATE = utf8_general_ci COMMENT = 'AT transaction mode undo table' ROW_FORMAT = Compact;
   ```

3. 修改服务实例的配置文件，使用AT：

   ```yaml
   seata:
     data-source-proxy-mode: AT
   ```

4. 使用和XA没区别，也是在全局事务入口加上@GlobalTransactionl注解，代码实例和知识点47一样

5. 接下来尝试一下异常案例：

```http
POST localhost:8082/order/createOrder
Content-Type: application/json

{
  "userId": "user202103032042012",
  "commodityCode": "100202003032041",
  "count": "10",
  "money": "200"
}
```

结果和知识点57一样。

## 50-AT的防脏读、防脏写演示

// TODO

## 51-TCC模式

**前言：TCC更考验编码逻辑和测试用例，代码侵入性很高，稍有不慎就会引发生产事故，因此谨慎使用。**

![image](https://user-images.githubusercontent.com/48977889/170924972-8552598a-0265-4f9b-91d9-30cb7e715a00.png)

1. TCC即Try、Confirm、Cancel。一阶段是各个服务实例执行子事务逻辑，直接提交，有点类似AT模式。但是！！！TCC模式的提交属于**资源预留**，既然叫资源预留，那在二阶段还是有可能归还（回滚）回去的，我该如何判断资源被预留了呢？此时**就需要一张记录资源预留信息的表**。
2. 和AT类似，当TM发现子事务都执行完毕了，报告给TC，TC根据子事务的状态决定是Confirm（提交）还会Cancel（回滚）。但是！！！这里的提交和回滚是针对资源预留结果来说的，如果Confirm了就代表这个资源预留是有效的、成功的，反之Cancel即代表资源预留是无效了，需要将预留的资源返还。
3. 也就是说，在Try流程结束后，Confirm或Cancel结束前TCC事务状态存在软状态，这时候就要基于人为的代码控制来判断软状态是否存在。这也是为什么TCC模式的代码侵入性非常高。

毕竟TCC和业务关联性很强， 我举个例子：加入子事务需要对用户余额100进行扣款40，如果使用TCC会怎么做呢？

1. Try阶段：直接对用户余额-40，并且往余额冻结表（**资源预留信息表**）里插入1条记录，记录包含事务ID、冻结金额、用户ID，事务状态为try。提交事务，此时用户余额是40。
2. Confirm阶段：将事务ID所在的冻结表的记录状态改为confirm，此时事务才算提交完成。
3. Cancel阶段：将事务ID所在的冻结表记录状态改为cancel，将用户余额+40，此时事务回滚完成。

就这样一个简单的TCC流程，实际涉及到很多场景的考虑，比如：

1. 假如我在try阶段发生异常，或者注册了分支事务后，执行Try超时了。TC会让事务实例执行Cancel操作，那么Cancel恢复余额时要考虑资源预留信息表记录不存在的情况（允许空回滚）。
2. 注册了分支事务后，执行Try超时了导致Cancel先执行。这时候要是Try恢复执行，就会导致执行了Try但不会执行Confirm或Cancel。那么在执行try的时候应该考虑**是否Cancel过了**（避免业务悬挂）。
3. 执行Try后很正常，资源也预留成功了，但是在Confirm或者Cancel前，其他事务SELECT被预留的资源该怎么办？这时还处于软状态，有可能会得到脏数据。或者其他事务UPDATE了预留资源，这有涉及到脏读脏写问题了。可是TCC模式没有TA那样的全局锁、也不会像XA那样全程拿着事务锁。**在TCC模式下，只能通过业务代码判断预留资源表是否有要操作的、并且处于try阶段的数据，如果有要进行特殊处理，也就是说在数据的写隔离和读隔离依赖于自定义的代码，Seata不会帮我们做这件事。
3. 总的来说，并不是所有事务都适合TCC模式，它只能通过代码人为控制资源预留信息表的方式防止脏读、脏写，对Try、Confirm、Cancel需要做好幂等处理。

## 52-TCC模式的使用

在知识点51的例子，仅针对Account服务实现TCC事务模式，Seata的分布式事务模式是细分到服务实例层面的，一个微服务的不同实例可以使用不同的分布式事务模式。

1. 对于Try、Confirm、Cancel操作的类添加@LocalTCC注解。对Try操作添加@TwoPhaseBusinessAction注解，指定Confirm和Cancel的操作方法名：

   ```java
   @LocalTCC
   public interface AccountTCCService {
   
       @TwoPhaseBusinessAction(name = "deduct",commitMethod = "confirm",rollbackMethod = "cancel")
       void deduct(@BusinessActionContextParameter(paramName = "userId") String userId,
                   @BusinessActionContextParameter(paramName = "money") int money);
   
       boolean confirm(BusinessActionContext ctx);
   
       boolean cancel(BusinessActionContext ctx);
   }
   ```

对于Confirm和Cancel操作来说，可以添加BusinessActionContext参数来获取Try-Confirm-Cancel过程中的公共数据，可以理解为上下文。而Try操作的参数可以添加@BusinessActionContextParameter()，方便上下文获取到。

2. 引入资源预留信息表，在这个业务中是“冻结余额表”，每次**扣款后都往冻结余额表插入一条记录，并注明扣款状态、事务ID**，这一整个为Try操作：

   ```mysql
   DROP TABLE IF EXISTS `account_freeze_tbl`;
   CREATE TABLE `account_freeze_tbl`  (
     `xid` varchar(128) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
     `user_id` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci NULL DEFAULT NULL,
     `freeze_money` int(11) UNSIGNED NULL DEFAULT 0,
     `state` int(1) NULL DEFAULT NULL COMMENT '事务状态，0:try，1:confirm，2:cancel',
     PRIMARY KEY (`xid`) USING BTREE
   ) ENGINE = InnoDB CHARACTER SET = utf8 COLLATE = utf8_general_ci ROW_FORMAT = COMPACT;
   SET FOREIGN_KEY_CHECKS = 1;
   ```

3. 编写Try操作，注意业务悬挂：

   ```java
       @Override
       @Transactional
       @GlobalTransactional
       public void deduct(String userId, int money) {
           // 0.获取事务id
           String xid = RootContext.getXID();
           // 1 先看是否被空回滚了，如果是，就不要try了，防止业务悬挂。
           AccountFreeze exist = freezeMapper.selectById(xid);
           if (exist != null) {
               return;
           }
           // 2.扣减可用余额
           accountMapper.deduct(userId, money);
           // 3.记录冻结金额，事务状态
           AccountFreeze freeze = new AccountFreeze();
           freeze.setUserId(userId);
           freeze.setFreezeMoney(money);
           freeze.setState(AccountFreeze.State.TRY);
           freeze.setXid(xid);
           freezeMapper.insert(freeze);
       }
   ```

4. 编写Confirm操作，提交成功后直接删除资源预留表的记录：

   ```java
   @Override
   public boolean confirm(BusinessActionContext ctx) {
       // 1.获取事务id
       String xid = ctx.getXid();
       // 2.根据id删除冻结记录
       int count = freezeMapper.deleteById(xid);
       return count == 1;
   }
   ```

5. 编写Cancel操作，注意要考虑还没执行Try就Cancel了：

   ```java
   @Override
   public boolean cancel(BusinessActionContext ctx) {
       // 0.查询冻结记录
       String xid = ctx.getXid();
       AccountFreeze freeze = freezeMapper.selectById(xid);
       // 1.是否还没Try就Cancel了？如果是代表本次是空回滚，需要插入一条Cancel的记录
       if (freeze == null) {
           // 获取Try阶段的参数
           String userId = ctx.getActionContext("userId").toString();
           freeze = new AccountFreeze();
           freeze.setXid(xid);
           freeze.setState(AccountFreeze.State.CANCEL);
           freeze.setFreezeMoney(0);
           freeze.setUserId(userId);
           freezeMapper.insert(freeze);
           return true;
       }
       // 2.恢复可用余额
       accountMapper.refund(freeze.getUserId(), freeze.getFreezeMoney());
       // 3.将冻结金额清零，状态改为CANCEL
       freeze.setFreezeMoney(0);
       freeze.setState(AccountFreeze.State.CANCEL);
       int count = freezeMapper.updateById(freeze);
       return count == 1;
   }
   ```

6. 启动项目后，跑知识点37和知识点38的用例，测试没问题，还能看到资源预留信息表有CANCEL记录：

   ![image](https://user-images.githubusercontent.com/48977889/171099292-de902916-ff62-4439-99a6-75bc8f3f314e.png)

   

## 53-SAGA模式

以全局事务为入口按序执行子事务，一旦发现子事务异常回滚，则前面的子事务倒叙回滚。这种分布式事务模式既没有全局锁，也没有资源冻结，采用长事务的解决办法。**但是软状态太长了，没有隔离性页导致无法避免脏写和脏读，只适用于旧系统添加分布式事务功能**。

![image](https://user-images.githubusercontent.com/48977889/170922614-46e244d4-25eb-4cb1-9e94-8dfec9cabe6f.png)

## 54-4种模式对比

![image](https://user-images.githubusercontent.com/48977889/170923018-8fc2ee37-ed67-437c-b504-bacaa429fcd4.png)

总的来说，一般业务场景下考虑强一致弱实时就XA，考虑弱一致强实时就AT，要求高强度实时性就考虑TCC（不过开发流程和测试流程比较复杂），一般不考虑TCC。
