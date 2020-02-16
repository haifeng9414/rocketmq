package com.dhf.demo;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
public class FileChannelAndMappedByteBufferWriteBenchmark {
    // 下面两个变量参考自CommitLog类的内部类CommitRealTimeService类的实现
    private static final int OS_PAGE_SIZE = 1024 * 4;
    private static final int commitLeastPages = 4;
    private static String directory = System.getProperty("java.io.tmpdir");
    // 每次测试的缓存区大小（字节），小于2048字节的没必要测试了，mmap有明显的性能优势
    @Param({"2048", "4096", "8192", "" + commitLeastPages * OS_PAGE_SIZE, "" + (commitLeastPages + 1) * OS_PAGE_SIZE})
//    @Param({"" + commitLeastPages * OS_PAGE_SIZE, "" + (commitLeastPages + 1) * OS_PAGE_SIZE})
    private int bufferSize;
    // 测试1G文件
    private int fileSize = 1024 * 1024 * 1024;

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(FileChannelAndMappedByteBufferWriteBenchmark.class.getSimpleName())
                .forks(1)
                .warmupIterations(5)
                .measurementIterations(5)
                .timeUnit(TimeUnit.SECONDS)
                .timeout(TimeValue.hours(1))
                .threads(1)
                .mode(Mode.SingleShotTime)
                .build();

        new Runner(opt).run();
    }

    @Benchmark
    public void mappedByteBufferWrite() throws IOException {
        final String filePath = buildFilePath();
        final File file = new File(filePath);
        FileChannel fileChannel = new RandomAccessFile(file, "rw").getChannel();
        MappedByteBuffer mappedByteBuffer = fileChannel.map(FileChannel.MapMode.READ_WRITE, 0, this.fileSize);
        final ByteBuffer byteBuffer = ByteBuffer.allocateDirect(this.bufferSize);

        final ByteBuffer writeBuffer = mappedByteBuffer.slice();

        for (int j = this.bufferSize; j < this.fileSize; j += this.bufferSize) {
            // 填充缓冲区
            for (int k = 0; k < this.bufferSize; k += Integer.BYTES) {
                byteBuffer.putInt(k);
            }

            byteBuffer.flip();
            writeBuffer.put(byteBuffer);
            byteBuffer.clear();
        }

        fileChannel.close();
    }

    @Benchmark
    public void fileChannelWrite() throws IOException {
        final String filePath = buildFilePath();
        final File file = new File(filePath);
        FileChannel fileChannel = new RandomAccessFile(file, "rw").getChannel();
        // 这里不使用map和使用map性能差了好几倍，rocketmq中无论什么情况，都是fileChannel和对应的mappedByteBuffer一块创建
        // 这里刚map完就能在操作系统中看到一个fileSize大小的文件
        fileChannel.map(FileChannel.MapMode.READ_WRITE, 0, this.fileSize);
        final ByteBuffer byteBuffer = ByteBuffer.allocateDirect(this.bufferSize);

        // 每次从文件起点开始，以bufferSize为缓冲区大小，写入数据直到文件被写满
        fileChannel.position(0);

        for (int j = this.bufferSize; j < this.fileSize; j += this.bufferSize) {
            // 填充缓冲区
            for (int k = 0; k < this.bufferSize; k += Integer.BYTES) {
                byteBuffer.putInt(k);
            }

            byteBuffer.flip();
            fileChannel.write(byteBuffer);
            byteBuffer.clear();
        }

        fileChannel.close();
    }

    @Benchmark
    public void randomAccessFileWrite() throws IOException {
        final String filePath = buildFilePath();
        final File file = new File(filePath);
        final RandomAccessFile randomAccessFile = new RandomAccessFile(file, "rw");
        final ByteBuffer byteBuffer = ByteBuffer.allocateDirect(this.bufferSize);

        for (int j = this.bufferSize; j < this.fileSize; j += this.bufferSize) {
            // 填充缓冲区
            for (int k = 0; k < this.bufferSize; k += Integer.BYTES) {
                byteBuffer.putInt(k);
            }

            byteBuffer.flip();
            randomAccessFile.write(byteBuffer.array());
            byteBuffer.clear();
        }

        randomAccessFile.close();
    }

    @Benchmark
    public void fileOutputStreamWrite() throws IOException {
        final String filePath = buildFilePath();
        FileOutputStream fileOutputStream = new FileOutputStream(filePath);

        final ByteBuffer byteBuffer = ByteBuffer.allocateDirect(this.bufferSize);

        for (int j = this.bufferSize; j < this.fileSize; j += this.bufferSize) {
            // 填充缓冲区
            for (int k = 0; k < this.bufferSize; k += Integer.BYTES) {
                byteBuffer.putInt(k);
            }

            byteBuffer.flip();
            fileOutputStream.write(byteBuffer.array());
            byteBuffer.clear();
        }

        fileOutputStream.close();
    }

    @Setup
    public void prepare() throws IOException {
        final String filePath = buildFilePath();
        final File file = new File(filePath);

        System.out.println("使用临时文件：" + filePath);

        if (!file.exists() || file.delete()) {
            if (!file.createNewFile()) {
                throw new RuntimeException("无法创建文件：" + filePath);
            }
        } else {
            throw new RuntimeException("无法创建文件：" + filePath + "，无法删除已存在的文件");
        }
    }

    @TearDown
    public void shutdown() {
        final String filePath = buildFilePath();
        final File file = new File(filePath);

        if (!file.delete()) {
            System.out.println("无法清除文件：" + filePath);
        }
    }

    private String buildFilePath() {
        return FileChannelAndMappedByteBufferWriteBenchmark.directory + File.separator + this.getClass().getSimpleName() + "BenchmarkFile";
    }
}