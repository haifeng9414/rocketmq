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

关于`MappedFile`对象的`commit()`方法的调用时机，已经在笔记[如何实现消息存储](如何实现消息存储.md)中关于`CommitRealTimeService`类的实现分析了，这里不再赘述。

下面再看开启“读写分离”后，`MappedFile`对象是如何写入消息的。`MappedFile`对象写入消息的方法是`appendMessage()`方法：
```java
public AppendMessageResult appendMessage(final MessageExtBrokerInner msg, final AppendMessageCallback cb) {
    return appendMessagesInner(msg, cb);
}

public AppendMessageResult appendMessagesInner(final MessageExt messageExt, final AppendMessageCallback cb) {
    assert messageExt != null;
    assert cb != null;

    // 获取上次写入的位置
    int currentPos = this.wrotePosition.get();

    if (currentPos < this.fileSize) {
        // 如果开启了"读写分离"模式则使用写buffer，也就是writeBuffer，否则使用内存映射的buffer
        // slice方法返回一个新的ByteBuffer对象，该对象和原ByteBuffer指向同一个buffer，但是新建
        // 的ByteBuffer的position等于0，capacity等于原ByteBuffer的capacity - position，也就是
        // slice返回剩余可写入的buffer
        ByteBuffer byteBuffer = writeBuffer != null ? writeBuffer.slice() : this.mappedByteBuffer.slice();
        // 每次创建ByteBuffer对象时，都是通过上面slice方法返回的，虽然新建的ByteBuffer对象和上面的writeBuffer或mappedByteBuffer
        // 使用的是同一个缓存区，但是新建的ByteBuffer对象的position和capacity等属性和上面两个ByteBuffer对象互不影响，所以每次都
        // 使用slice并在不操作上面两个ByteBuffer对象的情况下，上面两个ByteBuffer对象的position和capacity属性始终不变，分别等于
        // 0和MappedFile文件大小。因此，slice出来的新的ByteBuffer对象的position也始终是0，而capacity也等于MappedFile文件大小。
        // 这里将position设置为之前写入的位置，下面会从该位置继续写入数据
        byteBuffer.position(currentPos);
        AppendMessageResult result;
        if (messageExt instanceof MessageExtBrokerInner) {
            result = cb.doAppend(this.getFileFromOffset(), byteBuffer, this.fileSize - currentPos, (MessageExtBrokerInner) messageExt);
        } else if (messageExt instanceof MessageExtBatch) {
            result = cb.doAppend(this.getFileFromOffset(), byteBuffer, this.fileSize - currentPos, (MessageExtBatch) messageExt);
        } else {
            return new AppendMessageResult(AppendMessageStatus.UNKNOWN_ERROR);
        }
        // 更新写入的位置
        this.wrotePosition.addAndGet(result.getWroteBytes());
        // 更新写入的时间
        this.storeTimestamp = result.getStoreTimestamp();
        return result;
    }
    log.error("MappedFile.appendMessage return null, wrotePosition: {} fileSize: {}", currentPos, this.fileSize);
    return new AppendMessageResult(AppendMessageStatus.UNKNOWN_ERROR);
}
```

`cb.doAppend(this.getFileFromOffset(), byteBuffer, this.fileSize - currentPos, (MessageExtBrokerInner) messageExt)`语句是真正执行消息写入的方法，参数`byteBuffer`就是用于写入消息的buffer，其具体实现可以看笔记[如何实现消息存储](如何实现消息存储.md)。选择用于写入消息的buffer时的逻辑是，当`writeBuffer`不为空时，被用于写入消息的buffer是`writeBuffer`而不是`mappedByteBuffer`，`mappedByteBuffer`才是commitlog文件的内存映射buffer，如果消息写入`writeBuffer`而不是`mappedByteBuffer`，那消息内容是如何保存到commitlog文件的呢？这一部分实现在`CommitLog`类，`CommitLog`类的构造函数初始化了`CommitRealTimeService`对象：
```java
public CommitLog(final DefaultMessageStore defaultMessageStore) {
    // 略

    this.commitLogService = new CommitRealTimeService();

    // 略
}
```

`CommitLog`对象的`start()`方法又调用了`CommitRealTimeService`对象的`start()`方法：
```java
public void start() {
    this.flushCommitLogService.start();

    if (defaultMessageStore.getMessageStoreConfig().isTransientStorePoolEnable()) {
        this.commitLogService.start();
    }
}
```

再来看看`CommitRealTimeService`对象的实现：
```java
abstract class FlushCommitLogService extends ServiceThread {
    protected static final int RETRY_TIMES_OVER = 10;
}

class CommitRealTimeService extends FlushCommitLogService {

    private long lastCommitTimestamp = 0;

    @Override
    public String getServiceName() {
        return CommitRealTimeService.class.getSimpleName();
    }

    @Override
    public void run() {
        CommitLog.log.info(this.getServiceName() + " service started");
        while (!this.isStopped()) {
            // 执行commit的时间间隔
            int interval = CommitLog.this.defaultMessageStore.getMessageStoreConfig().getCommitIntervalCommitLog();

            // 该变量表示至少多少页（默认每页4K）的数据没有被commit时执行commit，当commitDataLeastPages为0时不考虑多少页没有
            // 被commit，直接执行commit
            int commitDataLeastPages = CommitLog.this.defaultMessageStore.getMessageStoreConfig().getCommitCommitLogLeastPages();

            // 多少时间忽略未被commit的数据量强制执行一次commit
            int commitDataThoroughInterval =
                CommitLog.this.defaultMessageStore.getMessageStoreConfig().getCommitCommitLogThoroughInterval();

            long begin = System.currentTimeMillis();
            // 如果到了需要执行commit的时间，设置commitDataLeastPages为0即可
            if (begin >= (this.lastCommitTimestamp + commitDataThoroughInterval)) {
                this.lastCommitTimestamp = begin;
                commitDataLeastPages = 0;
            }

            try {
                // 真正执行了commit操作时result为false，如果commit前后mappedFileQueue的committedWhere值相等则返回true
                boolean result = CommitLog.this.mappedFileQueue.commit(commitDataLeastPages);
                long end = System.currentTimeMillis();
                if (!result) {
                    // 当result为false时，说明真正执行了commit，这里更新上次commit的时间
                    this.lastCommitTimestamp = end; // result = false means some data committed.
                    //now wake up flush thread.
                    // 唤醒执行flush操作的线程，也就是FlushRealTimeService
                    flushCommitLogService.wakeup();
                }

                if (end - begin > 500) {
                    log.info("Commit data to file costs {} ms", end - begin);
                }
                // 等待被唤醒（handleDiskFlush方法会执行唤醒操作），或等待interval的时间自动唤醒
                this.waitForRunning(interval);
            } catch (Throwable e) {
                CommitLog.log.error(this.getServiceName() + " service has exception. ", e);
            }
        }

        boolean result = false;
        // 最后结束的时候再commit若干次确保数据都commit了
        for (int i = 0; i < RETRY_TIMES_OVER && !result; i++) {
            result = CommitLog.this.mappedFileQueue.commit(0);
            CommitLog.log.info(this.getServiceName() + " service shutdown, retry " + (i + 1) + " times " + (result ? "OK" : "Not OK"));
        }
        CommitLog.log.info(this.getServiceName() + " service end");
    }
}
```

`CommitRealTimeService`类继承了`ServiceThread`类，表示`CommitRealTimeService`对象是一个线程，并且支持启动、停止和等待等操作。`CommitRealTimeService`的运行逻辑很简单，根据配置的时间不断的执行`CommitLog.this.mappedFileQueue.commit(commitDataLeastPages)`语句，参数`commitDataLeastPages`表示某次commit需要操作的数据量的最小值，小于该值则不执行commit，下面再看看`mappedFileQueue`对象的`commit()`方法是如何实现的：
```java
public boolean commit(final int commitLeastPages) {
    boolean result = true;
    // committedWhere等于上次commit完成的位置
    MappedFile mappedFile = this.findMappedFileByOffset(this.committedWhere, this.committedWhere == 0);
    if (mappedFile != null) {
        int offset = mappedFile.commit(commitLeastPages);
        long where = mappedFile.getFileFromOffset() + offset;
        result = where == this.committedWhere;
        this.committedWhere = where;
    }

    return result;
}
```

上面的逻辑是，根据上次commit的位置获取`MappedFile`对象，再执行`MappedFile`对象的`commit()`方法，代码如下：
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

`MappedFile`对象的`commit()`方法在`writeBuffer`不为空的情况下，判断当前是否满足commit的条件，对应的方法是`isAbleToCommit()`：
```java
protected boolean isAbleToCommit(final int commitLeastPages) {
    int flush = this.committedPosition.get();
    int write = this.wrotePosition.get();

    if (this.isFull()) {
        return true;
    }

    if (commitLeastPages > 0) {
        return ((write / OS_PAGE_SIZE) - (flush / OS_PAGE_SIZE)) >= commitLeastPages;
    }

    return write > flush;
}
```

分别获取commit的位置和当前写入的位置，再计算差值判断是否满足一定的数据量，当满足条件时，执行`commit0()`方法提交数据，代码：
```java
protected void commit0(final int commitLeastPages) {
    int writePos = this.wrotePosition.get();
    int lastCommittedPosition = this.committedPosition.get();

    if (writePos - this.committedPosition.get() > 0) {
        try {
            ByteBuffer byteBuffer = writeBuffer.slice();
            byteBuffer.position(lastCommittedPosition);
            byteBuffer.limit(writePos);
            this.fileChannel.position(lastCommittedPosition);
            this.fileChannel.write(byteBuffer);
            this.committedPosition.set(writePos);
        } catch (Throwable e) {
            log.error("Error occurred when commit data to FileChannel.", e);
        }
    }
}
```

上面的逻辑很简单，就是将上次commit位置之后的数据写入到`FileChannel`对象，`MappedByteBuffer`对象就是该`FileChannel`对象创建来的，写入该`FileChannel`对象的数据和写入`MappedByteBuffer`对象的数据最后都能保存到`MappedFile`对象对应的文件，如commitlog文件。

以上就是开启“读写分离”模式后，消息写入的逻辑，简单来说就是，数据首先写入`writeBuffer`，再由`CommitRealTimeService`对象执行commit操作，将数据写入`FileChannel`对象，而这些数据也可以从`MappedByteBuffer`对象中获取到，下面的demo也可以说明这一点：
```java
public class MappedByteBufferAndFileChannelTest {
    /**
     * 测试直接向FileChannel写入的数据能否通过FileChannel创建出来的MappedByteBuffer获取到
     *
     * 结论：能
     */
    @Test
    public void test() throws IOException {
        final File file = new File(String.format("%s%s%s", System.getProperty("user.home"), File.separator, "MappedByteBufferAndFileChannelTest.tmp"));
        final int fileSize = 64; // 64bytes

        if (file.exists() && file.delete() && file.createNewFile()) {
            System.out.println("初始化文件: " + file.getName());
        } else if (!file.exists() && file.createNewFile()) {
            System.out.println("初始化文件: " + file.getName());
        } else {
            System.out.println("初始化文件异常");
        }

        FileChannel fileChannel = new RandomAccessFile(file, "rw").getChannel();
        // 内存映射
        MappedByteBuffer mappedByteBuffer = fileChannel.map(FileChannel.MapMode.READ_WRITE, 0, fileSize);

        String message = "test message";
        final ByteBuffer allocate = ByteBuffer.allocate(4 + message.getBytes().length);

        // 写数据到fileChannel
        allocate.putInt(message.getBytes().length);
        allocate.put(message.getBytes(Charset.defaultCharset()));
        allocate.flip();
        fileChannel.write(allocate);

        readFromFileChannel(fileChannel, 0, 4 + message.getBytes().length);
        readFromMappedByteBuffer(mappedByteBuffer);

        fileChannel.close();
    }

    private void readFromFileChannel(FileChannel fileChannel, int position, int length) throws IOException {
        final ByteBuffer readBuffer = ByteBuffer.allocate(length);
        fileChannel.position(position);
        fileChannel.read(readBuffer);

        readBuffer.flip();
        final int size = readBuffer.getInt();
        byte[] bytes = new byte[size];
        readBuffer.get(bytes, 0, size);
        System.out.println("read from fileChannel: " + new String(bytes, Charset.defaultCharset()));
    }

    private void readFromMappedByteBuffer(MappedByteBuffer mappedByteBuffer) {
        final int size = mappedByteBuffer.getInt();
        byte[] bytes = new byte[size];
        mappedByteBuffer.get(bytes, 0, size);
        System.out.println("read from mappedByteBuffer: " + new String(bytes, Charset.defaultCharset()));
    }
}

/*
输出：
初始化文件: MappedByteBufferAndFileChannelTest.tmp
read from fileChannel: test message
read from mappedByteBuffer: test message
*/
```

如果没有开启“读写分离”模式，从`MappedFile`对象的`appendMessagesInner()`方法可知，消息是直接写入`mappedByteBuffer`的，如果开启“读写分离”模式，消息最终是写入`FileChannel`对象的，为什么开启“读写分离”后不写入`mappedByteBuffer`呢，[网上](https://juejin.im/post/5cd82323f265da038932b1e6)对`FileChannel`和`MappedByteBuffer`的性能做了测试，结果是`FileChannel`对象在一次写入的数据量大于4K时，性能高于`MappedByteBuffer`，但是我自己也写了一个[性能测试](../../demo/src/test/java/com/dhf/demo/FileChannelAndMappedByteBufferWriteWithMlockBenchmark.java)，结果是在数据量比较大的情况下，还是`MappedByteBuffer`的性能比较高，测试结果：
```
Benchmark                                                                    (bufferSize)  Mode  Cnt  Score   Error  Units
FileChannelAndMappedByteBufferWriteWithMlockBenchmark.fileChannelWrite               2048    ss    5  2.054 ± 0.131   s/op
FileChannelAndMappedByteBufferWriteWithMlockBenchmark.fileChannelWrite               4096    ss    5  1.287 ± 0.475   s/op
FileChannelAndMappedByteBufferWriteWithMlockBenchmark.fileChannelWrite               8192    ss    5  0.839 ± 0.118   s/op
FileChannelAndMappedByteBufferWriteWithMlockBenchmark.fileChannelWrite              16384    ss    5  0.665 ± 0.047   s/op
FileChannelAndMappedByteBufferWriteWithMlockBenchmark.fileChannelWrite              20480    ss    5  0.643 ± 0.125   s/op
FileChannelAndMappedByteBufferWriteWithMlockBenchmark.mappedByteBufferWrite          2048    ss    5  0.365 ± 0.114   s/op
FileChannelAndMappedByteBufferWriteWithMlockBenchmark.mappedByteBufferWrite          4096    ss    5  0.349 ± 0.012   s/op
FileChannelAndMappedByteBufferWriteWithMlockBenchmark.mappedByteBufferWrite          8192    ss    5  0.349 ± 0.015   s/op
FileChannelAndMappedByteBufferWriteWithMlockBenchmark.mappedByteBufferWrite         16384    ss    5  0.344 ± 0.005   s/op
FileChannelAndMappedByteBufferWriteWithMlockBenchmark.mappedByteBufferWrite         20480    ss    5  0.384 ± 0.122   s/op
```

结果显示，无论一次写入的数据量（bufferSize）是多少，始终是`MappedByteBuffer`的性能高于`FileChannel`，特别是在写入小的数据时，性能差距好几倍。当然rocketmq也做了优化，也就是上面`CommitRealTimeService`对象的`run()`方法中`commitDataLeastPages`变量限制了默认在不考虑其他配置影响的情况下（不考虑`commitDataThoroughInterval`变量的影响），一次commit的数据量不会小于4 * 4K，这样使用`FileChannel`写入数据的时间和使用`MappedByteBuffer`写入数据的时间不会相差太大，可能是我测试时的机器配置问题或者测试代码的逻辑问题导致测试结果和网上的测试的结果不一样，不过使用`FileChannel`写入数据而不是`MappedByteBuffer`的目的应该还是提高commit时的性能的。

在了解”读写分离“模式的实现远离后，再总结下开启“读写分离“模式的好处：
```
通常有如下两种方式进行读写：
第一种，mmap+pageCache的方式，读写消息都走的是pageCache，这样读写都在pagecache里不可避免会有锁的问题，在并发的读写操作情况下，会出现缺页中断降低，内存加锁，污染页的回写。
第二种，DirectByteBuffer(堆外内存)+pageCache的两层架构方式，这样可以实现读写消息分离，写入消息时候写到的是DirectByteBuffer（堆外内存），读消息走的是pageCache（DirectByteBuffer是两步刷盘，一步是刷到pageCache，还有一步是刷到磁盘文件中），带来的好处就是，避免了内存操作的很多容易堵的地方，降低了时延，比如说缺页中断降低，内存加锁，污染页的回写。
```

rocketmq在开启“读写分离”后执行的就是第二种读写方式，开启“读写分离”模式后，写入的数据是先写入`DirectByteBuffer`，再由定时任务执行commit将数据一次性写入`FileChannel`，相比于不开启“读写分离”模式时数据直接写入pageCache，rocketmq的这种实现减少了数据直接写入pageCache的次数，也就减少了pageCache的并发操作，从而减少了出现缺页中断、内存加锁，污染页回写等问题的次数。

以上就是transientStorePoolEnable属性的作用。