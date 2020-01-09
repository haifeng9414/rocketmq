和[NettyRemotingServer类的实现](NettyRemotingServer类的实现.md)的分析过程一样，按照`NettyRemotingClient`实现的方法来分析其功能，这里假设已经看过笔记[NettyRemotingAbstract的实现](NettyRemotingAbstract类的实现.md)和[RemotingClient接口](RemotingClient接口.md)。

需要`NettyRemotingClient`类实现的方法如下，这些方法的作用在[NettyRemotingAbstract的实现](NettyRemotingAbstract类的实现.md)和[RemotingClient接口](RemotingClient接口.md)都有介绍：
```
public void updateNameServerAddressList(List<String> addrs);

public List<String> getNameServerAddressList();

public RemotingCommand invokeSync(String addr, RemotingCommand request, long timeoutMillis) throws InterruptedException, RemotingConnectException, RemotingSendRequestException, RemotingTimeoutException;

public void invokeAsync(String addr, RemotingCommand request, long timeoutMillis, InvokeCallback invokeCallback) throws InterruptedException, RemotingConnectException, RemotingTooMuchRequestException, RemotingTimeoutException, RemotingSendRequestException;

public void invokeOneway(String addr, RemotingCommand request, long timeoutMillis) throws InterruptedException, RemotingConnectException, RemotingTooMuchRequestException, RemotingTimeoutException, RemotingSendRequestException;

public void registerProcessor(int requestCode, NettyRequestProcessor processor, ExecutorService executor);

public void setCallbackExecutor(ExecutorService callbackExecutor);

public boolean isChannelWritable(String addr);

public void start();

public void shutdown();

public void registerRPCHook(RPCHook rpcHook);

public ChannelEventListener getChannelEventListener();

public ExecutorService getCallbackExecutor();
```

这里先放下`NettyRemotingClient`类的源码，源码中已经有了比较详细的注释，`NettyRemotingClient`类的实现在源码后分析：
```java
public class NettyRemotingClient extends NettyRemotingAbstract implements RemotingClient {
    private static final InternalLogger log = InternalLoggerFactory.getLogger(RemotingHelper.ROCKETMQ_REMOTING);

    private static final long LOCK_TIMEOUT_MILLIS = 3000;

    private final NettyClientConfig nettyClientConfig;
    // netty组件
    private final Bootstrap bootstrap = new Bootstrap();
    // netty组件
    private final EventLoopGroup eventLoopGroupWorker;
    // 保证创建Channel对象及关闭Channel对象期间的线程安全
    private final Lock lockChannelTables = new ReentrantLock();
    private final ConcurrentMap<String /* addr */, ChannelWrapper> channelTables = new ConcurrentHashMap<String, ChannelWrapper>();

    private final Timer timer = new Timer("ClientHouseKeepingService", true);

    private final AtomicReference<List<String>> namesrvAddrList = new AtomicReference<List<String>>();
    private final AtomicReference<String> namesrvAddrChoosed = new AtomicReference<String>();
    private final AtomicInteger namesrvIndex = new AtomicInteger(initValueIndex());
    // 用于控制getAndCreateNameserverChannel方法轮询namesrvAddrList选择namesrv的同步
    private final Lock lockNamesrvChannel = new ReentrantLock();

    // 当注册NettyRequestProcessor时没有指定ExecutorService的话用publicExecutor作为ExecutorService
    private final ExecutorService publicExecutor;

    /**
     * Invoke the callback methods in this executor when process response.
     */
    private ExecutorService callbackExecutor;
    // channel连接、关闭、异常和空闲的监听器
    private final ChannelEventListener channelEventListener;
    private DefaultEventExecutorGroup defaultEventExecutorGroup;

    public NettyRemotingClient(final NettyClientConfig nettyClientConfig) {
        this(nettyClientConfig, null);
    }

    public NettyRemotingClient(final NettyClientConfig nettyClientConfig,
        final ChannelEventListener channelEventListener) {
        // 初始化oneway和异步请求的信号量
        super(nettyClientConfig.getClientOnewaySemaphoreValue(), nettyClientConfig.getClientAsyncSemaphoreValue());
        this.nettyClientConfig = nettyClientConfig;
        // channel连接、关闭、异常和空闲的监听器
        this.channelEventListener = channelEventListener;

        int publicThreadNums = nettyClientConfig.getClientCallbackExecutorThreads();
        if (publicThreadNums <= 0) {
            publicThreadNums = 4;
        }

        // 当注册NettyRequestProcessor时没有指定ExecutorService的话用publicExecutor作为ExecutorService
        this.publicExecutor = Executors.newFixedThreadPool(publicThreadNums, new ThreadFactory() {
            private AtomicInteger threadIndex = new AtomicInteger(0);

            @Override
            public Thread newThread(Runnable r) {
                return new Thread(r, "NettyClientPublicExecutor_" + this.threadIndex.incrementAndGet());
            }
        });

        this.eventLoopGroupWorker = new NioEventLoopGroup(1, new ThreadFactory() {
            private AtomicInteger threadIndex = new AtomicInteger(0);

            @Override
            public Thread newThread(Runnable r) {
                return new Thread(r, String.format("NettyClientSelector_%d", this.threadIndex.incrementAndGet()));
            }
        });

        if (nettyClientConfig.isUseTLS()) {
            try {
                sslContext = TlsHelper.buildSslContext(true);
                log.info("SSL enabled for client");
            } catch (IOException e) {
                log.error("Failed to create SSLContext", e);
            } catch (CertificateException e) {
                log.error("Failed to create SSLContext", e);
                throw new RuntimeException("Failed to create SSLContext", e);
            }
        }
    }

    // 产生一个0-998的随机数
    private static int initValueIndex() {
        Random r = new Random();

        return Math.abs(r.nextInt() % 999) % 999;
    }

    @Override
    public void start() {
        // channel事件的工作线程
        this.defaultEventExecutorGroup = new DefaultEventExecutorGroup(
            nettyClientConfig.getClientWorkerThreads(),
            new ThreadFactory() {

                private AtomicInteger threadIndex = new AtomicInteger(0);

                @Override
                public Thread newThread(Runnable r) {
                    return new Thread(r, "NettyClientWorkerThread_" + this.threadIndex.incrementAndGet());
                }
            });

        // 下面的配置在NettyRemotingServer中已经介绍过了，这里不再赘述，需要注意这里没有调用handler的connect方法创建channel，因为现
        // 在还没有发送请求的需求，关于channel的创建，看invokeSync等方法的实现
        Bootstrap handler = this.bootstrap.group(this.eventLoopGroupWorker).channel(NioSocketChannel.class)
            .option(ChannelOption.TCP_NODELAY, true)
            .option(ChannelOption.SO_KEEPALIVE, false)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, nettyClientConfig.getConnectTimeoutMillis())
            .option(ChannelOption.SO_SNDBUF, nettyClientConfig.getClientSocketSndBufSize())
            .option(ChannelOption.SO_RCVBUF, nettyClientConfig.getClientSocketRcvBufSize())
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                public void initChannel(SocketChannel ch) throws Exception {
                    ChannelPipeline pipeline = ch.pipeline();
                    if (nettyClientConfig.isUseTLS()) {
                        if (null != sslContext) {
                            pipeline.addFirst(defaultEventExecutorGroup, "sslHandler", sslContext.newHandler(ch.alloc()));
                            log.info("Prepend SSL handler");
                        } else {
                            log.warn("Connections are insecure as SSLContext is null!");
                        }
                    }
                    pipeline.addLast(
                        defaultEventExecutorGroup,
                        new NettyEncoder(),
                        new NettyDecoder(),
                        new IdleStateHandler(0, 0, nettyClientConfig.getClientChannelMaxIdleTimeSeconds()),
                        new NettyConnectManageHandler(),
                        new NettyClientHandler());
                }
            });

        this.timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                try {
                    NettyRemotingClient.this.scanResponseTable();
                } catch (Throwable e) {
                    log.error("scanResponseTable exception", e);
                }
            }
        }, 1000 * 3, 1000);

        if (this.channelEventListener != null) {
            this.nettyEventExecutor.start();
        }
    }

    @Override
    public void shutdown() {
        try {
            this.timer.cancel();

            // 关闭所有的channel
            for (ChannelWrapper cw : this.channelTables.values()) {
                this.closeChannel(null, cw.getChannel());
            }

            this.channelTables.clear();

            this.eventLoopGroupWorker.shutdownGracefully();

            if (this.nettyEventExecutor != null) {
                this.nettyEventExecutor.shutdown();
            }

            if (this.defaultEventExecutorGroup != null) {
                this.defaultEventExecutorGroup.shutdownGracefully();
            }
        } catch (Exception e) {
            log.error("NettyRemotingClient shutdown exception, ", e);
        }

        if (this.publicExecutor != null) {
            try {
                this.publicExecutor.shutdown();
            } catch (Exception e) {
                log.error("NettyRemotingServer shutdown exception, ", e);
            }
        }
    }

    // 关闭connect操作失败的channel
    public void closeChannel(final String addr, final Channel channel) {
        if (null == channel)
            return;

        final String addrRemote = null == addr ? RemotingHelper.parseChannelRemoteAddr(channel) : addr;

        try {
            if (this.lockChannelTables.tryLock(LOCK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                try {
                    boolean removeItemFromTable = true;
                    final ChannelWrapper prevCW = this.channelTables.get(addrRemote);

                    log.info("closeChannel: begin close the channel[{}] Found: {}", addrRemote, prevCW != null);

                    // 在加锁之前channelTables的数据是可能变化的，则channel对应的ChannelWrapper对象可能也会发送变化，这里就是做
                    // 必要的检查并记日志
                    if (null == prevCW) {
                        log.info("closeChannel: the channel[{}] has been removed from the channel table before", addrRemote);
                        removeItemFromTable = false;
                    } else if (prevCW.getChannel() != channel) {
                        log.info("closeChannel: the channel[{}] has been closed before, and has been created again, nothing to do.",
                            addrRemote);
                        removeItemFromTable = false;
                    }

                    if (removeItemFromTable) {
                        this.channelTables.remove(addrRemote);
                        log.info("closeChannel: the channel[{}] was removed from channel table", addrRemote);
                    }

                    // 关闭channel
                    RemotingUtil.closeChannel(channel);
                } catch (Exception e) {
                    log.error("closeChannel: close the channel exception", e);
                } finally {
                    this.lockChannelTables.unlock();
                }
            } else {
                log.warn("closeChannel: try to lock channel table, but timeout, {}ms", LOCK_TIMEOUT_MILLIS);
            }
        } catch (InterruptedException e) {
            log.error("closeChannel exception", e);
        }
    }

    @Override
    public void registerRPCHook(RPCHook rpcHook) {
        if (rpcHook != null && !rpcHooks.contains(rpcHook)) {
            rpcHooks.add(rpcHook);
        }
    }

    // 关闭channel，和另一个closeChannel方法的实现差不多
    public void closeChannel(final Channel channel) {
        if (null == channel)
            return;

        try {
            if (this.lockChannelTables.tryLock(LOCK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                try {
                    boolean removeItemFromTable = true;
                    ChannelWrapper prevCW = null;
                    String addrRemote = null;
                    // 由于没有addr信息，所以只能遍历所有的ChannelWrapper寻找
                    for (Map.Entry<String, ChannelWrapper> entry : channelTables.entrySet()) {
                        String key = entry.getKey();
                        ChannelWrapper prev = entry.getValue();
                        if (prev.getChannel() != null) {
                            if (prev.getChannel() == channel) {
                                prevCW = prev;
                                addrRemote = key;
                                break;
                            }
                        }
                    }

                    if (null == prevCW) {
                        log.info("eventCloseChannel: the channel[{}] has been removed from the channel table before", addrRemote);
                        removeItemFromTable = false;
                    }

                    if (removeItemFromTable) {
                        this.channelTables.remove(addrRemote);
                        log.info("closeChannel: the channel[{}] was removed from channel table", addrRemote);
                        RemotingUtil.closeChannel(channel);
                    }
                } catch (Exception e) {
                    log.error("closeChannel: close the channel exception", e);
                } finally {
                    this.lockChannelTables.unlock();
                }
            } else {
                log.warn("closeChannel: try to lock channel table, but timeout, {}ms", LOCK_TIMEOUT_MILLIS);
            }
        } catch (InterruptedException e) {
            log.error("closeChannel exception", e);
        }
    }

    // 更新namesrv地址列表
    @Override
    public void updateNameServerAddressList(List<String> addrs) {
        List<String> old = this.namesrvAddrList.get();
        // 标记传入的地址列表和当前的是否不同
        boolean update = false;

        if (!addrs.isEmpty()) {
            if (null == old) {
                update = true;
            } else if (addrs.size() != old.size()) {
                update = true;
            } else {
                for (int i = 0; i < addrs.size() && !update; i++) {
                    if (!old.contains(addrs.get(i))) {
                        update = true;
                    }
                }
            }

            if (update) {
                // 将list的元素顺序随机打乱
                Collections.shuffle(addrs);
                log.info("name server address updated. NEW : {} , OLD: {}", addrs, old);
                // 更新namesrv地址列表
                this.namesrvAddrList.set(addrs);
            }
        }
    }

    // 同步发送请求
    @Override
    public RemotingCommand invokeSync(String addr, final RemotingCommand request, long timeoutMillis)
        throws InterruptedException, RemotingConnectException, RemotingSendRequestException, RemotingTimeoutException {
        long beginStartTime = System.currentTimeMillis();
        // 首先获取地址对应的channel，如果没有则创建一个，getAndCreateChannel方法会在channel的connect操作完成之前同步等待
        final Channel channel = this.getAndCreateChannel(addr);
        if (channel != null && channel.isActive()) {
            try {
                // 发送请求前调用RpcHook类的doBeforeRequest方法
                doBeforeRpcHooks(addr, request);
                // 减去connect花掉的时间
                long costTime = System.currentTimeMillis() - beginStartTime;
                if (timeoutMillis < costTime) {
                    throw new RemotingTimeoutException("invokeSync call timeout");
                }
                // 直接调用父类的实现即可
                RemotingCommand response = this.invokeSyncImpl(channel, request, timeoutMillis - costTime);
                // 发送请求后调用RpcHook类的doAfterResponse方法
                doAfterRpcHooks(RemotingHelper.parseChannelRemoteAddr(channel), request, response);
                return response;
            } catch (RemotingSendRequestException e) {
                log.warn("invokeSync: send request exception, so close the channel[{}]", addr);
                this.closeChannel(addr, channel);
                throw e;
            } catch (RemotingTimeoutException e) {
                if (nettyClientConfig.isClientCloseSocketIfTimeout()) {
                    this.closeChannel(addr, channel);
                    log.warn("invokeSync: close socket because of timeout, {}ms, {}", timeoutMillis, addr);
                }
                log.warn("invokeSync: wait response timeout exception, the channel[{}]", addr);
                throw e;
            }
        } else {
            // 创建channel失败或channel的connect操作失败则抛出异常
            this.closeChannel(addr, channel);
            throw new RemotingConnectException(addr);
        }
    }

    // 创建一个连向指定地址的Channel对象，或获取已经创建过的连向了指定地址的Channel对象
    private Channel getAndCreateChannel(final String addr) throws RemotingConnectException, InterruptedException {
        if (null == addr) {
            // 如果没有指定地址则从namesrvAddrList中轮询选择一个namesrv的地址并创建连向改地址的Channel并保存选择结果，或返回之前已经
            // 选择的namesrv的channel
            return getAndCreateNameserverChannel();
        }

        // 从缓存中获取地址对应的channel
        ChannelWrapper cw = this.channelTables.get(addr);
        if (cw != null && cw.isOK()) {
            return cw.getChannel();
        }

        // 未获取到则创建一个，下面的方法会创建一个新的channel并同步等待connect事件完成，如果connect超时或失败则返回null
        return this.createChannel(addr);
    }

    // 选择一个namesrv并创建对应的channel
    private Channel getAndCreateNameserverChannel() throws RemotingConnectException, InterruptedException {
        // 获取之前选择的namesrv地址
        String addr = this.namesrvAddrChoosed.get();
        if (addr != null) {
            // 获取改地址对应的ChannelWrapper，该对象持有ChannelFuture，能够获取channel对象
            ChannelWrapper cw = this.channelTables.get(addr);
            if (cw != null && cw.isOK()) {
                return cw.getChannel();
            }
        }

        // 如果之前没有选择过则选择一个
        final List<String> addrList = this.namesrvAddrList.get();
        // 为了线程安全，先加锁
        if (this.lockNamesrvChannel.tryLock(LOCK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            try {
                // double check
                addr = this.namesrvAddrChoosed.get();
                if (addr != null) {
                    ChannelWrapper cw = this.channelTables.get(addr);
                    if (cw != null && cw.isOK()) {
                        return cw.getChannel();
                    }
                }


                if (addrList != null && !addrList.isEmpty()) {
                    for (int i = 0; i < addrList.size(); i++) {
                        // namesrvIndex的初始值是0-998的随机数
                        int index = this.namesrvIndex.incrementAndGet();
                        // 简单的轮询选择
                        index = Math.abs(index);
                        index = index % addrList.size();
                        String newAddr = addrList.get(index);

                        this.namesrvAddrChoosed.set(newAddr);
                        log.info("new name server is chosen. OLD: {} , NEW: {}. namesrvIndex = {}", addr, newAddr, namesrvIndex);
                        // 创建或获取channel并同步等待connect操作完成
                        Channel channelNew = this.createChannel(newAddr);
                        // 当connect超时或失败时createChannel方法返回空，此时只能轮询下一个namesrv
                        if (channelNew != null) {
                            return channelNew;
                        }
                    }
                    // 所有的namesrv的channel都创建失败或connect失败则抛出异常
                    throw new RemotingConnectException(addrList.toString());
                }
            } finally {
                this.lockNamesrvChannel.unlock();
            }
        } else {
            log.warn("getAndCreateNameserverChannel: try to lock name server, but timeout, {}ms", LOCK_TIMEOUT_MILLIS);
        }

        return null;
    }

    // 创建channel并同步等待connect事件完成，如果connect超时或失败则返回null
    private Channel createChannel(final String addr) throws InterruptedException {
        // 存在可用的直接返回
        ChannelWrapper cw = this.channelTables.get(addr);
        // 判断ChannelWrapper中的channel是否是active的
        if (cw != null && cw.isOK()) {
            return cw.getChannel();
        }

        // 加锁保证线程安全
        if (this.lockChannelTables.tryLock(LOCK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            try {
                // 是否需要创建一个新的channel
                boolean createNewConnection;
                cw = this.channelTables.get(addr);
                // double check
                if (cw != null) {
                    if (cw.isOK()) {
                        return cw.getChannel();
                    } else if (!cw.getChannelFuture().isDone()) {
                        // channelFuture对应的是创建channel时connect方法的回调对象，这里判断是否是channel已经创建了但是connect还
                        // 未完成的情况，这种情况没必要创建了
                        createNewConnection = false;
                    } else {
                        // 不满足上面的条件说明是connect操作完成但channel未active，此时创建一个新的channel
                        this.channelTables.remove(addr);
                        createNewConnection = true;
                    }
                } else {
                    createNewConnection = true;
                }

                // 创建channel
                if (createNewConnection) {
                    ChannelFuture channelFuture = this.bootstrap.connect(RemotingHelper.string2SocketAddress(addr));
                    log.info("createChannel: begin to connect remote host[{}] asynchronously", addr);
                    // 保存channel到ChannelWrapper便于之后查询channel的状态及connect操作状态
                    cw = new ChannelWrapper(channelFuture);
                    // 缓存ChannelWrapper对象
                    this.channelTables.put(addr, cw);
                }
            } catch (Exception e) {
                log.error("createChannel: create channel exception", e);
            } finally {
                this.lockChannelTables.unlock();
            }
        } else {
            log.warn("createChannel: try to lock channel table, but timeout, {}ms", LOCK_TIMEOUT_MILLIS);
        }

        if (cw != null) {
            // 同步等待channel的连接完成，如果已经连接完成则awaitUninterruptibly方法会直接返回
            ChannelFuture channelFuture = cw.getChannelFuture();
            if (channelFuture.awaitUninterruptibly(this.nettyClientConfig.getConnectTimeoutMillis())) {
                if (cw.isOK()) {
                    log.info("createChannel: connect remote host[{}] success, {}", addr, channelFuture.toString());
                    return cw.getChannel();
                } else {
                    // 操作完成但是操作失败
                    log.warn("createChannel: connect remote host[" + addr + "] failed, " + channelFuture.toString(), channelFuture.cause());
                }
            } else {
                // 等待超时记录日志
                log.warn("createChannel: connect remote host[{}] timeout {}ms, {}", addr, this.nettyClientConfig.getConnectTimeoutMillis(),
                    channelFuture.toString());
            }
        }

        return null;
    }

    // 异步发送请求，和invokeSync方法的实现差不多
    @Override
    public void invokeAsync(String addr, RemotingCommand request, long timeoutMillis, InvokeCallback invokeCallback)
        throws InterruptedException, RemotingConnectException, RemotingTooMuchRequestException, RemotingTimeoutException,
        RemotingSendRequestException {
        long beginStartTime = System.currentTimeMillis();
        // netty的实现保证了调用Channel的方法是线程安全的，所以这里直接获取之前创建的channel即可
        final Channel channel = this.getAndCreateChannel(addr);
        if (channel != null && channel.isActive()) {
            try {
                // 异步发送请求，所以没有调用doAfterRpcHooks方法
                doBeforeRpcHooks(addr, request);
                long costTime = System.currentTimeMillis() - beginStartTime;
                // timeoutMillis < costTime说明connect操作花的时间太多了，channel是线程安全的，对于一个确定的channel，其读写操作
                // 在netty内部只有一个确定的线程执行
                if (timeoutMillis < costTime) {
                    throw new RemotingTooMuchRequestException("invokeAsync call timeout");
                }
                this.invokeAsyncImpl(channel, request, timeoutMillis - costTime, invokeCallback);
            } catch (RemotingSendRequestException e) {
                log.warn("invokeAsync: send request exception, so close the channel[{}]", addr);
                this.closeChannel(addr, channel);
                throw e;
            }
        } else {
            this.closeChannel(addr, channel);
            throw new RemotingConnectException(addr);
        }
    }

    // 只发送请求，不关心响应
    @Override
    public void invokeOneway(String addr, RemotingCommand request, long timeoutMillis) throws InterruptedException,
        RemotingConnectException, RemotingTooMuchRequestException, RemotingTimeoutException, RemotingSendRequestException {
        final Channel channel = this.getAndCreateChannel(addr);
        if (channel != null && channel.isActive()) {
            try {
                // oneway异步发送请求，所以没有调用doAfterRpcHooks方法
                doBeforeRpcHooks(addr, request);
                this.invokeOnewayImpl(channel, request, timeoutMillis);
            } catch (RemotingSendRequestException e) {
                log.warn("invokeOneway: send request exception, so close the channel[{}]", addr);
                this.closeChannel(addr, channel);
                throw e;
            }
        } else {
            this.closeChannel(addr, channel);
            throw new RemotingConnectException(addr);
        }
    }

    @Override
    public void registerProcessor(int requestCode, NettyRequestProcessor processor, ExecutorService executor) {
        ExecutorService executorThis = executor;
        if (null == executor) {
            executorThis = this.publicExecutor;
        }

        Pair<NettyRequestProcessor, ExecutorService> pair = new Pair<NettyRequestProcessor, ExecutorService>(processor, executorThis);
        this.processorTable.put(requestCode, pair);
    }

    @Override
    public boolean isChannelWritable(String addr) {
        ChannelWrapper cw = this.channelTables.get(addr);
        if (cw != null && cw.isOK()) {
            return cw.isWritable();
        }
        return true;
    }

    @Override
    public List<String> getNameServerAddressList() {
        return this.namesrvAddrList.get();
    }

    @Override
    public ChannelEventListener getChannelEventListener() {
        return channelEventListener;
    }

    @Override
    public ExecutorService getCallbackExecutor() {
        return callbackExecutor != null ? callbackExecutor : publicExecutor;
    }

    @Override
    public void setCallbackExecutor(final ExecutorService callbackExecutor) {
        this.callbackExecutor = callbackExecutor;
    }

    // channelFuture指向调用bootstrap.connect方法后的返回值，通过ChannelWrapper对象能够获取bootstrap.connect的结果及对应的
    // Channel对象和Channel对象的状态
    static class ChannelWrapper {
        private final ChannelFuture channelFuture;

        public ChannelWrapper(ChannelFuture channelFuture) {
            this.channelFuture = channelFuture;
        }

        public boolean isOK() {
            return this.channelFuture.channel() != null && this.channelFuture.channel().isActive();
        }

        public boolean isWritable() {
            return this.channelFuture.channel().isWritable();
        }

        private Channel getChannel() {
            return this.channelFuture.channel();
        }

        public ChannelFuture getChannelFuture() {
            return channelFuture;
        }
    }

    class NettyClientHandler extends SimpleChannelInboundHandler<RemotingCommand> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, RemotingCommand msg) throws Exception {
            processMessageReceived(ctx, msg);
        }
    }

    class NettyConnectManageHandler extends ChannelDuplexHandler {
        @Override
        public void connect(ChannelHandlerContext ctx, SocketAddress remoteAddress, SocketAddress localAddress,
            ChannelPromise promise) throws Exception {
            final String local = localAddress == null ? "UNKNOWN" : RemotingHelper.parseSocketAddressAddr(localAddress);
            final String remote = remoteAddress == null ? "UNKNOWN" : RemotingHelper.parseSocketAddressAddr(remoteAddress);
            log.info("NETTY CLIENT PIPELINE: CONNECT  {} => {}", local, remote);

            super.connect(ctx, remoteAddress, localAddress, promise);

            if (NettyRemotingClient.this.channelEventListener != null) {
                NettyRemotingClient.this.putNettyEvent(new NettyEvent(NettyEventType.CONNECT, remote, ctx.channel()));
            }
        }

        @Override
        public void disconnect(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
            final String remoteAddress = RemotingHelper.parseChannelRemoteAddr(ctx.channel());
            log.info("NETTY CLIENT PIPELINE: DISCONNECT {}", remoteAddress);
            closeChannel(ctx.channel());
            super.disconnect(ctx, promise);

            if (NettyRemotingClient.this.channelEventListener != null) {
                NettyRemotingClient.this.putNettyEvent(new NettyEvent(NettyEventType.CLOSE, remoteAddress, ctx.channel()));
            }
        }

        @Override
        public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
            final String remoteAddress = RemotingHelper.parseChannelRemoteAddr(ctx.channel());
            log.info("NETTY CLIENT PIPELINE: CLOSE {}", remoteAddress);
            closeChannel(ctx.channel());
            super.close(ctx, promise);
            NettyRemotingClient.this.failFast(ctx.channel());
            if (NettyRemotingClient.this.channelEventListener != null) {
                NettyRemotingClient.this.putNettyEvent(new NettyEvent(NettyEventType.CLOSE, remoteAddress, ctx.channel()));
            }
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt instanceof IdleStateEvent) {
                IdleStateEvent event = (IdleStateEvent) evt;
                if (event.state().equals(IdleState.ALL_IDLE)) {
                    final String remoteAddress = RemotingHelper.parseChannelRemoteAddr(ctx.channel());
                    log.warn("NETTY CLIENT PIPELINE: IDLE exception [{}]", remoteAddress);
                    closeChannel(ctx.channel());
                    if (NettyRemotingClient.this.channelEventListener != null) {
                        NettyRemotingClient.this
                            .putNettyEvent(new NettyEvent(NettyEventType.IDLE, remoteAddress, ctx.channel()));
                    }
                }
            }

            ctx.fireUserEventTriggered(evt);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            final String remoteAddress = RemotingHelper.parseChannelRemoteAddr(ctx.channel());
            log.warn("NETTY CLIENT PIPELINE: exceptionCaught {}", remoteAddress);
            log.warn("NETTY CLIENT PIPELINE: exceptionCaught exception.", cause);
            closeChannel(ctx.channel());
            if (NettyRemotingClient.this.channelEventListener != null) {
                NettyRemotingClient.this.putNettyEvent(new NettyEvent(NettyEventType.EXCEPTION, remoteAddress, ctx.channel()));
            }
        }
    }
}
```

## 实现

### `start()`和`shutdown()`方法
`NettyRemotingClient`类使用netty完成端口监听，所以`start()`方法主要工作是完成netty的`Bootstrap`对象的创建和启动。需要注意的是`NettyRemotingClient`作为客户端的实现，其主要用于发送请求，所以在`start()`方法中只是配置`Bootstrap`而没有调用`bootstrap.connect`创建连接，因为启动时还没有发送请求的需求，`bootstrap.connect`是在需要的时候调用的，具体可以看后面的分析。下面是`start()`和`shutdown()`方法的代码：
```java
@Override
public void start() {
    // channel事件的工作线程
    this.defaultEventExecutorGroup = new DefaultEventExecutorGroup(
        nettyClientConfig.getClientWorkerThreads(),
        new ThreadFactory() {

            private AtomicInteger threadIndex = new AtomicInteger(0);

            @Override
            public Thread newThread(Runnable r) {
                return new Thread(r, "NettyClientWorkerThread_" + this.threadIndex.incrementAndGet());
            }
        });

    // 下面的配置在NettyRemotingServer中已经介绍过了，这里不再赘述，需要注意这里没有调用handler的connect方法创建channel，因为现
    // 在还没有发送请求的需求，关于channel的创建，看invokeSync等方法的实现
    Bootstrap handler = this.bootstrap.group(this.eventLoopGroupWorker).channel(NioSocketChannel.class)
        .option(ChannelOption.TCP_NODELAY, true)
        .option(ChannelOption.SO_KEEPALIVE, false)
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, nettyClientConfig.getConnectTimeoutMillis())
        .option(ChannelOption.SO_SNDBUF, nettyClientConfig.getClientSocketSndBufSize())
        .option(ChannelOption.SO_RCVBUF, nettyClientConfig.getClientSocketRcvBufSize())
        .handler(new ChannelInitializer<SocketChannel>() {
            @Override
            public void initChannel(SocketChannel ch) throws Exception {
                ChannelPipeline pipeline = ch.pipeline();
                if (nettyClientConfig.isUseTLS()) {
                    if (null != sslContext) {
                        pipeline.addFirst(defaultEventExecutorGroup, "sslHandler", sslContext.newHandler(ch.alloc()));
                        log.info("Prepend SSL handler");
                    } else {
                        log.warn("Connections are insecure as SSLContext is null!");
                    }
                }
                pipeline.addLast(
                    defaultEventExecutorGroup,
                    new NettyEncoder(),
                    new NettyDecoder(),
                    new IdleStateHandler(0, 0, nettyClientConfig.getClientChannelMaxIdleTimeSeconds()),
                    new NettyConnectManageHandler(),
                    new NettyClientHandler());
            }
        });

    this.timer.scheduleAtFixedRate(new TimerTask() {
        @Override
        public void run() {
            try {
                NettyRemotingClient.this.scanResponseTable();
            } catch (Throwable e) {
                log.error("scanResponseTable exception", e);
            }
        }
    }, 1000 * 3, 1000);

    if (this.channelEventListener != null) {
        this.nettyEventExecutor.start();
    }
}

@Override
public void shutdown() {
    try {
        this.timer.cancel();

        // 关闭所有的channel
        for (ChannelWrapper cw : this.channelTables.values()) {
            this.closeChannel(null, cw.getChannel());
        }

        this.channelTables.clear();

        this.eventLoopGroupWorker.shutdownGracefully();

        if (this.nettyEventExecutor != null) {
            this.nettyEventExecutor.shutdown();
        }

        if (this.defaultEventExecutorGroup != null) {
            this.defaultEventExecutorGroup.shutdownGracefully();
        }
    } catch (Exception e) {
        log.error("NettyRemotingClient shutdown exception, ", e);
    }

    if (this.publicExecutor != null) {
        try {
            this.publicExecutor.shutdown();
        } catch (Exception e) {
            log.error("NettyRemotingServer shutdown exception, ", e);
        }
    }
}
```

### `updateNameServerAddressList()`和`getNameServerAddressList()`方法
RocketMQ中的客户端需要从namesrv获取topic和broker的信息，`updateNameServerAddressList()`方法和`getNameServerAddressList()`方法分别负责可用的namesrv地址的更新和获取：
```java
// 更新namesrv地址列表
@Override
public void updateNameServerAddressList(List<String> addrs) {
    List<String> old = this.namesrvAddrList.get();
    // 标记传入的地址列表和当前的是否不同
    boolean update = false;

    if (!addrs.isEmpty()) {
        if (null == old) {
            update = true;
        } else if (addrs.size() != old.size()) {
            update = true;
        } else {
            for (int i = 0; i < addrs.size() && !update; i++) {
                if (!old.contains(addrs.get(i))) {
                    update = true;
                }
            }
        }

        if (update) {
            // 将list的元素顺序随机打乱
            Collections.shuffle(addrs);
            log.info("name server address updated. NEW : {} , OLD: {}", addrs, old);
            // 更新namesrv地址列表
            this.namesrvAddrList.set(addrs);
        }
    }
}

@Override
public List<String> getNameServerAddressList() {
    return this.namesrvAddrList.get();
}
``` 

#### `invokeSync()`、`invokeAsync()`和`invokeOneway()`方法
这3个方法分别负责同步、异步和oneway的方式发送请求。这3个方法其实都是调用的父类的相应实现方法，只不过加上了`doBeforeRpcHooks()`和`doAfterRpcHooks()`方法在发送请求前后执行回调。需要注意的是只有`invokeSync()`方法同时调用了`doBeforeRpcHooks()`和`doAfterRpcHooks()`方法，因为该方法是同步发送请求的，而`invokeAsync()`和`invokeOneway()`方法都是异步发送，所以只调用了`doBeforeRpcHooks()`方法。

作为客户端，发送请求必然需要创建一个连接，这一步在netty中就是调用`bootstrap.connect`创建`Channel`对象，创建并获取`Channel`对象的过程`invokeSync()`、`invokeAsync()`和`invokeOneway()`方法使用的是同一个方法`getAndCreateChannel()`，对于`getAndCreateChannel()`方法的实现，可以看后面的分析。
```java
// 同步发送请求
@Override
public RemotingCommand invokeSync(String addr, final RemotingCommand request, long timeoutMillis)
    throws InterruptedException, RemotingConnectException, RemotingSendRequestException, RemotingTimeoutException {
    long beginStartTime = System.currentTimeMillis();
    // 首先获取地址对应的channel，如果没有则创建一个，getAndCreateChannel方法会在channel的connect操作完成之前同步等待
    final Channel channel = this.getAndCreateChannel(addr);
    if (channel != null && channel.isActive()) {
        try {
            // 发送请求前调用RpcHook类的doBeforeRequest方法
            doBeforeRpcHooks(addr, request);
            // 减去connect花掉的时间
            long costTime = System.currentTimeMillis() - beginStartTime;
            if (timeoutMillis < costTime) {
                throw new RemotingTimeoutException("invokeSync call timeout");
            }
            // 直接调用父类的实现即可
            RemotingCommand response = this.invokeSyncImpl(channel, request, timeoutMillis - costTime);
            // 发送请求后调用RpcHook类的doAfterResponse方法
            doAfterRpcHooks(RemotingHelper.parseChannelRemoteAddr(channel), request, response);
            return response;
        } catch (RemotingSendRequestException e) {
            log.warn("invokeSync: send request exception, so close the channel[{}]", addr);
            this.closeChannel(addr, channel);
            throw e;
        } catch (RemotingTimeoutException e) {
            if (nettyClientConfig.isClientCloseSocketIfTimeout()) {
                this.closeChannel(addr, channel);
                log.warn("invokeSync: close socket because of timeout, {}ms, {}", timeoutMillis, addr);
            }
            log.warn("invokeSync: wait response timeout exception, the channel[{}]", addr);
            throw e;
        }
    } else {
        // 创建channel失败或channel的connect操作失败则抛出异常
        this.closeChannel(addr, channel);
        throw new RemotingConnectException(addr);
    }
}

// 异步发送请求，和invokeSync方法的实现差不多
@Override
public void invokeAsync(String addr, RemotingCommand request, long timeoutMillis, InvokeCallback invokeCallback)
    throws InterruptedException, RemotingConnectException, RemotingTooMuchRequestException, RemotingTimeoutException,
    RemotingSendRequestException {
    long beginStartTime = System.currentTimeMillis();
    // netty的实现保证了调用Channel的方法是线程安全的，所以这里直接获取之前创建的channel即可
    final Channel channel = this.getAndCreateChannel(addr);
    if (channel != null && channel.isActive()) {
        try {
            // 异步发送请求，所以没有调用doAfterRpcHooks方法
            doBeforeRpcHooks(addr, request);
            long costTime = System.currentTimeMillis() - beginStartTime;
            // timeoutMillis < costTime说明connect操作花的时间太多了，channel是线程安全的，对于一个确定的channel，其读写操作
            // 在netty内部只有一个确定的线程执行
            if (timeoutMillis < costTime) {
                throw new RemotingTooMuchRequestException("invokeAsync call timeout");
            }
            this.invokeAsyncImpl(channel, request, timeoutMillis - costTime, invokeCallback);
        } catch (RemotingSendRequestException e) {
            log.warn("invokeAsync: send request exception, so close the channel[{}]", addr);
            this.closeChannel(addr, channel);
            throw e;
        }
    } else {
        this.closeChannel(addr, channel);
        throw new RemotingConnectException(addr);
    }
}

// 只发送请求，不关心响应
@Override
public void invokeOneway(String addr, RemotingCommand request, long timeoutMillis) throws InterruptedException,
    RemotingConnectException, RemotingTooMuchRequestException, RemotingTimeoutException, RemotingSendRequestException {
    final Channel channel = this.getAndCreateChannel(addr);
    if (channel != null && channel.isActive()) {
        try {
            // oneway异步发送请求，所以没有调用doAfterRpcHooks方法
            doBeforeRpcHooks(addr, request);
            this.invokeOnewayImpl(channel, request, timeoutMillis);
        } catch (RemotingSendRequestException e) {
            log.warn("invokeOneway: send request exception, so close the channel[{}]", addr);
            this.closeChannel(addr, channel);
            throw e;
        }
    } else {
        this.closeChannel(addr, channel);
        throw new RemotingConnectException(addr);
    }
}
```

#### `getAndCreateChannel()`方法
该方法创建一个连向指定地址的`Channel`对象，或获取已经创建过的连向了指定地址的`Channel`对象，通过`Channel`对象就能发送请求了：
```java
private Channel getAndCreateChannel(final String addr) throws RemotingConnectException, InterruptedException {
    if (null == addr) {
        // 如果没有指定地址则从namesrvAddrList中轮询选择一个namesrv的地址并创建连向改地址的Channel并保存选择结果，或返回之前已经
        // 选择的namesrv的channel
        return getAndCreateNameserverChannel();
    }

    // 从缓存中获取地址对应的channel
    ChannelWrapper cw = this.channelTables.get(addr);
    if (cw != null && cw.isOK()) {
        return cw.getChannel();
    }

    // 未获取到则创建一个，下面的方法会创建一个新的channel并同步等待connect事件完成，如果connect超时或失败则返回null
    return this.createChannel(addr);
}
```

上面用到的`getAndCreateNameserverChannel()`方法如注释所说就不分析了，重点在于`createChannel()`方法，在调用该方法之前，会从缓存中获取`ChannelWrapper`对象，该对象的作用是保存`bootstrap.connect`方法的执行结果并持有对应的`Channel`对象，该类定义如下：
```java
// channelFuture指向调用bootstrap.connect方法后的返回值，通过ChannelWrapper对象能够获取bootstrap.connect的结果及对应的
// Channel对象和Channel对象的状态
static class ChannelWrapper {
    private final ChannelFuture channelFuture;

    public ChannelWrapper(ChannelFuture channelFuture) {
        this.channelFuture = channelFuture;
    }

    public boolean isOK() {
        return this.channelFuture.channel() != null && this.channelFuture.channel().isActive();
    }

    public boolean isWritable() {
        return this.channelFuture.channel().isWritable();
    }

    private Channel getChannel() {
        return this.channelFuture.channel();
    }

    public ChannelFuture getChannelFuture() {
        return channelFuture;
    }
}
```

如果缓存中没有结果，则调用`createChannel()`方法创建一个，该方法调用`bootstrap.connect`方法连接指定地址并以返回的`ChannelFuture`对象创建`ChannelWrapper`对象，再将`ChannelWrapper`对象保存到缓存中，这样对于同一个地址的`Channel`对象就可以复用，netty的实现保证了`Channel`对象的线程安全。在调用`bootstrap.connect`方法之后，`createChannel()`方法还会同步等待操作完成，所以正常情况下`createChannel()`方法返回后，其返回的`Channel`对象就可用于发送请求，`createChannel()`方法代码如下：
```java
// 创建channel并同步等待connect事件完成，如果connect超时或失败则返回null
private Channel createChannel(final String addr) throws InterruptedException {
    // 存在可用的直接返回
    ChannelWrapper cw = this.channelTables.get(addr);
    // 判断ChannelWrapper中的channel是否是active的
    if (cw != null && cw.isOK()) {
        return cw.getChannel();
    }

    // 加锁保证线程安全
    if (this.lockChannelTables.tryLock(LOCK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
        try {
            // 是否需要创建一个新的channel
            boolean createNewConnection;
            cw = this.channelTables.get(addr);
            // double check
            if (cw != null) {
                if (cw.isOK()) {
                    return cw.getChannel();
                } else if (!cw.getChannelFuture().isDone()) {
                    // channelFuture对应的是创建channel时connect方法的回调对象，这里判断是否是channel已经创建了但是connect还
                    // 未完成的情况，这种情况没必要创建了
                    createNewConnection = false;
                } else {
                    // 不满足上面的条件说明是connect操作完成但channel未active，此时创建一个新的channel
                    this.channelTables.remove(addr);
                    createNewConnection = true;
                }
            } else {
                createNewConnection = true;
            }

            // 创建channel
            if (createNewConnection) {
                ChannelFuture channelFuture = this.bootstrap.connect(RemotingHelper.string2SocketAddress(addr));
                log.info("createChannel: begin to connect remote host[{}] asynchronously", addr);
                // 保存channel到ChannelWrapper便于之后查询channel的状态及connect操作状态
                cw = new ChannelWrapper(channelFuture);
                // 缓存ChannelWrapper对象
                this.channelTables.put(addr, cw);
            }
        } catch (Exception e) {
            log.error("createChannel: create channel exception", e);
        } finally {
            this.lockChannelTables.unlock();
        }
    } else {
        log.warn("createChannel: try to lock channel table, but timeout, {}ms", LOCK_TIMEOUT_MILLIS);
    }

    if (cw != null) {
        // 同步等待channel的连接完成，如果已经连接完成则awaitUninterruptibly方法会直接返回
        ChannelFuture channelFuture = cw.getChannelFuture();
        if (channelFuture.awaitUninterruptibly(this.nettyClientConfig.getConnectTimeoutMillis())) {
            if (cw.isOK()) {
                log.info("createChannel: connect remote host[{}] success, {}", addr, channelFuture.toString());
                return cw.getChannel();
            } else {
                // 操作完成但是操作失败
                log.warn("createChannel: connect remote host[" + addr + "] failed, " + channelFuture.toString(), channelFuture.cause());
            }
        } else {
            // 等待超时记录日志
            log.warn("createChannel: connect remote host[{}] timeout {}ms, {}", addr, this.nettyClientConfig.getConnectTimeoutMillis(),
                channelFuture.toString());
        }
    }

    return null;
}
```

### 其他方法
```java
@Override
public void registerProcessor(int requestCode, NettyRequestProcessor processor, ExecutorService executor) {
    ExecutorService executorThis = executor;
    if (null == executor) {
        executorThis = this.publicExecutor;
    }

    Pair<NettyRequestProcessor, ExecutorService> pair = new Pair<NettyRequestProcessor, ExecutorService>(processor, executorThis);
    this.processorTable.put(requestCode, pair);
}

@Override
public void setCallbackExecutor(final ExecutorService callbackExecutor) {
    this.callbackExecutor = callbackExecutor;
}

@Override
public boolean isChannelWritable(String addr) {
    ChannelWrapper cw = this.channelTables.get(addr);
    if (cw != null && cw.isOK()) {
        return cw.isWritable();
    }
    return true;
}

@Override
public void registerRPCHook(RPCHook rpcHook) {
    if (rpcHook != null && !rpcHooks.contains(rpcHook)) {
        rpcHooks.add(rpcHook);
    }
}

@Override
public ChannelEventListener getChannelEventListener() {
    return channelEventListener;
}

@Override
public ExecutorService getCallbackExecutor() {
    return callbackExecutor != null ? callbackExecutor : publicExecutor;
}
```