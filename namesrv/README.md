# Namesrv实现

## 介绍
Namesrv是一个几乎无状态的节点，作为RocketMQ的注册中心，是专门为RocketMQ设计的轻量级命名服务，可集群横向扩展，节点之间无任何信息同步。

Namesrv的主要功能：
1. 保存RocketMQ集群的各种信息，包括：
    ```
    // 保存topic下的队列信息
    private final HashMap<String/* topic */, List<QueueData>> topicQueueTable;
    // 保存broker信息，这里的key是brokerName而不是brokerAddr，一个brokerName可能对应有多个节点，包括master和slave，
    // 这些节点的brokerName是相等的，但是IP不同，所以brokerName对应的是一个broker的所有节点，而BrokerData就保存了这些
    // 节点的信息
    private final HashMap<String/* brokerName */, BrokerData> brokerAddrTable;
    // 保存cluster下的brokerName
    private final HashMap<String/* clusterName */, Set<String/* brokerName */>> clusterAddrTable;
    // 保存brokerAddr和broker信息，一个broker下可能有master和slave，所以一个broker下可能有多个brokerAddr，每个brokerAddr
    // 对应一个节点，BrokerLiveInfo对象保存了某个节点的信息
    private final HashMap<String/* brokerAddr */, BrokerLiveInfo> brokerLiveTable;
    // 保存broker下的filter server
    private final HashMap<String/* brokerAddr */, List<String>/* Filter Server */> filterServerTable;
    ```
2. 每个Broker启动的时候会向Namesrv发送注册请求，Namesrv接收Broker的请求注册路由信息，Namesrv保存活跃的Broker列表，包括Master和Slave。
3. Broker每隔30秒会发送心跳给Namesrv，心跳中会带有Topic信息，Namesrv会根据心跳更新Broker的信息。
4. 每个Broker节点和所有Namesrv保持长连接，Namesrv每隔10秒检查所有心跳超时（2分钟）的Broker节点，关闭对应的Channel并删除Broker节点相关的信息。
5. 接收Client（Producer、Consumer等）的请求，返回对应的信息，如根据某个Topic返回所有负责该Topic的Broker的信息。

Namesrv的实现很简单，用Netty接收请求并根据请求返回信息，对于保存的各种信息，其实就是对各种Map的处理，源码这里就不具体分析了。

Namesrv的实现使得Namesrv稳定性非常高，无状态互不影响，非常易于横向扩展，同时也只涉及到很少的磁盘读写（对kvConfig.json文件有更新时会执行该文件的持久化），所以性能也非常高。