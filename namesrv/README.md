# Namesrv实现

## 介绍
Namesrv是一个几乎无状态的节点，作为RocketMQ的注册中心，是专门为RocketMQ设计的轻量级命名服务，可集群横向扩展，节点之间无任何信息同步。

Name Server的主要功能：
1. 每个Broker启动的时候会向Namesrv发送注册请求，Namesrv接收Broker的请求注册路由信息，NameServer保存活跃的Broker列表，包括Master和Slave
2. 保存所有topic和该topic所有队列的列表
3. 保存所有Broker的Filter列表
4. 接收client（Producer和Consumer）的请求根据某个topic获取所有到Broker的路由信息