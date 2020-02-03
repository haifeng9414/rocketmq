## topic的创建
每个broker都维护自己的topic信息并向namesrv上报topic信息，所以topic是存储在broker的。topic需要创建后才能使用，使用`mqadmin`命令就可以创建topic，同时rocketmq还提供了自动创建topic的机制。关于使用`mqadmin`命令创建topic的分析在笔记[mqadmin命令的实现](../../tools/mqadmin命令的实现.md)，自动创建topic的过程是broker处理客户端发送消息时实现的，所以自动创建topic的实现可以看笔记[如何实现消息发送](../client/如何实现消息发送.md)。

## topic的存储
每个broker可以存储多个topic的消息，每个topic的消息也可以分片存储于不同的broker。Message Queue用于存储消息的物理地址，每个topic中的消息地址存储于多个Message Queue中。由笔记[mqadmin命令的实现](../tools/mqadmin命令的实现.md)中关于创建topic的分析可以，broker收到创建topic请求后执行的方法是`AdminBrokerProcessor`类的`updateAndCreateTopic()`方法，该方法代码如下：
```java
private synchronized RemotingCommand updateAndCreateTopic(ChannelHandlerContext ctx,
    RemotingCommand request) throws RemotingCommandException {
    final RemotingCommand response = RemotingCommand.createResponseCommand(null);
    final CreateTopicRequestHeader requestHeader =
        (CreateTopicRequestHeader) request.decodeCommandCustomHeader(CreateTopicRequestHeader.class);
    log.info("updateAndCreateTopic called by {}", RemotingHelper.parseChannelRemoteAddr(ctx.channel()));

    // topic不能等于clusterName
    if (requestHeader.getTopic().equals(this.brokerController.getBrokerConfig().getBrokerClusterName())) {
        String errorMsg = "the topic[" + requestHeader.getTopic() + "] is conflict with system reserved words.";
        log.warn(errorMsg);
        response.setCode(ResponseCode.SYSTEM_ERROR);
        response.setRemark(errorMsg);
        return response;
    }

    try {
        // 直接返回成功响应
        response.setCode(ResponseCode.SUCCESS);
        response.setOpaque(request.getOpaque());
        response.markResponseType();
        response.setRemark(null);
        ctx.writeAndFlush(response);
    } catch (Exception e) {
        log.error("Failed to produce a proper response", e);
    }

    // 从请求中解析topic配置
    TopicConfig topicConfig = new TopicConfig(requestHeader.getTopic());
    topicConfig.setReadQueueNums(requestHeader.getReadQueueNums());
    topicConfig.setWriteQueueNums(requestHeader.getWriteQueueNums());
    topicConfig.setTopicFilterType(requestHeader.getTopicFilterTypeEnum());
    topicConfig.setPerm(requestHeader.getPerm());
    topicConfig.setTopicSysFlag(requestHeader.getTopicSysFlag() == null ? 0 : requestHeader.getTopicSysFlag());

    // 保存topic配置到private final ConcurrentMap<String, TopicConfig> topicConfigTable = new ConcurrentHashMap<String, TopicConfig>(1024);
    // 同时更新dataVersion并持久化topicConfigTable也就是当前broker所有的topic的配置和dataVersion到topic.json文件
    this.brokerController.getTopicConfigManager().updateTopicConfig(topicConfig);

    // 向namesrv发送当前topic的配置
    this.brokerController.registerIncrementBrokerData(topicConfig, this.brokerController.getTopicConfigManager().getDataVersion());

    return null;
}
```

关于topic的保存，在`this.brokerController.getTopicConfigManager().updateTopicConfig(topicConfig);`这一行，该方法代码如下：
```java
public void updateTopicConfig(final TopicConfig topicConfig) {
    TopicConfig old = this.topicConfigTable.put(topicConfig.getTopicName(), topicConfig);
    if (old != null) {
        log.info("update topic config, old:[{}] new:[{}]", old, topicConfig);
    } else {
        log.info("create new topic [{}]", topicConfig);
    }

    this.dataVersion.nextVersion();

    // 持久化topicConfigTable及dataVersion属性到topic.json
    this.persist();
}

public synchronized void persist() {
    String jsonString = this.encode(true);
    if (jsonString != null) {
        String fileName = this.configFilePath();
        try {
            MixAll.string2File(jsonString, fileName);
        } catch (IOException e) {
            log.error("persist file " + fileName + " exception", e);
        }
    }
}
```
对于`TopicConfigManager`类，上面的`configFilePath()`默认方法返回`{用户目录}/store/config/topic.json`，`updateTopicConfig()`方法每次更新broker的topic时，都将broker的所有topic配置持久化到本地，当broker启动时会加载该文件，这就实现了存储topic的功能。实际上rocketmq集群在运行的时候是无所谓topic的持久化的，真正需要持久化的是topic下的队列，这一块可以看笔记[如何实现消息存储](../client/如何实现消息存储.md)

## topic的使用
生产者发送消息时需要为消息指定topic，消费者消费消息时也需要设置从哪个topic获取消息，topic的使用主要是针对消息的发送和消费，关于topic的使用可以看笔记[如何实现消息发送](../client/如何实现消息发送.md)