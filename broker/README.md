# Broker实现

## 介绍
1. broker负责消息存储和转发。
2. 多个broker可以组成主从，主从的brokerName属性相等，brokerId等于0的为master、大于0的为slave。
3. 只有master才能进行写入操作，slave不允许。
4. 客户端消费可以从master和slave消费。在默认情况下，消费者都从master消费，在master挂后，客户端由于从Namesrv中感知到broker挂机，就会从slave消费。
5. broker向所有的Namesrv结点建立长连接，注册自己的topic信息。

## 实现
这里先列几个broker的功能点，按照功能点逐个分析：
1. 如何创建、保存、使用topic
2. 如何实现消息存储
3. 如何实现消息查询
4. 如何实现消息的可靠性
5. 如何实现顺序消息
6. 如何实现消息重试
7. 如何实现消息分发
8. 如何实现死信队列
9. 如何实现事务消息
10. 如何实现消息标签
11. 如何实现消息过滤
12. 如何实现回溯消费
13. 如何实现定时消息
14. 如何实现流量控制
15. 如何实现主从部署
16. 如何实现负载均衡

### 如何创建、保存、使用topic
每个broker都维护自己的topic信息并向namesrv上报topic信息，所以topic是存储在broker的。topic需要创建后才能使用，使用`mqadmin`命令就可以创建topic，同时rocketmq还提供了自动创建topic的机制。关于使用`mqadmin`命令创建topic的分析在笔记[mqadmin命令的实现](../tools/README.md)，自动创建topic的过程是broker处理客户端发送消息时实现的，所以自动创建topic的实现可以看broker如何处理客户端发送的消息。

关于topic的存储，每个broker可以存储多个topic的消息，每个topic的消息也可以分片存储于不同的broker。Message Queue用于存储消息的物理地址，每个topic中的消息地址存储于多个Message Queue中。
