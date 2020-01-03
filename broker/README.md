# Broker实现

## 介绍
1. broker负责消息存储和转发。
2. 多个broker可以组成主从，主从的brokerName属性相等，brokerId等于0的为master、大于0的为slave。
3. 只有master才能进行写入操作，slave不允许。
4. 客户端消费可以从master和slave消费。在默认情况下，消费者都从master消费，在master挂后，客户端由于从Namesrv中感知到broker挂机，就会从slave消费。
5. broker向所有的Namesrv结点建立长连接，注册自己的topic信息。

## 实现
这里先列几个broker的功能点，按照功能点逐个分析：
1. 如何创建和保存topic及topic下的队列
2. 如何实现消息存储
3. 如何实现消息分发
4. 如何实现事务消息