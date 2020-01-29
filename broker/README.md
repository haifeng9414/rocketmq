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
2. 如何实现消息重试
3. 如何实现死信队列
4. 如何实现消息存储
5. 如何实现消息分发
6. 如何实现事务消息

### 如何创建、保存、使用topic
每个broker都维护自己的topic信息并向namesrv上报topic信息，所以topic是存储在broker的。topic需要创建后才能使用，使用`mqadmin`命令就可以创建topic，同时rocketmq还提供了自动创建topic的机制。关于使用`mqadmin`命令创建topic的分析在笔记[mqadmin命令的实现](../tools/README.md)
