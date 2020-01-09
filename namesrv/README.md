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

## 实现
Namesrv的实现很简单，用Netty接收请求并根据请求返回信息，对于保存的各种信息，其实就是对各种Map的处理。

Namesrv的实现使得Namesrv稳定性非常高，无状态互不影响，非常易于横向扩展，同时也只涉及到很少的磁盘读写（对kvConfig.json文件有更新时会执行该文件的持久化），所以性能也非常高。

下面分析Namesrv的实现，首先是入口`NamesrvStartup`类：
```java
public static void main(String[] args) {
    main0(args);
}

public static NamesrvController main0(String[] args) {
    try {
        // 根据命令行参数创建NamesrvController
        NamesrvController controller = createNamesrvController(args);
        // 调用NamesrvController的initialize方法和start方法
        start(controller);
        String tip = "The Name Server boot success. serializeType=" + RemotingCommand.getSerializeTypeConfigInThisServer();
        log.info(tip);
        System.out.printf("%s%n", tip);
        return controller;
    } catch (Throwable e) {
        e.printStackTrace();
        System.exit(-1);
    }

    return null;
}

public static NamesrvController start(final NamesrvController controller) throws Exception {
    if (null == controller) {
        throw new IllegalArgumentException("NamesrvController is null");
    }

    boolean initResult = controller.initialize();
    if (!initResult) {
        controller.shutdown();
        System.exit(-3);
    }

    // 添加hook，在JVM关闭时调用controller的shutdown方法
    Runtime.getRuntime().addShutdownHook(new ShutdownHookThread(log, new Callable<Void>() {
        @Override
        public Void call() throws Exception {
            controller.shutdown();
            return null;
        }
    }));

    controller.start();

    return controller;
}
```

`NamesrvStartup`类的责任是创建和初始化`NamesrvController`，`NamesrvController`的初始化在其`initialize()`方法，代码：
```java
public boolean initialize() {

    // 加载kvConfig.json文件的内容，保存到KVConfigManager的configTable属性
    this.kvConfigManager.load();

    // NettyRemotingServer封装了netty相关工作
    this.remotingServer = new NettyRemotingServer(this.nettyServerConfig, this.brokerHousekeepingService);

    // 创建一个线程池，NettyRemotingServer会用到
    this.remotingExecutor =
        Executors.newFixedThreadPool(nettyServerConfig.getServerWorkerThreads(), new ThreadFactoryImpl("RemotingExecutorThread_"));

    // 注册一个NettyRequestProcessor到remotingServer，用于处理netty监听到的各种网络请求，默认实现是DefaultRequestProcessor
    this.registerProcessor();

    // 定时扫描所有broker节点，close所有过期的节点的channel
    this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {

        @Override
        public void run() {
            NamesrvController.this.routeInfoManager.scanNotActiveBroker();
        }
    }, 5, 10, TimeUnit.SECONDS);

    // 定时打印kvConfig.json文件配置
    this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {

        @Override
        public void run() {
            NamesrvController.this.kvConfigManager.printAllPeriodically();
        }
    }, 1, 10, TimeUnit.MINUTES);

    if (TlsSystemConfig.tlsMode != TlsMode.DISABLED) {
        // Register a listener to reload SslContext
        try {
            // 监听tls配置文件是否有变化，有变化则重载配置，监听变化的实现原理是用一个线程每隔500毫秒获取一次
            // 被监听文件的hash并和之前的hash比较，不同则调用这里的onChanged
            fileWatchService = new FileWatchService(
                new String[] {
                    TlsSystemConfig.tlsServerCertPath,
                    TlsSystemConfig.tlsServerKeyPath,
                    TlsSystemConfig.tlsServerTrustCertPath
                },
                new FileWatchService.Listener() {
                    boolean certChanged, keyChanged = false;
                    @Override
                    public void onChanged(String path) {
                        if (path.equals(TlsSystemConfig.tlsServerTrustCertPath)) {
                            log.info("The trust certificate changed, reload the ssl context");
                            reloadServerSslContext();
                        }
                        if (path.equals(TlsSystemConfig.tlsServerCertPath)) {
                            certChanged = true;
                        }
                        if (path.equals(TlsSystemConfig.tlsServerKeyPath)) {
                            keyChanged = true;
                        }
                        if (certChanged && keyChanged) {
                            log.info("The certificate and private key changed, reload the ssl context");
                            certChanged = keyChanged = false;
                            reloadServerSslContext();
                        }
                    }
                    private void reloadServerSslContext() {
                        ((NettyRemotingServer) remotingServer).loadSslContext();
                    }
                });
        } catch (Exception e) {
            log.warn("FileWatchService created error, can't load the certificate dynamically");
        }
    }

    return true;
}
```

`NamesrvController`的`initialize()`方法创建了一些组件并开启了若干定时任务，各个组件和定时任务的作用在注释中说明了，具体的实现可以看具体的相关方法及方法中添加的注释。对于上面的初始化过程，可以发现`NamesrvController`中最关键的组件是`NettyRemotingServer`，该类封装了网络请求相关的处理过程，Namesrv收到的网络请求会由`NettyRemotingServer`接收并解析交给`registerProcessor()`方法中注册的`DefaultRequestProcessor`类处理。该类的实现在笔记[NettyRemotingServer类的实现](../my_doc/公共组件/NettyRemotingServer类的实现.md)中分析了。