# 分布式配置中心

## 10-Nacos作为分布式配置中心

1. 在Nacos服务器添加配置文件，注意namespace要和服务实例一致

   ![image](https://user-images.githubusercontent.com/48977889/166636379-e96efae0-8cef-4855-9ee7-135c0cea3d48.png)

2. 需要知道的是，SpringCloud应用再启动时先获取Nacos的配置，再读取application的配置。但是！应用如何知道Nacos的配置在哪呢？这就需要有回老朋友bootstrap配置文件了，具体流程如下：

   ![image](https://user-images.githubusercontent.com/48977889/166635340-2416e0f7-3595-40fd-8f8a-87ba708e6ced.png)

3. 添加一下bootstrap配置：

   ```yaml
   spring:
     application:
       name: orderservice #服务名称
     profiles:
       active: dev # 环境
     cloud:
       nacos:
         server-addr: localhost:8848 # nacos地址
         config:
           file-extension: yaml # 文件后缀名
   ```

   值得注意的是，服务名称+环境+文件后缀名组成orderservice-dev.yaml，**正好能和Nacos中声明的配置ID一致，就是靠这一点才匹配上的**。

4. 需要接入Nacos配置中心的实例，请加上以下依赖：

```xml
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
</dependency>
```

5. 对于Nacos作为分布式配置中心来说，也是支持热加载的，有两种方式：
   1. 使用@Value注解赋值时，只需在@Value注解所在类加上一个@RefreshScope注解即可。
   2. 使用@ConfigurationProperties(prefix="")时，默认支持热加载。

## 11-Nacos的全局配置

知识点10.3说到，Nacos通过${服务名称}-${环境}.${文件名后缀}组成的配置ID找到对应的配置，实际上除了这个id外，Nacos还会默认找${服务名称}.${文件名后缀}这个配置ID对应的配置，并把这个配置作为全局配置来使用，基于这一点可以实现多个active间共用同一套配置。

那么问题来了，Nacos全局配置、Nacos环境配置、项目本地配置哪个优先级更高呢？虽然知识点10.2说到先加载Nacos，最后才加载本地配置，但实际上本地配置的优先级是最低的，其次是Nacos全局配置，Nacos环境配置，即**环境配置 ＞ 全局配置 ＞ 本地配置**。至于是为什么，要想想使用Nacos的初衷是为了实现热加载更改、不用重启项目的方式来刷新配置，如果本地配置的优先级最高，那热加载还有什么意义呢。

# 高级远程调用

## 12-OpenFeign

回顾知识点1，RestTemplate是最基本的远程调用方式，配合@LoadBalanced注解使用也能实现RestTemplate的负载均衡。但是RestTemplate需要使用URL+参数的形式调用，并且每一次调用和封装时代码都比较冗余，**不太适用于微服务之间的接口调用，更适用于对接外部接口（如服务商、合作方暴露的接口）**，因此提出一个更优雅的实现：OpenFeign，它是SpringCloud基于Netfix的Feign二次开发的产品，更加兼容SpringCloud。

消费者添加依赖：

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-openfeign</artifactId>
</dependency>
```

添加Enable注解：

```java
@EnableFeignClients
public class OrderApplication {
}
```

消费者添加远程调用接口：

```java
// 这里的value和提供者的注册名称一致
@FeignClient("userservice")
public interface UserClient {
    @GetMapping("/user/{id}")
    User queryById(@PathVariable("id") Long id);
}
```

值得注意的是接口定义的方法和注解，需要与消费者中定义的一致，**因此后续考虑将通用的定义如返回值，参数等Java文件放在公共的项目中**。

使用远程调用接口：

```java
@Autowired
private UserClient userClient;

public Order queryOrderById(Long orderId) {
        // 1.查询订单
        Order order = orderMapper.findById(orderId);
		User user = userClient.queryById(order.getUserId());
		order.setUser(user);
}
```

其实OpenFeign里面已经实现了负载均衡，也是基于Ribbon的，也就是说默认OpenFeign = Ribbon + RestTemplate

## 13-OpenFeign的优化1

OpenFeign底层默认使用URLConnection来实现网络请求，但是这个底层组件不支持池化思想，每一次调用都要重新建立连接。可以将OpenFeign的底层客户端改为HttpClient或OKHttp，以HttpClient为例，步骤如下：

1. 引入依赖：

```xml
<dependency>
    <groupId>io.github.openfeign</groupId>
    <artifactId>feign-httpclient</artifactId>
</dependency>
```

2. 添加配置：

```yaml
feign:
  httpclient:
    enabled: true # 支持HttpClient的开关
    max-connections: 200 # 最大连接数
    max-connections-per-route: 50 # 单个路径的最大连接数
```

## 14-OpenFeign的优化2

结合实例代码可以发现，消费者和提供者都要使用同一套代码，这样非常地冗余。实际上，可以将服务调用的传参、响应统一放在一个公共模块里，消费者和提供者只需共同依赖这个模块即可。

1. 新建一个common-feign模块，引入feign和HttpClient的依赖，同时userservice和orderservice共同引用common-feign。
2. 将orderservice的User和UserClient移到common-feign模块里，删除userservice的User。
3. 但是！！！这样会引发一个问题：UserClient被挪到common-feign后，无法被orderservice的启动类扫描到了，因此需要在启动类加上：

```java
@MapperScan("cn.itcast.order.mapper")
@SpringBootApplication
@EnableFeignClients(basePackages = {"cn.itcast.commonfeign.client"})
public class OrderApplication {
}
```

4. 这样，就能减少代码的冗余，如果userserivce接口内容发生改变，只需改common-feign即可。

# Gateway

## 15-Gateway的整体概念

以前对Gateway的概念比较混淆，现在可以明确的是：**Gateway和Nginx虽然功能相似，但本质却不一样！！！。**总的来说可以这样划分：

1. Nginx：访问的第一层入口，除了微服务外还包含了前端资源的负载均衡，因此Nginx严格意义上来说不属于微服务的一部分。**它的负载均衡是针对外部请求到本机应用的**。
2. Gateway：被Nginx代理后，访问的第二层入口，**本质上属于微服务的一部分**，可以理解为**微服务的网关**，用来路由并负载均衡到本次业务第一个需要的微服务。**它的负载均衡是针对外部请求到微服务的**。
3. Ribbon：不属于访问入口，它通过注册中心找到目标地址后，通过负载均衡请求到具体的服务实例，本质属于微服务的一部分。**它的负载均衡是针对微服务之间的调用**。

一般来说，请求先经过Nginx转发到Gateway，再通过Gateway转发到具体的服务实例A，实例A可能立即返回结果，也可能通过Ribbon+注册中心请求其他服务实例B后，将结果整合并返回。

Gateway的作用：

1. 可以对请求进行一次全局的身份验证、权限校验
2. 负载均衡
3. 请求限流

Gateway主流有两种选型：Zuul和gateway（小写），现在基本用gateway。

## 16-gateway搭建

1. gateway本质是一个Java微服务应用，因此第一步先导入Nacos和gateway依赖：

```xml
<!--nacos服务注册发现依赖-->
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
</dependency>
<!--网关gateway依赖-->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-gateway</artifactId>
</dependency>
```

2. 添加gateway配置：

```yaml
spring:
  application:
    name: gateway
  cloud:
    nacos:
      server-addr: localhost:8848 # nacos地址
    gateway:
      routes:
        - id: user-service # 路由标示，必须唯一
          uri: lb://userservice # 路由的目标地址
          predicates: # 路由断言，判断请求是否符合规则
            - Path=/user/** # 路径断言，判断路径是否是以/user开头，如果是则符合
        - id: order-service
          uri: lb://orderservice
          predicates:
            - Path=/order/**
```

 路由标示代表着一个gateway转发，可以理解为一个gateway转发规则的id

 目标地址代表要转发过去的目的地，lb://代表使用负载均衡，userservice是Nacos里的服务名。

 路由断言表示uri通配符，可以配多个

 总的来说，如果转发到gateway的请求路径命中了某个**路由表示**的**路由断言**，则将该请求转发到**目标地址**对应的服务实例那。

本地启动gateway实例，试试请求localhost:10010/order/1，发现可以请求成功。

![image](https://user-images.githubusercontent.com/48977889/166942919-920735b7-acbd-4f4e-815e-a4339638b851.png)
