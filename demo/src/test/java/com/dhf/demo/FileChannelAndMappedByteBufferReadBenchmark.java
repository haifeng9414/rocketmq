package com.dhf.demo;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
public class FileChannelAndMappedByteBufferReadBenchmark {
    // 下面两个变量参考自CommitLog类的内部类CommitRealTimeService类的实现
    private static final int OS_PAGE_SIZE = 1024 * 4;
    private static final int commitLeastPages = 4;
    private static String directory = System.getProperty("java.io.tmpdir");
    // 每次测试的缓存区大小（字节）
    @Param({"2048", "4096", "8192", "" + commitLeastPages * OS_PAGE_SIZE, "" + (commitLeastPages + 1) * OS_PAGE_SIZE})
//    @Param({"" + commitLeastPages * OS_PAGE_SIZE, "" + (commitLeastPages + 1) * OS_PAGE_SIZE})
    private int bufferSize;
    // 测试1G文件
    private int fileSize = 1024 * 1024 * 1024;

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(FileChannelAndMappedByteBufferReadBenchmark.class.getSimpleName())
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
    public void mappedByteBufferRead() throws IOException {
        final String filePath = buildFilePath();
        final File file = new File(filePath);
        FileChannel fileChannel = new RandomAccessFile(file, "rw").getChannel();
        MappedByteBuffer mappedByteBuffer = fileChannel.map(FileChannel.MapMode.READ_WRITE, 0, this.fileSize);
        byte[] buffer = new byte[this.bufferSize];
        ByteBuffer readBuffer = mappedByteBuffer.slice();

        int lastRead = -1;
        for (int j = this.bufferSize, k = 0; j < this.fileSize; j += this.bufferSize, k++) {
            readBuffer.position(k * this.bufferSize);
            ByteBuffer readBufferNew = readBuffer.slice();
            readBufferNew.limit(this.bufferSize);

            readBufferNew.get(buffer);
            lastRead = j;
        }

        if (lastRead > 0 && lastRead < this.fileSize) {
            readBuffer.position(lastRead);
            ByteBuffer readBufferNew = readBuffer.slice();
            readBufferNew.limit(this.fileSize - lastRead);

            // 直接调用readBufferNew.get(buffer)会抛出BufferUnderflowException，因为readBufferNew的limit - position小于buffer的容量
            readBufferNew.get(buffer, 0, this.fileSize - lastRead);
        }

        fileChannel.close();
    }

//    @Benchmark
    public void fileChannelRead() throws IOException {
        final String filePath = buildFilePath();
        final File file = new File(filePath);
        FileChannel fileChannel = new RandomAccessFile(file, "rw").getChannel();
        fileChannel.map(FileChannel.MapMode.READ_WRITE, 0, this.fileSize);
        final ByteBuffer readBuffer = ByteBuffer.allocateDirect(this.bufferSize);

        while (fileChannel.read(readBuffer) != -1) {
            readBuffer.clear();
        }

        fileChannel.close();
    }

//    @Benchmark
    public void randomAccessFileRead(Blackhole blackhole) throws IOException {
        final String filePath = buildFilePath();
        final File file = new File(filePath);
        final RandomAccessFile randomAccessFile = new RandomAccessFile(file, "rw");
        byte[] readBuffer = new byte[this.bufferSize];

        while (randomAccessFile.read(readBuffer) != -1) {
            blackhole.consume(readBuffer);
        }

        randomAccessFile.close();
    }

//    @Benchmark
    public void fileOutputStreamRead(Blackhole blackhole) throws IOException {
        final String filePath = buildFilePath();
        final FileInputStream fileInputStream = new FileInputStream(filePath);

        byte[] readBuffer = new byte[this.bufferSize];

        while (fileInputStream.read(readBuffer) != -1) {
            blackhole.consume(readBuffer);
        }

        fileInputStream.close();
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
        return FileChannelAndMappedByteBufferReadBenchmark.directory + File.separator + this.getClass().getSimpleName() + "BenchmarkFile";
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
}