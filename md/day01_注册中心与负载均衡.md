# 远程调用的方式

## 1-最基本的调用方式

RestTemplate是最基本的远程调用方式，只需注入RestTemplate对象，调用对应的API请求下游系统即可。

```java
public Order queryOrderById(Long orderId) {
    // 1.查询订单
    Order order = orderMapper.findById(orderId);

    /*
     day01-1：远程调用的雏形-RestTemplate，属于是最基本的远程调用方式
     */
    User user = restTemplate.getForObject(String.format("http://localhost:8081/user/%s", order.getUserId()), User.class);
    order.setUser(user);
    
    return order;
}
```

# 注册中心

## 2-远程调用的问题

在知识点1中可以发现，远程调用的提供者是采用硬编码的方式写死在消费者的url里，这样非常不灵活。因此可以引入“注册中心”这个角色，每当一个提供者启动时候都主动注册进注册中心里，当消费者需要调用时，只需根据“服务内容”向注册中心请求提供者的信息，注册中心根据特定规则将符合条件的提供者返回给消费者。

打个比方，如**订单服务**需要请求**用户服务**的**用户信息接口**，**订单服务**需要将**用户信息接口**这个服务内容作为参数请求注册中心，注册中心根据特定规则，将符合条件的**用户服务**提供者信息返回给**订单服务**，**订单服务**再通过远程调用的方式请求接口，获得最终结果。

## 3-Eureka

注册中心有很多种，虽然每一种的大致功能都一样，但细微的实现可能略有不同，现以Eureka举例。Eureka有个特点是：Eureka会将所有提供者的信息返回给消费者，消费者再通过负载均衡的方式选择一个来请求。不过Eureka已经逐渐被淘汰了，这里提出来只是作为入门知识了解注册中心的作用（**仅仅是2.x版本胎死腹中，1.x版本还在维护中**）

### 搭建

引入依赖：

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-netflix-eureka-server</artifactId>
</dependency>
```

启动类加上启动注解：

```java
@EnableEurekaServer
@SpringBootApplication
public class EurekaApplication {
    public static void main(String[] args) {
        SpringApplication.run(EurekaApplication.class, args);
    }
}
```

添加配置，指定Eureka服务端的信息：

```yaml
eureka:
  client:
    service-url:  # eureka的地址信息
      defaultZone: http://127.0.0.1:10086/eureka
```

当服务启动后，访问主机的10086/eureka后，就能访问进eureka主页了：

![image-20220503153951027](https://user-images.githubusercontent.com/48977889/166444914-80d9d4eb-d6ca-4cc8-bfa7-7372b9a6f573.png)

### 注册

搭建好eureka服务器后，需要将提供者注册进eureka内。

提供者与消费者引入依赖：

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
</dependency>
```

提供者与消费者添加配置：

```yaml
spring:
  application:
  	#注册进eureka后的名字
    name: user_service
eureka:
  client:
    service-url:  # eureka的地址信息
      defaultZone: http://127.0.0.1:10086/eureka
```

注册进eureka的配置信息与eureka服务器配置一样，区别是引入的依赖是client端。

启动了消费者和提供者后(提供者启动2个)，eureka的列表信息如下：

![image-20220503155616152](https://user-images.githubusercontent.com/48977889/166444919-e7930c31-0821-426e-bba3-ba722bf95b0c.png)

### 使用

搭建eureka、注册提供者和消费者后，就到消费者的使用了。

注入RestTemplate的时候，增加@LoadBalanced注解：

```java
//day01-3：在restTemplate上面加上@LoadBalanced注解，配合Eureka注册中心，实现负载均衡
@LoadBalanced
@Bean
public RestTemplate restTemplate() {
    return new RestTemplate();
}
```

服务调用的时候，消费者请求提供者的${主机+端口}直接改成${服务名}，注意服务名不能有下划线_ssss：

```java
User user = restTemplate.getForObject(String.format("http://userservice/user/%s", order.getUserId()), User.class);
```

## 6-Nacos作为注册中心

### 搭建nacos服务器

国内开源的注册中心，功能比Eureka更强大，但是功能不止注册中心，还包含分布式配置中心（类似apollo）等功能。

安装好Nacos，更改好配置脚本后，直接启动：

![image](https://user-images.githubusercontent.com/48977889/166611355-111f531e-da51-425c-9f24-4b5e1920b65c.png)

-m standalone代表使用单机模式启动nacos服务端，启动成功后可以直接登录http://localhost:8848/nacos/#/login，进入nacos的控制台，账密默认都是nacos

### 服务注册到nacos

1. 父工程添加依赖：

```xml
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-alibaba-dependencies</artifactId>
    <version>2.2.5.RELEASE</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```

2. 提供者和消费者添加依赖：

```xml
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
</dependency>
```

3. 提供者和消费者去掉eureka的依赖

4. 提供者和消费者新增nacos配置：

   ```yaml
   spring:
     cloud:
       nacos:
         server-addr: localhost:8848
   ```

5. 启动提供者和消费者后，可以发现nacos控制台里出现了两个服务：

​	![image](https://user-images.githubusercontent.com/48977889/166612649-57cbe3ef-58e5-4600-a3a1-17ff5a8120a5.png)

6. 调用和Eureka一样，也是通过服务名替代主机+端口号，值得注意的是：nacos会区分服务名的大小写。

## 7-Nacos作为注册中心：集群

Nacos里有一个概念叫集群，它设计上是在服务之下，实例之上的。目的是为了同一集群下多个服务的互相调用会更加快速，当同集群内的服务调用失败才考虑调用其他集群的服务，架构图如下：

![截图_选择区域_20220504095928](https://user-images.githubusercontent.com/48977889/166614215-126e9661-161f-48e5-a8dd-aabaeb9490f9.png)

可以配置实例所属的集群：

```yaml
spring:
  cloud:
    nacos:
      server-addr: localhost:8848
      discovery:
        cluster-name: J1 #代表这个实例属于J1集群
```

可以上面说到，集群本身是Nacos自身的概念，但负载均衡却是SpringCloud写好的规则。在SpringCloud眼里根本没有所谓的Nacos集群，因此就算配了集群，请求提供者的时候仍然走的是默认的负载均衡策略。因此需要在消费者中配置以下策略：

```yaml
# 服务名
userservice:
	# ribbon配置
  ribbon:
  	# 通过全雷鸣的方式，指定负载均衡策略
    NFLoadBalancerRuleClassName: com.alibaba.cloud.nacos.ribbon.NacosRule  # 负载均衡规则
```

注意是以服务名为一级配置的方式配置，当ribbon请求Nacos注册好的提供者时，会采用NacosRule这一负载均衡策略，这个策略的默认规则是：**优先请求同集群中的提供者 ，在同一集群内采用随机请求的方式**。

## 8-Nacos作为注册中心：namespace

Nacos也有namespace的概念，和其他的一样，它的namespace也是用来隔离用的，不同namespace之间的服务实例无法相互访问。在Nacos服务器中需要先创建好命名空间，指定命名空间ID（如果不指定，则默认会生成一个UUID作为空间ID）。然后在服务实例的配置文件中添加以下配置：

```yaml
  cloud:
    nacos:
      server-addr: localhost:8848 # nacos服务地址
      discovery:
        cluster-name: J2
        namespace: 4d6ce343-9e1b-44df-a90f-2cf2b6b3d177 #这里是namesapce的id
```

这样，当服务实例启动时，就会指定它的namespace了。

## 9-Nacos与Eureka

在知识点5中，说明了应该有一个机制使得注册中心主动通知服务实例，告诉它们提供者的列表发生了变更，这一点在Eureka是没有的，但在Nacos却实现了。当服务提供者的数量、状态发生变更时，Nacos会主动推送消息给消费者，告诉提供者的信息变更（**我猜应该是用了Netty的双向通信机制**）。而Eureka只会让消费者在下一次同步信息时获取最新的提供者信息。

Nacos还有一个概念叫“临时实例”，默认注册进Nacos的实例都是临时实例。临时实例采用心跳机制与Nacos服务器进行存活通信，当心跳停止后，Nacos会主动剔除掉失活的服务实例，这一点和Eureka是一样的。但Nacos的非临时实例不一样，它是采用**Nacos服务器主动询问服务实例是否存活**的机制，当服务实例失活后，Nacos只会标记服务实例不可用，使消费者无法请求到，非临时实例一旦重新启动，Nacos又会自动将其标为可用状态。

可以通过以下配置来确定实例的临时性：

```yaml
  cloud:
    nacos:
      server-addr: localhost:8848 # nacos服务地址
      discovery:
        cluster-name: J2
          namespace: 4d6ce343-9e1b-44df-a90f-2cf2b6b3d177
          ephemeral: false # 是否是临时实例，默认为true
```

# 负载均衡

## 4-Ribbon负载均衡流程

知识点3讲到，消费者从eureka获取到所有提供者的主机信息，然后进行负载均衡请求，那么是谁做负载均衡？又是什么时候做的呢？回到@LoadBalanced注解，当RestTemplate被加入@LoadBalanced注解后，调用请求时会被LoadBalanceInterceptor拦截处理请求，**然后被loadBalancer进行处理，在Eureka中默认采用RibbonLoadBalancerClient处理**。最终通过IRule根据特定规则返回本次请求的目的地，默认采用ZoneAvoidanceRule这个规则，当然，也可以采用配置的方式更改或手动实现负载均衡规则。

![image-20220503162547202](https://user-images.githubusercontent.com/48977889/166444922-e550751d-7006-42d9-becd-e0f3fa463726.png)

## 5-Ribbon的懒加载

知识点4讲到，当请求提供者时，Ribbon会先从注册中心获取提供者列表，再做负载均衡。实际上，Ribbon默认情况下只有在第一次请求时才会去获取提供者列表，有点类似于MVC容器的初始化也是在第一次请求时才触发的，这也被称为“第一次惩罚”。第一次请求后Ribbon会将提供者列表缓存到JVM内存里，供下次使用。当然，也可以通过配置的方式使Ribbon开启饥饿加载模式，即Java进程启动时就请求Eureka，而不是等到第一次请求发生时。

```yaml
ribbon:
  eager-load:
    enabled: true # 开启饥饿加载
    clients: # 指定饥饿加载的服务名称
      - userservice
```

当然，如果Eureka的服务列表发生了变更，双方应该要有一个通信机制，让Eureka注册者知道应该重新去获取一遍服务列表，而不是一直依赖本地缓存。
