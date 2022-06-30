# 75-MyCat2是什么

直接下一个定义：MyCat是基于Java语言开发的数据库中间件，所谓中间件承载着“中间”的工作，左边是需要访问数据库的客户端，右边是需要被访问的数据库。那么MyCat在中间有什么作用呢？主要是向客户端提供分库分表数据查询、读写分离。客户端通过操作MyCat可以实现直接操作MySQL无法完成的效果。

![img](https://user-images.githubusercontent.com/48977889/176353398-742eab63-86cd-494e-aad5-fb0303f38b5e.png)

![img](https://user-images.githubusercontent.com/48977889/176352998-66dfc60c-af79-43a6-bd04-6c254bc20515.png)

MyCat的核心原理是“拦截”，它拦截了客户端的SQL语句，对这些SQL语句进行分析，发往实际的数据库，再从数据库拿到真实数据，并将真实数据做进一步处理，返回给客户端。对于客户端来说仿佛没有分库分表的概念：

![img](https://user-images.githubusercontent.com/48977889/176354359-5a5746c7-b9c0-4057-829a-ec611fc444e1.png)

MyCat是基于Cobar进行二次开发的产品，而MyCat2则是MyCat的升级版，提供了许多MyCat原先不支持的功能。除了MyCat外，还有许多类似的数据库中间件，比如OneProxy、KingShard、Vitess、Atlas等等。

# 76-MyCat2的安装

1. 先准备好安装文件：

   ```bash
   kjg@kjg-PC:~/Downloads$ ls -l | grep mycat
   -rw-r--r-- 1 kjg kjg 148999807 6月  29 13:08 mycat2-1.21-release-jar-with-dependencies-2022-4-15.jar
   -rw-r--r-- 1 kjg kjg   1246974 6月  29 13:06 mycat2-install-template-1.21.zip
   kjg@kjg-PC:~/Downloads$ 
   ```

2. 解压zip到/usr/local/mycat2文件下内

3. 将mycat2-1.21-release-jar-with-dependencies-2022-4-15.jar添加到myca2/lib内：

   ```bash
   kjg@kjg-PC:/usr/local/mycat2/mycat$ sudo cp ~/Downloads/mycat2-1.21-release-jar-with-dependencies-2022-4-15.jar lib/
   ```

4. 修改mycat的原型数据库配置（需要连哪个物理库）

   ```
   vim conf/datasources/prototypeDs.datasource.json
   
   {
           "dbType":"mysql",
           "idleTimeout":60000,
           "initSqls":[],
           "initSqlsGetConnection":true,
           "instanceType":"READ_WRITE",
           "maxCon":1000,
           "maxConnectTimeout":3000,
           "maxRetryCount":5,
           "minCon":1,
           "name":"prototypeDs",
           "password":"123456",
           "type":"JDBC",
           "url":"jdbc:mysql://localhost:3306/mycat_demo?useUnicode=true&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8",
           "user":"root",
           "weight":0
   }
   ```

5. 启动mycat2：

   ```bash
   kjg@kjg-PC:/usr/local/mycat2/mycat$ ./bin/mycat start
   Starting mycat2...
   kjg@kjg-PC:/usr/local/mycat2/mycat$ ./bin/mycat status
   mycat2 is running (14132).
   ```

6. 启动成功后，可以向访问数据库一样访问MyCat，只不过要访问8066端口：

   ```bash
   kjg@kjg-PC:/usr/local/mycat2/mycat$ mysql -uroot -p123456 -P 8066
   mysql: [Warning] Using a password on the command line interface can be insecure.
   Welcome to the MySQL monitor.  Commands end with ; or \g.
   Your MySQL connection id is 18
   Server version: 8.0.29 MySQL Community Server - GPL
   
   Copyright (c) 2000, 2022, Oracle and/or its affiliates.
   
   Oracle is a registered trademark of Oracle Corporation and/or its
   affiliates. Other names may be trademarks of their respective
   owners.
   
   Type 'help;' or '\h' for help. Type '\c' to clear the current input statement.
   
   mysql> 
   mysql> show databases;
   +--------------------+
   | Database           |
   +--------------------+
   | es_demo            |
   | information_schema |
   | mycat              |
   | mycat_demo         |
   | mysql              |
   | order_service      |
   | performance_schema |
   | seata              |
   | seata_demo         |
   | sys                |
   | user_service       |
   +--------------------+
   11 rows in set (0.01 sec)
   ```

   **还是那句话：对于客户端来说仿佛没有分库分表的概念，操作MyCat就像操作MySQL一样。**

# 77-MyCat一些难以理解的概念

全局表：可以理解为枚举表或字典表，比如资金方表，一般不会改动，并且数量是固定的。但是为了提高查询效率，即使值都一样，还是会在每个物理分库都**冗余一份**。获取到物理子表的数据后，直接在物理子表所在的数据库进行全局表查询。

分表键：根据分表键进行分表，是分表的关键参考。比如ID、比如日期。

ER表：被分表的子表，打个比方授信申请表和额度表应该是1：1的关系。当授信申请表被分库分表时，为了提高查询效率，会将额度表也进行分库分表，并且对应的策略和授信申请表一样。比如将授信申请ID作为分库键，1-1000的授信申请放到库A里，1001-2000的授信申请放到库B里，那么1-1000这个授信申请对应的额度表也要放在库A里，1001-2000这个授信申请对应的额度表也要放在库A里。**额度表就是授信申请表的ER表**。

# MyCat的配置文件

## 78-用户配置

所在路径：conf/users/${数据库用户名}.user.json

配置信息：

```json
{
        "dialect":"mysql",	#数据库类型 
        "ip":null,	#客户端ip，如果配置了会限制客户端请求过来的ip，只有已配置的ip才能请求mycat
        "password":"123456",	#数据库密码
        "transactionType":"proxy",	#mycat采用的事务类型
        "username":"root",	#数据库用户名
    	"isolation":3	#事务隔离级别
}
```

其中的事务隔离级别对应：

1=读未提交

2=读已提交

3=可重复读

4=串行化

transactionType有两种：proxy和xa。proxy是mycat的事务类型，目前还不太了解（和之前学的分布式事务类型不一样），默认情况下使用XA。

## 79-原型库配置

 所在路径：conf/datasource/${数据源名字}.datasources.json

安装的时候了解过了，这里不赘述

## 80-集群配置

所在路径：conf/clusters/${集群名称}.cluster.json  

## 81-库配置

所在路径：conf/schemas/${库名}.schema.json

# 82-搭建MySQL的主从复制

MyCat的读写分离是基于MySQL的主从复制进行的，因此先搭起MySQL1主2从的架构。主是192.168.120.161，两个从是192.168.120.121和192.168.120.122。

