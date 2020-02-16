package com.dhf.demo;

import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import org.apache.rocketmq.store.util.LibC;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;
import sun.nio.ch.DirectBuffer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
public class FileChannelAndMappedByteBufferReadWithMlockBenchmark {
    // 下面两个变量参考自CommitLog类的内部类CommitRealTimeService类的实现
    private static final int OS_PAGE_SIZE = 1024 * 4;
    private static final int commitLeastPages = 4;
    private static String directory = System.getProperty("java.io.tmpdir");
    // 每次测试的缓存区大小（字节）
    @Param({"32", "64", "128", "256", "512", "1024", "2048", "4096", "8192", "" + commitLeastPages * OS_PAGE_SIZE})
//    @Param({"" + commitLeastPages * OS_PAGE_SIZE})
    private int bufferSize;
    // 测试1G文件
    private int fileSize = 1024 * 1024 * 1024;

    private FileChannel fileChannel;
    private MappedByteBuffer mappedByteBuffer;

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(FileChannelAndMappedByteBufferReadWithMlockBenchmark.class.getSimpleName())
                .forks(1)
                .warmupIterations(2)
                .measurementIterations(2)
                .timeUnit(TimeUnit.SECONDS)
                .timeout(TimeValue.hours(1))
                .threads(1)
                .mode(Mode.SingleShotTime)
                .build();

        new Runner(opt).run();
    }

    @Benchmark
    public void mappedByteBufferRead() throws IOException {
        byte[] buffer = new byte[this.bufferSize];

        ByteBuffer readBuffer = this.mappedByteBuffer.slice();

        for (int j = 0, k = 0; j < this.fileSize; j += this.bufferSize, k++) {
            readBuffer.position(k * this.bufferSize);
            ByteBuffer readBufferNew = readBuffer.slice();
            readBufferNew.limit(this.bufferSize);

            readBuffer.get(buffer);
        }
    }

    @Benchmark
    public void fileChannelRead() throws IOException {
        final ByteBuffer readBuffer = ByteBuffer.allocate(this.bufferSize);

        this.fileChannel.position(0);

        while (this.fileChannel.read(readBuffer) != -1) {
            readBuffer.clear();
        }
    }

    @Setup
    public void prepare() throws IOException {
        final String filePath = buildFilePath();
        final File file = new File(filePath);

        System.out.println("使用临时文件：" + filePath);

        if (!file.exists() || file.delete()) {
            if (!file.createNewFile()) {
                throw new RuntimeException("无法创建文件：" + filePath);
            } else {
                fillingData(file);
                this.fileChannel = new RandomAccessFile(file, "rw").getChannel();
                this.mappedByteBuffer = this.fileChannel.map(FileChannel.MapMode.READ_WRITE, 0, this.fileSize);
                this.mlock();
            }
        } else {
            throw new RuntimeException("无法创建文件：" + filePath + "，无法删除已存在的文件");
        }
    }

    @TearDown
    public void shutdown() throws IOException {
        final String filePath = buildFilePath();
        final File file = new File(filePath);

        if (!file.delete()) {
            System.out.println("无法清除文件：" + filePath);
        }

        this.munlock();
        this.fileChannel.close();
    }

    private String buildFilePath() {
        return FileChannelAndMappedByteBufferReadWithMlockBenchmark.directory + File.separator + this.getClass().getSimpleName() + "BenchmarkFile";
    }

    private void fillingData(File file) throws IOException {
        FileChannel fileChannel = new RandomAccessFile(file, "rw").getChannel();
        final int bufferSize = Math.max(this.bufferSize, 16384);
        final ByteBuffer byteBuffer = ByteBuffer.allocate(bufferSize);

        fileChannel.position(0);

        for (int j = 0; j < this.fileSize; j += bufferSize) {
            for (int k = 0; k < bufferSize; k += Integer.BYTES) {
                byteBuffer.putInt(k);
            }

            byteBuffer.flip();
            fileChannel.write(byteBuffer);
            byteBuffer.clear();
        }

        fileChannel.force(false);
    }

    private void mlock() {
        final long address = ((DirectBuffer) (this.mappedByteBuffer)).address();
        Pointer pointer = new Pointer(address);
        {
            LibC.INSTANCE.mlock(pointer, new NativeLong(this.fileSize));
        }

        {
            LibC.INSTANCE.madvise(pointer, new NativeLong(this.fileSize), LibC.MADV_WILLNEED);
        }
    }

    private void munlock() {
        final long address = ((DirectBuffer) (this.mappedByteBuffer)).address();
        Pointer pointer = new Pointer(address);
        LibC.INSTANCE.munlock(pointer, new NativeLong(this.fileSize));
    }
}