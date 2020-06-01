# Broker实现

## 介绍
1. broker负责消息存储和转发。
2. 多个broker可以组成主从，主从的brokerName属性相等，brokerId等于0的为master、大于0的为slave。
3. 只有master才能进行写入操作，slave不允许。
4. 客户端消费可以从master和slave消费。在默认情况下，消费者都从master消费，在master挂后，客户端由于从Namesrv中感知到broker挂机，就会从slave消费。
5. broker向所有的Namesrv结点建立长连接，注册自己的topic信息。

## 实现
这里先列几个broker的功能点，按照功能点逐个分析：
1. [如何创建、保存、使用topic](../my_doc/broker/如何创建、保存、使用topic.md)
2. [如何实现消息发送](../my_doc/client/如何实现消息发送.md)
3. [如何实现负载均衡](../my_doc/client/如何实现负载均衡.md)
4. [如何处理发送消息的请求](../my_doc/broker/如何处理发送消息的请求.md)
5. [如何实现消息存储](../my_doc/broker/如何实现消息存储.md)
6. [如何实现消息消费](../my_doc/client/如何实现消息消费.md)
6. [如何实现消息查询](../my_doc/client/如何实现消息查询.md)
7. [如何保存消息消费进度](../my_doc/client/如何保存消息消费进度.md)
8. [如何实现流量控制](../my_doc/client/如何实现流量控制.md)
9. [如何实现消息过滤](../my_doc/client/如何实现消息过滤.md)
10. [如何实现顺序消息](../my_doc/client/如何实现顺序消息.md)
11. [如何实现延时消息](../my_doc/client/如何实现延时消息.md)
12. [如何实现消息重试](../my_doc/client/如何实现消息重试.md)
13. [如何实现死信队列](../my_doc/client/如何实现死信队列.md)
14. [如何实现事务消息](../my_doc/client/如何实现事务消息.md)
15. 如何实现回溯消费
16. 如何实现消息的可靠性
17. 如何实现主从部署
18. 如何实现消息轨迹
19. 如何实现Dledger部署模式

