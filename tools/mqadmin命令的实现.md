`mqadmin`命令用于配置rocketmq集群，命令选项如下：
```
~/Desktop/rocketmq-all-4.6.0-bin-release/bin » ./mqadmin                                                                                                               dhf@donghaifengdeMacBook-Pro
The most commonly used mqadmin commands are:
   updateTopic          Update or create topic
   deleteTopic          Delete topic from broker and NameServer.
   updateSubGroup       Update or create subscription group
   deleteSubGroup       Delete subscription group from broker.
   updateBrokerConfig   Update broker's config
   updateTopicPerm      Update topic perm
   topicRoute           Examine topic route info
   topicStatus          Examine topic Status info
   topicClusterList     get cluster info for topic
   brokerStatus         Fetch broker runtime status data
   queryMsgById         Query Message by Id
   queryMsgByKey        Query Message by Key
   queryMsgByUniqueKey  Query Message by Unique key
   queryMsgByOffset     Query Message by offset
   printMsg             Print Message Detail
   printMsgByQueue      Print Message Detail
   sendMsgStatus        send msg to broker.
   brokerConsumeStats   Fetch broker consume stats data
   producerConnection   Query producer's socket connection and client version
   consumerConnection   Query consumer's socket connection, client version and subscription
   consumerProgress     Query consumers's progress, speed
   consumerStatus       Query consumer's internal data structure
   cloneGroupOffset     clone offset from other group.
   clusterList          List all of clusters
   topicList            Fetch all topic list from name server
   updateKvConfig       Create or update KV config.
   deleteKvConfig       Delete KV config.
   wipeWritePerm        Wipe write perm of broker in all name server
   resetOffsetByTime    Reset consumer offset by timestamp(without client restart).
   updateOrderConf      Create or update or delete order conf
   cleanExpiredCQ       Clean expired ConsumeQueue on broker.
   cleanUnusedTopic     Clean unused topic on broker.
   startMonitoring      Start Monitoring
   statsAll             Topic and Consumer tps stats
   allocateMQ           Allocate MQ
   checkMsgSendRT       check message send response time
   clusterRT            List All clusters Message Send RT
   getNamesrvConfig     Get configs of name server.
   updateNamesrvConfig  Update configs of name server.
   getBrokerConfig      Get broker config by cluster or special broker!
   queryCq              Query cq command.
   sendMessage          Send a message
   consumeMessage       Consume message
   updateAclConfig      Update acl config yaml file in broker
   deleteAccessConfig   Delete Acl Config Account in broker
   clusterAclConfigVersion List all of acl config version information in cluster
   updateGlobalWhiteAddr Update global white address for acl Config File in broker
   getAccessConfigSubCommand List all of acl config information in cluster

See 'mqadmin help <command>' for more information on a specific command.
```

`mqadmin`命令实际上是一个脚本，脚本内容如下：
```
#!/bin/sh
  
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

if [ -z "$ROCKETMQ_HOME" ] ; then
  ## resolve links - $0 may be a link to maven's home
  PRG="$0"

  # need this for relative symlinks
  while [ -h "$PRG" ] ; do
    ls=`ls -ld "$PRG"`
    link=`expr "$ls" : '.*-> \(.*\)$'`
    if expr "$link" : '/.*' > /dev/null; then
      PRG="$link"
    else
      PRG="`dirname "$PRG"`/$link"
    fi
  done

  saveddir=`pwd`

  ROCKETMQ_HOME=`dirname "$PRG"`/..

  # make it fully qualified
  ROCKETMQ_HOME=`cd "$ROCKETMQ_HOME" && pwd`

  cd "$saveddir"
fi

export ROCKETMQ_HOME

sh ${ROCKETMQ_HOME}/bin/tools.sh org.apache.rocketmq.tools.command.MQAdminStartup $@
```

可以看到，`mqadmin`命令实际上调用的是`tools.sh`脚本，该脚本内容：
```
#!/bin/sh
  
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

#===========================================================================================
# Java Environment Setting
#===========================================================================================
error_exit ()
{
    echo "ERROR: $1 !!"
    exit 1
}

[ ! -e "$JAVA_HOME/bin/java" ] && JAVA_HOME=$HOME/jdk/java
[ ! -e "$JAVA_HOME/bin/java" ] && JAVA_HOME=/usr/java
[ ! -e "$JAVA_HOME/bin/java" ] && error_exit "Please set the JAVA_HOME variable in your environment, We need java(x64)!"

export JAVA_HOME
export JAVA="$JAVA_HOME/bin/java"
export BASE_DIR=$(dirname $0)/..
export CLASSPATH=.:${BASE_DIR}/conf:${CLASSPATH}

#===========================================================================================
# JVM Configuration
#===========================================================================================
JAVA_OPT="${JAVA_OPT} -server -Xms1g -Xmx1g -Xmn256m -XX:MetaspaceSize=128m -XX:MaxMetaspaceSize=128m"
JAVA_OPT="${JAVA_OPT} -Djava.ext.dirs=${BASE_DIR}/lib:${JAVA_HOME}/jre/lib/ext:${JAVA_HOME}/lib/ext"
JAVA_OPT="${JAVA_OPT} -cp ${CLASSPATH}"

$JAVA ${JAVA_OPT} $@
```

`tools.sh`只是简单的根据参数执行java命令，执行的java类是`MQAdminStartup`，该类初始化了所有`mqadmin`帮助信息中能看到的命令行选项，`mqadmin`命令的每个子选项都对应一个`SubCommand`接口的实现类，`SubCommand`接口定义如下：
```java
public interface SubCommand {
    String commandName();

    String commandDesc();

    Options buildCommandlineOptions(final Options options);

    void execute(final CommandLine commandLine, final Options options, RPCHook rpcHook) throws SubCommandException;
}
```

执行`mqadmin`命令的某个子选项时，实际上是调用的子选项对应的实现类的`execute()`方法，下面分析创建topic时用到的`mqadmin updateTopic`选项的执行过程，执行`mqadmin updateTopic`命令时输出如下：
```
~/Desktop/rocketmq-all-4.6.0-bin-release/bin » ./mqadmin updateTopic                                                                                                   dhf@donghaifengdeMacBook-Pro
usage: mqadmin updateTopic -b <arg> | -c <arg>  [-h] [-n <arg>] [-o <arg>] [-p <arg>] [-r <arg>] [-s <arg>] -t
       <arg> [-u <arg>] [-w <arg>]
 -b,--brokerAddr <arg>       create topic to which broker
 -c,--clusterName <arg>      create topic to which cluster
 -h,--help                   Print help
 -n,--namesrvAddr <arg>      Name server address list, eg: 192.168.0.1:9876;192.168.0.2:9876
 -o,--order <arg>            set topic's order(true|false)
 -p,--perm <arg>             set topic's permission(2|4|6), intro[2:W 4:R; 6:RW]
 -r,--readQueueNums <arg>    set read queue nums
 -s,--hasUnitSub <arg>       has unit sub (true|false)
 -t,--topic <arg>            topic name
 -u,--unit <arg>             is unit topic (true|false)
 -w,--writeQueueNums <arg>   set write queue nums
```

对应的实现类是`UpdateTopicSubCommand`，最关键的`execute()`方法代码如下：
```java
@Override
public void execute(final CommandLine commandLine, final Options options,
    RPCHook rpcHook) throws SubCommandException {
    // DefaultMQAdminExt类是MQAdminExt接口的实现类，MQAdminExt接口定义了所有mqadmin命令能够执行的所有方法
    DefaultMQAdminExt defaultMQAdminExt = new DefaultMQAdminExt(rpcHook);
    // 保存执行时间
    defaultMQAdminExt.setInstanceName(Long.toString(System.currentTimeMillis()));

    try {
        // 创建topic的配置
        TopicConfig topicConfig = new TopicConfig();
        // 默认读写队列数量都为8
        topicConfig.setReadQueueNums(8);
        topicConfig.setWriteQueueNums(8);
        // 从命令行获取topic名称
        topicConfig.setTopicName(commandLine.getOptionValue('t').trim());

        // readQueueNums
        if (commandLine.hasOption('r')) {
            // 从命令行获取读队列名称
            topicConfig.setReadQueueNums(Integer.parseInt(commandLine.getOptionValue('r').trim()));
        }

        // writeQueueNums
        if (commandLine.hasOption('w')) {
            // 从命令行获取写队列名称
            topicConfig.setWriteQueueNums(Integer.parseInt(commandLine.getOptionValue('w').trim()));
        }

        // perm
        if (commandLine.hasOption('p')) {
            // 从命令行获取队列权限
            topicConfig.setPerm(Integer.parseInt(commandLine.getOptionValue('p').trim()));
        }

        // 从命令行获取其他topic其他配置
        boolean isUnit = false;
        if (commandLine.hasOption('u')) {
            isUnit = Boolean.parseBoolean(commandLine.getOptionValue('u').trim());
        }

        boolean isCenterSync = false;
        if (commandLine.hasOption('s')) {
            isCenterSync = Boolean.parseBoolean(commandLine.getOptionValue('s').trim());
        }

        int topicCenterSync = TopicSysFlag.buildSysFlag(isUnit, isCenterSync);
        topicConfig.setTopicSysFlag(topicCenterSync);

        boolean isOrder = false;
        if (commandLine.hasOption('o')) {
            isOrder = Boolean.parseBoolean(commandLine.getOptionValue('o').trim());
        }
        topicConfig.setOrder(isOrder);

        if (commandLine.hasOption('b')) {
            // 获取broker地址
            String addr = commandLine.getOptionValue('b').trim();

            defaultMQAdminExt.start();
            // 在broker中创建topic
            defaultMQAdminExt.createAndUpdateTopicConfig(addr, topicConfig);

            if (isOrder) {
                String brokerName = CommandUtil.fetchBrokerNameByAddr(defaultMQAdminExt, addr);
                String orderConf = brokerName + ":" + topicConfig.getWriteQueueNums();
                defaultMQAdminExt.createOrUpdateOrderConf(topicConfig.getTopicName(), orderConf, false);
                System.out.printf("%s", String.format("set broker orderConf. isOrder=%s, orderConf=[%s]",
                    isOrder, orderConf.toString()));
            }
            System.out.printf("create topic to %s success.%n", addr);
            System.out.printf("%s", topicConfig);
            return;

        } else if (commandLine.hasOption('c')) {
            // 获取集群名称，以集群的方式创建topic
            String clusterName = commandLine.getOptionValue('c').trim();

            defaultMQAdminExt.start();

            // 获取集群下所有的broker
            Set<String> masterSet =
                CommandUtil.fetchMasterAddrByClusterName(defaultMQAdminExt, clusterName);
            // 遍历broker，依次创建topic
            for (String addr : masterSet) {
                defaultMQAdminExt.createAndUpdateTopicConfig(addr, topicConfig);
                System.out.printf("create topic to %s success.%n", addr);
            }

            if (isOrder) {
                Set<String> brokerNameSet =
                    CommandUtil.fetchBrokerNameByClusterName(defaultMQAdminExt, clusterName);
                StringBuilder orderConf = new StringBuilder();
                String splitor = "";
                for (String s : brokerNameSet) {
                    orderConf.append(splitor).append(s).append(":")
                        .append(topicConfig.getWriteQueueNums());
                    splitor = ";";
                }
                defaultMQAdminExt.createOrUpdateOrderConf(topicConfig.getTopicName(),
                    orderConf.toString(), true);
                System.out.printf("set cluster orderConf. isOrder=%s, orderConf=[%s]", isOrder, orderConf);
            }

            System.out.printf("%s", topicConfig);
            return;
        }

        ServerUtil.printCommandLineHelp("mqadmin " + this.commandName(), options);
    } catch (Exception e) {
        throw new SubCommandException(this.getClass().getSimpleName() + " command failed", e);
    } finally {
        defaultMQAdminExt.shutdown();
    }
}
```

简单来说，`execute()`方法的实现就是创建`DefaultMQAdminExt`对象，并根据命令行参数创建`TopicConfig`对象用于表示需要更新或创建的topic的配置，之后调用`DefaultMQAdminExt`对象的`start()`方法和`createAndUpdateTopicConfig()`方法，所以`mqadmin updateTopic`命令的实现主要是在`DefaultMQAdminExt`类，下面分析该类的实现。

`DefaultMQAdminExt`类继承自`ClientConfig`类，实现了`MQAdminExt`接口，`ClientConfig`类定义了在rocketmq集群中客户端的通用配置：
```java
public static final String SEND_MESSAGE_WITH_VIP_CHANNEL_PROPERTY = "com.rocketmq.sendMessageWithVIPChannel";
private String namesrvAddr = NameServerAddressUtils.getNameServerAddresses();
private String clientIP = RemotingUtil.getLocalAddress();
private String instanceName = System.getProperty("rocketmq.client.name", "DEFAULT");
private int clientCallbackExecutorThreads = Runtime.getRuntime().availableProcessors();
protected String namespace;
protected AccessChannel accessChannel = AccessChannel.LOCAL;

/**
 * Pulling topic information interval from the named server
 */
private int pollNameServerInterval = 1000 * 30;
/**
 * Heartbeat interval in microseconds with message broker
 */
private int heartbeatBrokerInterval = 1000 * 30;
/**
 * Offset persistent interval for consumer
 */
private int persistConsumerOffsetInterval = 1000 * 5;
private long pullTimeDelayMillsWhenException = 1000;
private boolean unitMode = false;
private String unitName;
private boolean vipChannelEnabled = Boolean.parseBoolean(System.getProperty(SEND_MESSAGE_WITH_VIP_CHANNEL_PROPERTY, "false"));

private boolean useTLS = TlsSystemConfig.tlsEnable;

private LanguageCode language = LanguageCode.JAVA;
```

这些配置就不赘述了，`MQAdminExt`接口定义了rocketmq集群中一个管理员能够执行的操作，`MQAdminExt`接口的定义如下：
```java
public interface MQAdminExt extends MQAdmin {
    void start() throws MQClientException;

    void shutdown();

    void updateBrokerConfig(final String brokerAddr, final Properties properties) throws RemotingConnectException,
        RemotingSendRequestException, RemotingTimeoutException, UnsupportedEncodingException, InterruptedException, MQBrokerException;

    Properties getBrokerConfig(final String brokerAddr) throws RemotingConnectException,
        RemotingSendRequestException, RemotingTimeoutException, UnsupportedEncodingException, InterruptedException, MQBrokerException;

    void createAndUpdateTopicConfig(final String addr,
        final TopicConfig config) throws RemotingException, MQBrokerException,
        InterruptedException, MQClientException;

    void createAndUpdatePlainAccessConfig(final String addr, final PlainAccessConfig plainAccessConfig) throws RemotingException, MQBrokerException,
        InterruptedException, MQClientException;

    void deletePlainAccessConfig(final String addr, final String accessKey) throws RemotingException, MQBrokerException,
        InterruptedException, MQClientException;

    void updateGlobalWhiteAddrConfig(final String addr, final String globalWhiteAddrs)throws RemotingException, MQBrokerException,
        InterruptedException, MQClientException;

    ClusterAclVersionInfo examineBrokerClusterAclVersionInfo(final String addr) throws RemotingException, MQBrokerException,
        InterruptedException, MQClientException;

    AclConfig examineBrokerClusterAclConfig(final String addr) throws RemotingException, MQBrokerException,
        InterruptedException, MQClientException;

    void createAndUpdateSubscriptionGroupConfig(final String addr,
        final SubscriptionGroupConfig config) throws RemotingException,
        MQBrokerException, InterruptedException, MQClientException;

    SubscriptionGroupConfig examineSubscriptionGroupConfig(final String addr, final String group);

    TopicConfig examineTopicConfig(final String addr, final String topic);

    TopicStatsTable examineTopicStats(
        final String topic) throws RemotingException, MQClientException, InterruptedException,
        MQBrokerException;

    TopicList fetchAllTopicList() throws RemotingException, MQClientException, InterruptedException;

    TopicList fetchTopicsByCLuster(
        String clusterName) throws RemotingException, MQClientException, InterruptedException;

    KVTable fetchBrokerRuntimeStats(
        final String brokerAddr) throws RemotingConnectException, RemotingSendRequestException,
        RemotingTimeoutException, InterruptedException, MQBrokerException;

    ConsumeStats examineConsumeStats(
        final String consumerGroup) throws RemotingException, MQClientException, InterruptedException,
        MQBrokerException;

    ConsumeStats examineConsumeStats(final String consumerGroup,
        final String topic) throws RemotingException, MQClientException,
        InterruptedException, MQBrokerException;

    ClusterInfo examineBrokerClusterInfo() throws InterruptedException, MQBrokerException, RemotingTimeoutException,
        RemotingSendRequestException, RemotingConnectException;

    TopicRouteData examineTopicRouteInfo(
        final String topic) throws RemotingException, MQClientException, InterruptedException;

    ConsumerConnection examineConsumerConnectionInfo(final String consumerGroup) throws RemotingConnectException,
        RemotingSendRequestException, RemotingTimeoutException, InterruptedException, MQBrokerException, RemotingException,
        MQClientException;

    ProducerConnection examineProducerConnectionInfo(final String producerGroup,
        final String topic) throws RemotingException,
        MQClientException, InterruptedException, MQBrokerException;

    List<String> getNameServerAddressList();

    int wipeWritePermOfBroker(final String namesrvAddr, String brokerName) throws RemotingCommandException,
        RemotingConnectException, RemotingSendRequestException, RemotingTimeoutException, InterruptedException, MQClientException;

    void putKVConfig(final String namespace, final String key, final String value);

    String getKVConfig(final String namespace,
        final String key) throws RemotingException, MQClientException, InterruptedException;

    KVTable getKVListByNamespace(
        final String namespace) throws RemotingException, MQClientException, InterruptedException;

    void deleteTopicInBroker(final Set<String> addrs, final String topic) throws RemotingException, MQBrokerException,
        InterruptedException, MQClientException;

    void deleteTopicInNameServer(final Set<String> addrs,
        final String topic) throws RemotingException, MQBrokerException,
        InterruptedException, MQClientException;

    void deleteSubscriptionGroup(final String addr, String groupName) throws RemotingException, MQBrokerException,
        InterruptedException, MQClientException;

    void createAndUpdateKvConfig(String namespace, String key,
        String value) throws RemotingException, MQBrokerException,
        InterruptedException, MQClientException;

    void deleteKvConfig(String namespace, String key) throws RemotingException, MQBrokerException, InterruptedException,
        MQClientException;

    List<RollbackStats> resetOffsetByTimestampOld(String consumerGroup, String topic, long timestamp, boolean force)
        throws RemotingException, MQBrokerException, InterruptedException, MQClientException;

    Map<MessageQueue, Long> resetOffsetByTimestamp(String topic, String group, long timestamp, boolean isForce)
        throws RemotingException, MQBrokerException, InterruptedException, MQClientException;

    void resetOffsetNew(String consumerGroup, String topic, long timestamp) throws RemotingException, MQBrokerException,
        InterruptedException, MQClientException;

    Map<String, Map<MessageQueue, Long>> getConsumeStatus(String topic, String group,
        String clientAddr) throws RemotingException,
        MQBrokerException, InterruptedException, MQClientException;

    void createOrUpdateOrderConf(String key, String value,
        boolean isCluster) throws RemotingException, MQBrokerException,
        InterruptedException, MQClientException;

    GroupList queryTopicConsumeByWho(final String topic) throws RemotingConnectException, RemotingSendRequestException,
        RemotingTimeoutException, InterruptedException, MQBrokerException, RemotingException, MQClientException;

    List<QueueTimeSpan> queryConsumeTimeSpan(final String topic,
        final String group) throws InterruptedException, MQBrokerException,
        RemotingException, MQClientException;

    boolean cleanExpiredConsumerQueue(String cluster) throws RemotingConnectException, RemotingSendRequestException,
        RemotingTimeoutException, MQClientException, InterruptedException;

    boolean cleanExpiredConsumerQueueByAddr(String addr) throws RemotingConnectException, RemotingSendRequestException,
        RemotingTimeoutException, MQClientException, InterruptedException;

    boolean cleanUnusedTopic(String cluster) throws RemotingConnectException, RemotingSendRequestException,
        RemotingTimeoutException, MQClientException, InterruptedException;

    boolean cleanUnusedTopicByAddr(String addr) throws RemotingConnectException, RemotingSendRequestException,
        RemotingTimeoutException, MQClientException, InterruptedException;

    ConsumerRunningInfo getConsumerRunningInfo(final String consumerGroup, final String clientId, final boolean jstack)
        throws RemotingException, MQClientException, InterruptedException;

    ConsumeMessageDirectlyResult consumeMessageDirectly(String consumerGroup,
        String clientId,
        String msgId) throws RemotingException, MQClientException, InterruptedException, MQBrokerException;

    ConsumeMessageDirectlyResult consumeMessageDirectly(String consumerGroup,
        String clientId,
        String topic,
        String msgId) throws RemotingException, MQClientException, InterruptedException, MQBrokerException;

    List<MessageTrack> messageTrackDetail(
        MessageExt msg) throws RemotingException, MQClientException, InterruptedException,
        MQBrokerException;

    void cloneGroupOffset(String srcGroup, String destGroup, String topic, boolean isOffline) throws RemotingException,
        MQClientException, InterruptedException, MQBrokerException;

    BrokerStatsData viewBrokerStatsData(final String brokerAddr, final String statsName, final String statsKey)
        throws RemotingConnectException, RemotingSendRequestException, RemotingTimeoutException, MQClientException,
        InterruptedException;

    Set<String> getClusterList(final String topic) throws RemotingConnectException, RemotingSendRequestException,
        RemotingTimeoutException, MQClientException, InterruptedException;

    ConsumeStatsList fetchConsumeStatsInBroker(final String brokerAddr, boolean isOrder,
        long timeoutMillis) throws RemotingConnectException, RemotingSendRequestException,
        RemotingTimeoutException, MQClientException, InterruptedException;

    Set<String> getTopicClusterList(
        final String topic) throws InterruptedException, MQBrokerException, MQClientException, RemotingException;

    SubscriptionGroupWrapper getAllSubscriptionGroup(final String brokerAddr,
        long timeoutMillis) throws InterruptedException, RemotingTimeoutException, RemotingSendRequestException,
        RemotingConnectException, MQBrokerException;

    TopicConfigSerializeWrapper getAllTopicGroup(final String brokerAddr,
        long timeoutMillis) throws InterruptedException, RemotingTimeoutException, RemotingSendRequestException,
        RemotingConnectException, MQBrokerException;

    void updateConsumeOffset(String brokerAddr, String consumeGroup, MessageQueue mq,
        long offset) throws RemotingException, InterruptedException, MQBrokerException;

    /**
     * Update name server config.
     * <br>
     * Command Code : RequestCode.UPDATE_NAMESRV_CONFIG
     *
     * <br> If param(nameServers) is null or empty, will use name servers from ns!
     */
    void updateNameServerConfig(final Properties properties,
        final List<String> nameServers) throws InterruptedException, RemotingConnectException,
        UnsupportedEncodingException, RemotingSendRequestException, RemotingTimeoutException,
        MQClientException, MQBrokerException;

    /**
     * Get name server config.
     * <br>
     * Command Code : RequestCode.GET_NAMESRV_CONFIG
     * <br> If param(nameServers) is null or empty, will use name servers from ns!
     *
     * @return The fetched name server config
     */
    Map<String, Properties> getNameServerConfig(final List<String> nameServers) throws InterruptedException,
        RemotingTimeoutException, RemotingSendRequestException, RemotingConnectException,
        MQClientException, UnsupportedEncodingException;

    /**
     * query consume queue data
     *
     * @param brokerAddr broker ip address
     * @param topic topic
     * @param queueId id of queue
     * @param index start offset
     * @param count how many
     * @param consumerGroup group
     */
    QueryConsumeQueueResponseBody queryConsumeQueue(final String brokerAddr,
        final String topic, final int queueId,
        final long index, final int count, final String consumerGroup)
        throws InterruptedException, RemotingTimeoutException, RemotingSendRequestException, RemotingConnectException, MQClientException;

    boolean resumeCheckHalfMessage(String msgId)
            throws RemotingException, MQClientException, InterruptedException, MQBrokerException;

    boolean resumeCheckHalfMessage(final String topic, final String msgId) throws RemotingException, MQClientException, InterruptedException, MQBrokerException;
}
```

`MQAdminExt`的父接口`MQAdmin`也是一些rocketmq集群管理员能够执行的操作对应的方法：
```java
public interface MQAdmin {
    /**
     * Creates an topic
     *
     * @param key accesskey
     * @param newTopic topic name
     * @param queueNum topic's queue number
     */
    void createTopic(final String key, final String newTopic, final int queueNum)
        throws MQClientException;

    /**
     * Creates an topic
     *
     * @param key accesskey
     * @param newTopic topic name
     * @param queueNum topic's queue number
     * @param topicSysFlag topic system flag
     */
    void createTopic(String key, String newTopic, int queueNum, int topicSysFlag)
        throws MQClientException;

    /**
     * Gets the message queue offset according to some time in milliseconds<br>
     * be cautious to call because of more IO overhead
     *
     * @param mq Instance of MessageQueue
     * @param timestamp from when in milliseconds.
     * @return offset
     */
    long searchOffset(final MessageQueue mq, final long timestamp) throws MQClientException;

    /**
     * Gets the max offset
     *
     * @param mq Instance of MessageQueue
     * @return the max offset
     */
    long maxOffset(final MessageQueue mq) throws MQClientException;

    /**
     * Gets the minimum offset
     *
     * @param mq Instance of MessageQueue
     * @return the minimum offset
     */
    long minOffset(final MessageQueue mq) throws MQClientException;

    /**
     * Gets the earliest stored message time
     *
     * @param mq Instance of MessageQueue
     * @return the time in microseconds
     */
    long earliestMsgStoreTime(final MessageQueue mq) throws MQClientException;

    /**
     * Query message according to message id
     *
     * @param offsetMsgId message id
     * @return message
     */
    MessageExt viewMessage(final String offsetMsgId) throws RemotingException, MQBrokerException,
        InterruptedException, MQClientException;

    /**
     * Query messages
     *
     * @param topic message topic
     * @param key message key index word
     * @param maxNum max message number
     * @param begin from when
     * @param end to when
     * @return Instance of QueryResult
     */
    QueryResult queryMessage(final String topic, final String key, final int maxNum, final long begin,
        final long end) throws MQClientException, InterruptedException;

    /**
     * @return The {@code MessageExt} of given msgId
     */
    MessageExt viewMessage(String topic,
        String msgId) throws RemotingException, MQBrokerException, InterruptedException, MQClientException;

}
```

`DefaultMQAdminExt`在实现上面两个管理员接口时实际上是创建了`DefaultMQAdminExtImpl`对象来完成接口的实现，`DefaultMQAdminExt`对象主要任务是继承`ClientConfig`类，所以下面再分析`DefaultMQAdminExtImpl`类的实现。

`DefaultMQAdminExtImpl`类实现了`MQAdminExt`和`MQAdminExtInner`接口，`MQAdminExt`接口就是上面说的管理员接口，`MQAdminExtInner`接口是个标记接口，没有方法定义。`DefaultMQAdminExtImpl`类的成员变量`MQClientInstance`类是个公用类，实现了rocketmq中的组件可能用到的多个方法，如创建topic、发送消息、更新路由信息等。`DefaultMQAdminExtImpl`类在实现`MQAdminExt`接口的大部分方法时实际上使用的是`MQClientInstance`类，如创建topic的实现：
```java
@Override
public void createAndUpdateTopicConfig(String addr, TopicConfig config) throws RemotingException, MQBrokerException,
    InterruptedException, MQClientException {
    this.mqClientInstance.getMQClientAPIImpl().createTopic(addr, this.defaultMQAdminExt.getCreateTopicKey(), config, timeoutMillis);
}
```

`DefaultMQAdminExtImpl`类的主要作用就是正确的初始化`MQClientInstance`类，初始化过程在`DefaultMQAdminExtImpl`类的`start()`方法：
```java
@Override
public void start() throws MQClientException {
    switch (this.serviceState) {
        case CREATE_JUST:
            // 更新状态为启动失败
            this.serviceState = ServiceState.START_FAILED;

            // 如果没有设置rocketmq.client.name属性的值，则使用pid作为instanceName
            this.defaultMQAdminExt.changeInstanceNameToPID();

            // 创建MQClientInstance对象
            this.mqClientInstance = MQClientManager.getInstance().getOrCreateMQClientInstance(this.defaultMQAdminExt, rpcHook);

            // this.defaultMQAdminExt.getAdminExtGroup()默认返回admin_ext_group字符串，这里将当前DefaultMQAdminExtImpl
            // 对象作为MQAdminExtInner注册到mqClientInstance实例，如果group对应的MQAdminExtInner已存在则返回false
            boolean registerOK = mqClientInstance.registerAdminExt(this.defaultMQAdminExt.getAdminExtGroup(), this);
            if (!registerOK) {
                // 注册失败时抛出异常
                this.serviceState = ServiceState.CREATE_JUST;
                throw new MQClientException("The adminExt group[" + this.defaultMQAdminExt.getAdminExtGroup()
                    + "] has created already, specifed another name please."
                    + FAQUrl.suggestTodo(FAQUrl.GROUP_NAME_DUPLICATE_URL), null);
            }

            mqClientInstance.start();

            log.info("the adminExt [{}] start OK", this.defaultMQAdminExt.getAdminExtGroup());

            // 更新状态为running·
            this.serviceState = ServiceState.RUNNING;
            break;
        case RUNNING: // 其他状态调用start方法都抛出异常
        case START_FAILED:
        case SHUTDOWN_ALREADY:
            throw new MQClientException("The AdminExt service state not OK, maybe started once, "
                + this.serviceState
                + FAQUrl.suggestTodo(FAQUrl.CLIENT_SERVICE_NOT_OK), null);
        default:
            break;
    }
}
```

`start()`方法使用`MQClientManager`类创建`MQClientInstance`实例，并且保证只创建一个（clientId相同的则不重复创建），创建过程如下：
```java
public MQClientInstance getOrCreateMQClientInstance(final ClientConfig clientConfig, RPCHook rpcHook) {
    /*
    构建clientId，rocketmq用clientId唯一标记一个客户端实例，一个客户端实例对于Broker而言会开辟一个Netty的客户端实例。
    而ClientID是由ClientIP+InstanceName构成，故如果一个进程中多个实例（无论Producer还是Consumer）ClientIP和InstanceName
    都一样，他们将公用一个内部实例（同一套网络连接，线程资源等），也就是同一个MQClientInstance实例。如果没有设置InstanceName，
    则默认使用pid作为InstanceName

    此外，ClientID在对于Consumer负载均衡的时候起到唯一标识的作用，一旦多个实例（无论不同进程、还是同一进程）的多个
    Consumer实例有一样的ClientID，负载均衡的时候会把两个实例当作一个client（因为clientID相同）。故为了避免不必要的问题，
    ClientIP+instanceName的组合建议唯一，除非有意需要共用连接、资源。
     */
    String clientId = clientConfig.buildMQClientId();
    MQClientInstance instance = this.factoryTable.get(clientId);
    if (null == instance) {
        instance =
            new MQClientInstance(clientConfig.cloneClientConfig(),
                this.factoryIndexGenerator.getAndIncrement(), clientId, rpcHook);
        MQClientInstance prev = this.factoryTable.putIfAbsent(clientId, instance);
        if (prev != null) {
            instance = prev;
            log.warn("Returned Previous MQClientInstance for clientId:[{}]", clientId);
        } else {
            log.info("Created new MQClientInstance for clientId:[{}]", clientId);
        }
    }

    return instance;
}
```

创建`MQClientInstance`实例之后，调用`MQClientInstance`实例的`registerAdminExt()`方法将`DefaultMQAdminExtImpl`类本身保存到`MQClientInstance`实例的map中：
```
private final ConcurrentMap<String/* group */, MQAdminExtInner> adminExtTable = new ConcurrentHashMap<String, MQAdminExtInner>();
```

key的值为`this.defaultMQAdminExt.getAdminExtGroup()`方法的返回值，默认为`admin_ext_group`字符串。初始化完`MQClientInstance`实例后调用其`start()`方法并更新状态。`MQClientInstance`实例的`start()`方法又执行了一些初始化工作，代码：
```java
public void start() throws MQClientException {

    synchronized (this) {
        switch (this.serviceState) {
            case CREATE_JUST:
                // 更新状态
                this.serviceState = ServiceState.START_FAILED;
                // If not specified,looking address from name server
                // 从环境变量中获取namesrv的地址，如果获取不到则从默认url抓取namesrv地址
                if (null == this.clientConfig.getNamesrvAddr()) {
                    this.mQClientAPIImpl.fetchNameServerAddr();
                }
                // Start request-response channel
                // MQClientAPIImpl实现了真正发送各种网络请求的逻辑，必然用到了netty，这里的start方法实际上是调用
                // NettyRemotingClient的start方法创建netty的Bootstrap
                this.mQClientAPIImpl.start();
                // Start various schedule tasks
                // 开启定时任务，最主要的是更新路由信息，发送心跳等
                this.startScheduledTask();
                // Start pull service
                // pullMessageService是个无限循环的线程，负责从指定队列拉取消息
                this.pullMessageService.start();
                // Start rebalance service
                // rebalanceService是个无限循环的线程，以固定间隔调用当前类的doRebalance方法
                this.rebalanceService.start();
                // Start push service
                // 启动生产者
                this.defaultMQProducer.getDefaultMQProducerImpl().start(false);
                log.info("the client factory [{}] start OK", this.clientId);
                this.serviceState = ServiceState.RUNNING;
                break;
            case START_FAILED:
                throw new MQClientException("The Factory object[" + this.getClientId() + "] has been created before, and failed.", null);
            default:
                break;
        }
    }
}
```

`MQClientInstance`类的`start()`方法的作用主要是调用若干成员变量的`start()`方法并开启一些定时任务，具体的细节不赘述，下面主要分析`MQClientInstance`类创建topic的过程。

回到创建`MQClientInstance`实例的`DefaultMQAdminExtImpl`类，其实现创建topic的方法是：
```java
@Override
public void createAndUpdateTopicConfig(String addr, TopicConfig config) throws RemotingException, MQBrokerException,
    InterruptedException, MQClientException {
    this.mqClientInstance.getMQClientAPIImpl().createTopic(addr, this.defaultMQAdminExt.getCreateTopicKey(), config, timeoutMillis);
}
```

可以看到创建topic的过程调用的是`MQClientInstance`类的`MQClientAPIImpl`成员变量的`createTopic()`方法，而`MQClientAPIImpl`类也可以视为一个工具类，其维护了一个`RemotingClient`类型的成员变量，默认实现为`NettyRemotingClient`，该类的作用在笔记[NettyRemotingClient类的实现](../my_doc/公共组件/NettyRemotingClient类的实现.md)中已经介绍了。通过`RemotingClient`对象，`MQClientAPIImpl`类提供了多个网络请求相关的方法，如管理topic、发送消息、获取broker信息等，对于创建topic的实现，对应的`createTopic()`方法代码：
```java
public void createTopic(final String addr, final String defaultTopic, final TopicConfig topicConfig,
    final long timeoutMillis)
    throws RemotingException, MQBrokerException, InterruptedException, MQClientException {
    CreateTopicRequestHeader requestHeader = new CreateTopicRequestHeader();
    requestHeader.setTopic(topicConfig.getTopicName());
    requestHeader.setDefaultTopic(defaultTopic);
    requestHeader.setReadQueueNums(topicConfig.getReadQueueNums());
    requestHeader.setWriteQueueNums(topicConfig.getWriteQueueNums());
    requestHeader.setPerm(topicConfig.getPerm());
    requestHeader.setTopicFilterType(topicConfig.getTopicFilterType().name());
    requestHeader.setTopicSysFlag(topicConfig.getTopicSysFlag());
    requestHeader.setOrder(topicConfig.isOrder());

    RemotingCommand request = RemotingCommand.createRequestCommand(RequestCode.UPDATE_AND_CREATE_TOPIC, requestHeader);

    RemotingCommand response = this.remotingClient.invokeSync(MixAll.brokerVIPChannel(this.clientConfig.isVipChannelEnabled(), addr),
        request, timeoutMillis);
    assert response != null;
    switch (response.getCode()) {
        case ResponseCode.SUCCESS: {
            return;
        }
        default:
            break;
    }

    throw new MQClientException(response.getCode(), response.getRemark());
}
```

到这里终于到了真正发送创建topic请求的地方，发送过程很简单，创建一个`RemotingCommand`对象并发送同步请求，关于`RemotingCommand`对象和请求的发送过程，可以看笔记[remoting模块的作用和实现](../my_doc/公共组件/remoting模块的作用和实现.md)。

对于其他的管理员操作，实现过程和上面创建topic的过程是类似的，就不赘述了，当请求发送出去后，需要broker处理请求，下面再分析broker处理创建topic请求的过程。

处理创建topic请求的code为`RequestCode.UPDATE_AND_CREATE_TOPIC`，从broker的入口`BrokerController`类的代码可以看出，创建topic请求对应的`NettyRequestProcessor`为`AdminBrokerProcessor`，该类对应的处理方法为`updateAndCreateTopic()`，代码如下：
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

上面的实现过程逻辑不复杂，重点在于最后的`this.brokerController.registerIncrementBrokerData(topicConfig, this.brokerController.getTopicConfigManager().getDataVersion());`语句，向namesrv发送了`RequestCode.REGISTER_BROKER`请求上报topic的配置，namesrv处理`RequestCode.REGISTER_BROKER`请求的过程可以看笔记[Namesrv实现](../namesrv/README.md)，这里不在赘述。以上就是创建topic的过程，`mqadmin`命令的其他选项的实现过程也大同小异。