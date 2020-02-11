/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.store;

import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.rocketmq.common.UtilAll;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.message.MessageExtBatch;
import org.apache.rocketmq.store.config.FlushDiskType;
import org.apache.rocketmq.store.util.LibC;
import sun.nio.ch.DirectBuffer;

public class MappedFile extends ReferenceResource {
    public static final int OS_PAGE_SIZE = 1024 * 4;
    protected static final InternalLogger log = InternalLoggerFactory.getLogger(LoggerName.STORE_LOGGER_NAME);

    // 在执行cleanup方法前TOTAL_MAPPED_VIRTUAL_MEMORY保存文件大小
    private static final AtomicLong TOTAL_MAPPED_VIRTUAL_MEMORY = new AtomicLong(0);

    // 在执行cleanup方法前TOTAL_MAPPED_FILES保存文件数量（其实就一个文件）
    private static final AtomicInteger TOTAL_MAPPED_FILES = new AtomicInteger(0);
    // 保存写入的位置
    protected final AtomicInteger wrotePosition = new AtomicInteger(0);
    // 如果采用"读写分离"的模式，消息会先写入writeBuffer，此时数据没有写入到mappedByteBuffer，所以不算真正的写入，需要把writeBuffer
    // 的数据写入到mappedByteBuffer才行，该变量就是保存写入到mappedByteBuffer的位置，如果wrotePosition == committedPosition表示
    // 数据全提交了
    protected final AtomicInteger committedPosition = new AtomicInteger(0);
    // 数据写入mappedByteBuffer不代表真正的文件中就有数据了，还需要flush，这里保存flush的位置
    private final AtomicInteger flushedPosition = new AtomicInteger(0);
    // MappedFile文件的大小，如果是commitlog文件，则默认1G，如果是consumeQueue文件，则默认5.72M
    protected int fileSize;
    protected FileChannel fileChannel;
    /**
     * Message will put to here first, and then reput to FileChannel if writeBuffer is not null.
     */
    // 采用"读写分离"的模式时消息会先写入该buffer，而不是直接写入mappedByteBuffer
    protected ByteBuffer writeBuffer = null;
    // 用于获取writeBuffer的buffer池
    protected TransientStorePool transientStorePool = null;
    // MappedFile文件的名称
    private String fileName;
    // MappedFile文件的起始offset
    private long fileFromOffset;
    // 指向MappedFile在文件系统中的文件
    private File file;
    // MappedFile文件的内存映射buffer
    private MappedByteBuffer mappedByteBuffer;
    // 上次写入消息的时间
    private volatile long storeTimestamp = 0;
    // 当前MappedFile文件是否是MappedFileQueue的第一个MappedFile文件
    private boolean firstCreateInQueue = false;

    public MappedFile() {
    }

    public MappedFile(final String fileName, final int fileSize) throws IOException {
        init(fileName, fileSize);
    }

    public MappedFile(final String fileName, final int fileSize,
        final TransientStorePool transientStorePool) throws IOException {
        init(fileName, fileSize, transientStorePool);
    }

    public static void ensureDirOK(final String dirName) {
        if (dirName != null) {
            File f = new File(dirName);
            if (!f.exists()) {
                boolean result = f.mkdirs();
                log.info(dirName + " mkdir " + (result ? "OK" : "Failed"));
            }
        }
    }

    public static void clean(final ByteBuffer buffer) {
        if (buffer == null || !buffer.isDirect() || buffer.capacity() == 0)
            return;
        invoke(invoke(viewed(buffer), "cleaner"), "clean");
    }

    private static Object invoke(final Object target, final String methodName, final Class<?>... args) {
        return AccessController.doPrivileged(new PrivilegedAction<Object>() {
            public Object run() {
                try {
                    Method method = method(target, methodName, args);
                    method.setAccessible(true);
                    return method.invoke(target);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        });
    }

    private static Method method(Object target, String methodName, Class<?>[] args)
        throws NoSuchMethodException {
        try {
            return target.getClass().getMethod(methodName, args);
        } catch (NoSuchMethodException e) {
            return target.getClass().getDeclaredMethod(methodName, args);
        }
    }

    private static ByteBuffer viewed(ByteBuffer buffer) {
        String methodName = "viewedBuffer";
        Method[] methods = buffer.getClass().getMethods();
        for (int i = 0; i < methods.length; i++) {
            if (methods[i].getName().equals("attachment")) {
                methodName = "attachment";
                break;
            }
        }

        ByteBuffer viewedBuffer = (ByteBuffer) invoke(buffer, methodName);
        if (viewedBuffer == null)
            return buffer;
        else
            return viewed(viewedBuffer);
    }

    public static int getTotalMappedFiles() {
        return TOTAL_MAPPED_FILES.get();
    }

    public static long getTotalMappedVirtualMemory() {
        return TOTAL_MAPPED_VIRTUAL_MEMORY.get();
    }

    public void init(final String fileName, final int fileSize,
        final TransientStorePool transientStorePool) throws IOException {
        init(fileName, fileSize);
        // 如果传入了transientStorePool则表示采用"读写分离"的模式，这里从transientStorePool获取一个用于写入消息的buffer
        this.writeBuffer = transientStorePool.borrowBuffer();
        this.transientStorePool = transientStorePool;
    }

    private void init(final String fileName, final int fileSize) throws IOException {
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.file = new File(fileName);
        this.fileFromOffset = Long.parseLong(this.file.getName());
        boolean ok = false;

        ensureDirOK(this.file.getParent());

        try {
            this.fileChannel = new RandomAccessFile(this.file, "rw").getChannel();
            // 内存映射
            this.mappedByteBuffer = this.fileChannel.map(MapMode.READ_WRITE, 0, fileSize);
            TOTAL_MAPPED_VIRTUAL_MEMORY.addAndGet(fileSize);
            TOTAL_MAPPED_FILES.incrementAndGet();
            ok = true;
        } catch (FileNotFoundException e) {
            log.error("Failed to create file " + this.fileName, e);
            throw e;
        } catch (IOException e) {
            log.error("Failed to map file " + this.fileName, e);
            throw e;
        } finally {
            if (!ok && this.fileChannel != null) {
                this.fileChannel.close();
            }
        }
    }

    public long getLastModifiedTimestamp() {
        return this.file.lastModified();
    }

    public int getFileSize() {
        return fileSize;
    }

    public FileChannel getFileChannel() {
        return fileChannel;
    }

    public AppendMessageResult appendMessage(final MessageExtBrokerInner msg, final AppendMessageCallback cb) {
        return appendMessagesInner(msg, cb);
    }

    public AppendMessageResult appendMessages(final MessageExtBatch messageExtBatch, final AppendMessageCallback cb) {
        return appendMessagesInner(messageExtBatch, cb);
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

    public long getFileFromOffset() {
        return this.fileFromOffset;
    }

    public boolean appendMessage(final byte[] data) {
        int currentPos = this.wrotePosition.get();

        if ((currentPos + data.length) <= this.fileSize) {
            try {
                this.fileChannel.position(currentPos);
                this.fileChannel.write(ByteBuffer.wrap(data));
            } catch (Throwable e) {
                log.error("Error occurred when append message to mappedFile.", e);
            }
            this.wrotePosition.addAndGet(data.length);
            return true;
        }

        return false;
    }

    /**
     * Content of data from offset to offset + length will be wrote to file.
     *
     * @param offset The offset of the subarray to be used.
     * @param length The length of the subarray to be used.
     */
    public boolean appendMessage(final byte[] data, final int offset, final int length) {
        int currentPos = this.wrotePosition.get();

        if ((currentPos + length) <= this.fileSize) {
            try {
                this.fileChannel.position(currentPos);
                this.fileChannel.write(ByteBuffer.wrap(data, offset, length));
            } catch (Throwable e) {
                log.error("Error occurred when append message to mappedFile.", e);
            }
            this.wrotePosition.addAndGet(length);
            return true;
        }

        return false;
    }

    /**
     * @return The current flushed position
     */
    public int flush(final int flushLeastPages) {
        // 判断是否需要执行flush，当flushLeastPages大于0时，至少有flushLeastPages页（默认每页4K）的数据没有flush的时候
        // isAbleToFlush方法返回true，当flushLeastPages等于0时，如果存在未被flush的数据则返回true
        if (this.isAbleToFlush(flushLeastPages)) {
            if (this.hold()) {
                // 获取写入数据的位置，当前flushedPosition的值和写入数据的位置之间的数据就是未被flush的数据
                int value = getReadPosition();

                try {
                    //We only append data to fileChannel or mappedByteBuffer, never both.
                    if (writeBuffer != null || this.fileChannel.position() != 0) {
                        this.fileChannel.force(false);
                    } else {
                        this.mappedByteBuffer.force();
                    }
                } catch (Throwable e) {
                    log.error("Error occurred when force data to disk.", e);
                }

                this.flushedPosition.set(value);
                this.release();
            } else {
                log.warn("in flush, hold failed, flush offset = " + this.flushedPosition.get());
                this.flushedPosition.set(getReadPosition());
            }
        }
        return this.getFlushedPosition();
    }

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

    private boolean isAbleToFlush(final int flushLeastPages) {
        int flush = this.flushedPosition.get();
        int write = getReadPosition();

        if (this.isFull()) {
            return true;
        }

        if (flushLeastPages > 0) {
            return ((write / OS_PAGE_SIZE) - (flush / OS_PAGE_SIZE)) >= flushLeastPages;
        }

        return write > flush;
    }

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

    public int getFlushedPosition() {
        return flushedPosition.get();
    }

    public void setFlushedPosition(int pos) {
        this.flushedPosition.set(pos);
    }

    public boolean isFull() {
        return this.fileSize == this.wrotePosition.get();
    }

    public SelectMappedBufferResult selectMappedBuffer(int pos, int size) {
        int readPosition = getReadPosition();
        if ((pos + size) <= readPosition) {
            if (this.hold()) {
                ByteBuffer byteBuffer = this.mappedByteBuffer.slice();
                byteBuffer.position(pos);
                ByteBuffer byteBufferNew = byteBuffer.slice();
                byteBufferNew.limit(size);
                return new SelectMappedBufferResult(this.fileFromOffset + pos, byteBufferNew, size, this);
            } else {
                log.warn("matched, but hold failed, request pos: " + pos + ", fileFromOffset: "
                    + this.fileFromOffset);
            }
        } else {
            log.warn("selectMappedBuffer request pos invalid, request pos: " + pos + ", size: " + size
                + ", fileFromOffset: " + this.fileFromOffset);
        }

        return null;
    }

    public SelectMappedBufferResult selectMappedBuffer(int pos) {
        int readPosition = getReadPosition();
        if (pos < readPosition && pos >= 0) {
            if (this.hold()) {
                ByteBuffer byteBuffer = this.mappedByteBuffer.slice();
                byteBuffer.position(pos);
                int size = readPosition - pos;
                ByteBuffer byteBufferNew = byteBuffer.slice();
                byteBufferNew.limit(size);
                return new SelectMappedBufferResult(this.fileFromOffset + pos, byteBufferNew, size, this);
            }
        }

        return null;
    }

    @Override
    public boolean cleanup(final long currentRef) {
        if (this.isAvailable()) {
            log.error("this file[REF:" + currentRef + "] " + this.fileName
                + " have not shutdown, stop unmapping.");
            return false;
        }

        if (this.isCleanupOver()) {
            log.error("this file[REF:" + currentRef + "] " + this.fileName
                + " have cleanup, do not do it again.");
            return true;
        }

        // 清空buffer
        clean(this.mappedByteBuffer);
        // TOTAL_MAPPED_VIRTUAL_MEMORY保存有文件大小，这里再减去文件大小，相当于清空
        TOTAL_MAPPED_VIRTUAL_MEMORY.addAndGet(this.fileSize * (-1));
        // TOTAL_MAPPED_FILES保存文件数量（其实就一个文件），这里清空
        TOTAL_MAPPED_FILES.decrementAndGet();
        log.info("unmap file[REF:" + currentRef + "] " + this.fileName + " OK");
        return true;
    }

    public boolean destroy(final long intervalForcibly) {
        // 标记mappedFile文件不可用。根据mappedFile被引用的状态判断是否需要调用cleanup方法
        this.shutdown(intervalForcibly);

        // 如果mappedFile引用次数小于等于0并且已经被设置为待清除状态
        if (this.isCleanupOver()) {
            try {

                // 关闭文件通道
                this.fileChannel.close();
                log.info("close file channel " + this.fileName + " OK");

                long beginTime = System.currentTimeMillis();
                // 删除文件
                boolean result = this.file.delete();
                log.info("delete file[REF:" + this.getRefCount() + "] " + this.fileName
                    + (result ? " OK, " : " Failed, ") + "W:" + this.getWrotePosition() + " M:"
                    + this.getFlushedPosition() + ", "
                    + UtilAll.computeElapsedTimeMilliseconds(beginTime));
            } catch (Exception e) {
                log.warn("close file channel " + this.fileName + " Failed. ", e);
            }

            return true;
        } else {
            log.warn("destroy mapped file[REF:" + this.getRefCount() + "] " + this.fileName
                + " Failed. cleanupOver: " + this.cleanupOver);
        }

        return false;
    }

    public int getWrotePosition() {
        return wrotePosition.get();
    }

    public void setWrotePosition(int pos) {
        this.wrotePosition.set(pos);
    }

    /**
     * @return The max position which have valid data
     */
    public int getReadPosition() {
        // 返回写入数据的位置（写入的数据不一定被flush了），如果writeBuffer为空则wrotePosition的值就是结果，否则可能部分数据存在于
        // writeBuffer中，则committedPosition的值才是已经被写入的位置
        return this.writeBuffer == null ? this.wrotePosition.get() : this.committedPosition.get();
    }

    public void setCommittedPosition(int pos) {
        this.committedPosition.set(pos);
    }

    public void warmMappedFile(FlushDiskType type, int pages) {
        long beginTime = System.currentTimeMillis();
        // mappedByteBuffer为MappedFile文件的MappedByteBuffer
        ByteBuffer byteBuffer = this.mappedByteBuffer.slice();
        int flush = 0;
        long time = System.currentTimeMillis();
        for (int i = 0, j = 0; i < this.fileSize; i += MappedFile.OS_PAGE_SIZE, j++) {
            /*
             mappedByteBuffer是通过mmap返回的，mmap将一个磁盘文件（比如一个commitlog文件，或者是consumeQueue文件）映射到内存中，
             但是开始时并不是直接把磁盘文件里的数据给读取到内存，mmap执行完后，并没有任何的数据拷贝操作，磁盘文件还是停留在那里，mmap
             首先做的是把磁盘文件的地址和用户进程私有空间的虚拟内存地址进行一个映射（mmap在进行文件映射的时候，一般有大小限制，在
             1.5GB~2GB之间，所以rocketmq才让commitlog单个文件在1GB，consumeQueue文件在5.72MB）。当需要对文件进行读写时，比如要写入
             消息到commitlog文件，需要先获取commitlog文件的MappedByteBuffer对象，该对象就代表着被映射的虚拟内存地址。对MappedByteBuffer
             执行写入操作时，数据会直接进入PageCache中，然后过一段时间之后，由os的线程异步刷入磁盘中。当要读取数据时，同样通过MappedByteBuffer
             对象读取数据，读取时会判断数据是否在pageCache里，如果在的话，就可以直接从PageCache里读取了。如果pageCache里没有要读的数据，
             那么此时就会从磁盘文件里加载数据到pageCache中，而且pageCache在加载数据的时候，还会将加载的数据块临近的其他数据块也一起加载
             到PageCache中。
             这里向mappedByteBuffer添加空数据，相当于初始化数据，下面的mlock方法会预读mappedByteBuffer对应的内存，所以这里初始化数据是
             为了这个？
             */
            byteBuffer.put(i, (byte) 0);
            // force flush when flush disk type is sync
            // 如果是同步刷盘，则每pages次循环刷一次盘
            if (type == FlushDiskType.SYNC_FLUSH) {
                if ((i / OS_PAGE_SIZE) - (flush / OS_PAGE_SIZE) >= pages) {
                    flush = i;
                    mappedByteBuffer.force();
                }
            }

            // prevent gc
            // 这是啥操作？网上看到的解释是通过Thread.sleep(0)实现线程切换，给gc线程更多的机会允许，这样就能减少之后一次gc允许的时间
            // 不是很理解这种做法，源自https://stackoverflow.com/questions/53284031/why-thread-sleep0-can-prevent-gc-in-rocketmq
            if (j % 1000 == 0) {
                log.info("j={}, costTime={}", j, System.currentTimeMillis() - time);
                time = System.currentTimeMillis();
                try {
                    Thread.sleep(0);
                } catch (InterruptedException e) {
                    log.error("Interrupted", e);
                }
            }
        }

        // force flush when prepare load finished
        if (type == FlushDiskType.SYNC_FLUSH) {
            log.info("mapped file warm-up done, force to disk, mappedFile={}, costTime={}",
                this.getFileName(), System.currentTimeMillis() - beginTime);
            mappedByteBuffer.force();
        }
        log.info("mapped file warm-up done. mappedFile={}, costTime={}", this.getFileName(),
            System.currentTimeMillis() - beginTime);

        // 锁住mappedByteBuffer对应的内存段防止被操作系统swap掉，调用madvise预读mappedByteBuffer对应的内存段
        this.mlock();
    }

    public String getFileName() {
        return fileName;
    }

    public MappedByteBuffer getMappedByteBuffer() {
        return mappedByteBuffer;
    }

    public ByteBuffer sliceByteBuffer() {
        return this.mappedByteBuffer.slice();
    }

    public long getStoreTimestamp() {
        return storeTimestamp;
    }

    public boolean isFirstCreateInQueue() {
        return firstCreateInQueue;
    }

    public void setFirstCreateInQueue(boolean firstCreateInQueue) {
        this.firstCreateInQueue = firstCreateInQueue;
    }

    public void mlock() {
        final long beginTime = System.currentTimeMillis();
        // 返回内存中的起始地址
        final long address = ((DirectBuffer) (this.mappedByteBuffer)).address();
        Pointer pointer = new Pointer(address);
        {
            // mlock操作能够锁住内存防止内存段被操作系统swap掉，这里锁住mappedByteBuffer对应的整个文件大小的内存
            int ret = LibC.INSTANCE.mlock(pointer, new NativeLong(this.fileSize));
            log.info("mlock {} {} {} ret = {} time consuming = {}", address, this.fileName, this.fileSize, ret, System.currentTimeMillis() - beginTime);
        }

        {
            // madvise操作是向操作系统提出关于指定内存段的建议，这里的madv_willneed建议表示对应的内存段可能马上就要被访问，希望操作
            // 系统能够预读一些这段内存的页面
            int ret = LibC.INSTANCE.madvise(pointer, new NativeLong(this.fileSize), LibC.MADV_WILLNEED);
            log.info("madvise {} {} {} ret = {} time consuming = {}", address, this.fileName, this.fileSize, ret, System.currentTimeMillis() - beginTime);
        }
    }

    public void munlock() {
        final long beginTime = System.currentTimeMillis();
        final long address = ((DirectBuffer) (this.mappedByteBuffer)).address();
        Pointer pointer = new Pointer(address);
        // 解锁指定的内存地址
        int ret = LibC.INSTANCE.munlock(pointer, new NativeLong(this.fileSize));
        log.info("munlock {} {} {} ret = {} time consuming = {}", address, this.fileName, this.fileSize, ret, System.currentTimeMillis() - beginTime);
    }

    //testable
    File getFile() {
        return this.file;
    }

    @Override
    public String toString() {
        return this.fileName;
    }
}
