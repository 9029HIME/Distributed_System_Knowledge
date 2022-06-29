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