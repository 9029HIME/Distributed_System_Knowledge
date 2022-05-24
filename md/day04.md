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

