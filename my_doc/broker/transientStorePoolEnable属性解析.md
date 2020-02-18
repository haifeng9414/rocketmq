`transientStorePoolEnable`属性的作用是，broker是master并且采用异步刷盘的情况下，其写入消息时会单独申请一个与目标物理文件（commitlog）同样大小的堆外内存。消息首先追加到堆外内存，然后commit到与物理文件的内存映射内存中，再flush到磁盘（相当于“读写分离”）。如果`transientStorePoolEnable`为flalse ，消息直接追加到与物理文件直接映射的内存中（`MappedByteBuffer`），然后刷写到磁盘中。

下面分析`transientStorePoolEnable`属性为true带来的好处，及开启后broker如何处理相关逻辑的，下面假设已经看过笔记[如何实现消息存储](如何实现消息存储.md)，跳过必要的前景介绍。

保存消息的逻辑在`MappedFile`类，`MappedFile`对象的创建在`AllocateMappedFileService`类的`mmapOperation()`方法：
```java
private boolean mmapOperation() {
    boolean isSuccess = false;
    AllocateRequest req = null;
    try {
        // 略

        if (req.getMappedFile() == null) {
            // 开始创建commitlog文件
            long beginTime = System.currentTimeMillis();

            MappedFile mappedFile;
            // 如果开启了"读写分离"模式
            if (messageStore.getMessageStoreConfig().isTransientStorePoolEnable()) {
                try {
                    // spi，如果在META-INF/services目录下有org.apache.rocketmq.store.MappedFile文件，则以该文件中的
                    // 类为MappedFile实现类
                    mappedFile = ServiceLoader.load(MappedFile.class).iterator().next();
                    mappedFile.init(req.getFilePath(), req.getFileSize(), messageStore.getTransientStorePool());
                } catch (RuntimeException e) {
                    log.warn("Use default implementation.");
                    // 找不到实现类时使用默认实现
                    mappedFile = new MappedFile(req.getFilePath(), req.getFileSize(), messageStore.getTransientStorePool());
                }
            } else {
                mappedFile = new MappedFile(req.getFilePath(), req.getFileSize());
            }

            // 略
        }
    }
    // 略
}
```

创建`MappedFile`对象时首先判断`messageStore.getMessageStoreConfig().isTransientStorePoolEnable()`是否为true，该代码：
```java
public boolean isTransientStorePoolEnable() {
    return transientStorePoolEnable && FlushDiskType.ASYNC_FLUSH == getFlushDiskType()
        && BrokerRole.SLAVE != getBrokerRole();
}
```

上面的方法在`transientStorePoolEnable`属性为true并且broker是master并且采用异步刷盘的情况下返回true，此时创建`MappedFile`对象的逻辑是：
```java
mappedFile = new MappedFile(req.getFilePath(), req.getFileSize(), messageStore.getTransientStorePool());
```

`messageStore.getTransientStorePool()`返回的是`TransientStorePool`对象，该对象为堆外内存池，存储的是最开始说的写入消息时申请的堆外内存，broker启动时堆外内存池会初始化固定个数的堆外内存供`MappedFile`使用，`TransientStorePool`对象的初始化在`DefaultMessageStore`类的构造方法：
```java
public DefaultMessageStore(final MessageStoreConfig messageStoreConfig, final BrokerStatsManager brokerStatsManager,
    final MessageArrivingListener messageArrivingListener, final BrokerConfig brokerConfig) throws IOException {
    // 略

    this.transientStorePool = new TransientStorePool(messageStoreConfig);

    if (messageStoreConfig.isTransientStorePoolEnable()) {
        this.transientStorePool.init();
    }
    
    // 略
}
```
这里再看`TransientStorePool`类的实现：
```java
public class TransientStorePool {
    private static final InternalLogger log = InternalLoggerFactory.getLogger(LoggerName.STORE_LOGGER_NAME);

    // poolSize表示创建的堆外内存数量，默认等于5
    private final int poolSize;
    // 保存commitlog文件的大小，默认1G
    private final int fileSize;
    // 保存可用的堆外内存
    private final Deque<ByteBuffer> availableBuffers;
    private final MessageStoreConfig storeConfig;

    public TransientStorePool(final MessageStoreConfig storeConfig) {
        this.storeConfig = storeConfig;
        this.poolSize = storeConfig.getTransientStorePoolSize();
        this.fileSize = storeConfig.getMappedFileSizeCommitLog();
        this.availableBuffers = new ConcurrentLinkedDeque<>();
    }

    /**
     * It's a heavy init method.
     */
    public void init() {
        for (int i = 0; i < poolSize; i++) {
            // 申请堆外内存
            ByteBuffer byteBuffer = ByteBuffer.allocateDirect(fileSize);

            // mlock的作用在MappedFile类的mlock方法中介绍了
            final long address = ((DirectBuffer) byteBuffer).address();
            Pointer pointer = new Pointer(address);
            LibC.INSTANCE.mlock(pointer, new NativeLong(fileSize));

            availableBuffers.offer(byteBuffer);
        }
    }

    public void destroy() {
        for (ByteBuffer byteBuffer : availableBuffers) {
            final long address = ((DirectBuffer) byteBuffer).address();
            Pointer pointer = new Pointer(address);
            LibC.INSTANCE.munlock(pointer, new NativeLong(fileSize));
        }
    }

    public void returnBuffer(ByteBuffer byteBuffer) {
        byteBuffer.position(0);
        byteBuffer.limit(fileSize);
        this.availableBuffers.offerFirst(byteBuffer);
    }

    public ByteBuffer borrowBuffer() {
        ByteBuffer buffer = availableBuffers.pollFirst();
        if (availableBuffers.size() < poolSize * 0.4) {
            log.warn("TransientStorePool only remain {} sheets.", availableBuffers.size());
        }
        return buffer;
    }

    public int availableBufferNums() {
        if (storeConfig.isTransientStorePoolEnable()) {
            return availableBuffers.size();
        }
        return Integer.MAX_VALUE;
    }
}
```

`TransientStorePool`类的实现很简单，在`init()`方法中初始化固定数量的堆外内存，并保存到双向队列中，提供`borrowBuffer()`和`returnBuffer()`方法实现从队列获取和返还堆外内存，`MappedFile`类的构造函数就调用了`borrowBuffer()`方法从`TransientStorePool`对象获取堆外内存：
```java
public void init(final String fileName, final int fileSize,
    final TransientStorePool transientStorePool) throws IOException {
    init(fileName, fileSize);
    // 如果传入了transientStorePool则表示采用"读写分离"的模式，这里从transientStorePool获取一个用于写入消息的buffer
    this.writeBuffer = transientStorePool.borrowBuffer();
    this.transientStorePool = transientStorePool;
}
```

归还堆外内存的地方在`MappedFile`对象的`commit()`方法：
```java
public int commit(final int commitLeastPages) {
    // writeBuffer为空表示没有开启"读写分离"模式，直接返回即可
    if (writeBuffer == null) {
        //no need to commit data to file channel, so just regard wrotePosition as committedPosition.
        return this.wrotePosition.get();
    }
    // 判断是否需要执行commit，当commitLeastPages大于0时，至少有commitLeastPages页（默认每页4K）的数据没有commit的时候
    // isAbleToCommit方法返回true，当commitLeastPages等于0时，如果存在未被commit的数据则返回true
    if (this.isAbleToCommit(commitLeastPages)) {
        // MappedFile类继承自ReferenceResource，这里增加被引用次数
        if (this.hold()) {
            // 将writeBuffer的数据写入到fileChannel
            commit0(commitLeastPages);
            // 释放引用
            this.release();
        } else {
            log.warn("in commit, hold failed, commit offset = " + this.committedPosition.get());
        }
    }

    // All dirty data has been committed to FileChannel.
    // 如果committedPosition和fileSize相等，说明当前MappedFile文件已经被写完了，此时将writeBuffer还给buffer池
    if (writeBuffer != null && this.transientStorePool != null && this.fileSize == this.committedPosition.get()) {
        this.transientStorePool.returnBuffer(writeBuffer);
        this.writeBuffer = null;
    }

    return this.committedPosition.get();
}
```

关于`MappedFile`对象的`commit()`方法的调用实际，已经在笔记[如何实现消息存储](如何实现消息存储.md)中关于`CommitRealTimeService`类的实现分析了，这里不再赘述。

下面再看开启“读写分离”后，`MappedFile`对象是如何写入消息的，
