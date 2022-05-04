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

