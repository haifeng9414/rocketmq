package com.dhf.demo;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

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
    // 每次测试的文件数量
    private static int count = 1;
    private static String directory = System.getProperty("java.io.tmpdir");
    // 每次测试的文件大小（字节）
    @Param({"32", "64", "128", "256", "512", "1024", "2048", "4096", "8192"})
    private int fileSize;

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(FileChannelAndMappedByteBufferWriteBenchmark.class.getSimpleName())
                .forks(1)
                .measurementIterations(1)
                .timeUnit(TimeUnit.MILLISECONDS)
                .threads(1)
                .mode(Mode.SingleShotTime)
                .build();

        new Runner(opt).run();
    }

    @Benchmark
    public void mappedByteBufferWrite() throws IOException {
        for (int i = 0; i < count; i++) {
            final String filePath = buildFilePath(i);
            final File file = new File(filePath);
            FileChannel fileChannel = new RandomAccessFile(file, "rw").getChannel();
            MappedByteBuffer mappedByteBuffer = fileChannel.map(FileChannel.MapMode.READ_WRITE, 0, fileSize);

            final ByteBuffer byteBuffer = ByteBuffer.allocate(fileSize);
            for (int j = 0; j < this.fileSize; j += Integer.BYTES) {
                byteBuffer.putInt(j);
            }
            byteBuffer.flip();
            mappedByteBuffer.put(byteBuffer);

            fileChannel.close();
        }
    }

    @Benchmark
    public void fileChannelWrite() throws IOException {
        for (int i = 0; i < count; i++) {
            final String filePath = buildFilePath(i);
            final File file = new File(filePath);
            FileChannel fileChannel = new RandomAccessFile(file, "rw").getChannel();

            final ByteBuffer byteBuffer = ByteBuffer.allocate(fileSize);
            for (int j = 0; j < this.fileSize; j += Integer.BYTES) {
                byteBuffer.putInt(j);
            }
            byteBuffer.flip();
            fileChannel.write(byteBuffer);

            fileChannel.close();
        }
    }

    @Benchmark
    public void randomAccessFileWrite() throws IOException {
        for (int i = 0; i < count; i++) {
            final String filePath = buildFilePath(i);
            final File file = new File(filePath);
            final RandomAccessFile randomAccessFile = new RandomAccessFile(file, "rw");

            final ByteBuffer byteBuffer = ByteBuffer.allocate(fileSize);
            for (int j = 0; j < this.fileSize; j += Integer.BYTES) {
                byteBuffer.putInt(j);
            }
            byteBuffer.flip();
            randomAccessFile.write(byteBuffer.array());

            randomAccessFile.close();
        }
    }

    @Benchmark
    public void fileOutputStreamWrite() throws IOException {
        for (int i = 0; i < count; i++) {
            final String filePath = buildFilePath(i);
            FileOutputStream fileOutputStream = new FileOutputStream(filePath);

            final ByteBuffer byteBuffer = ByteBuffer.allocate(fileSize);
            for (int j = 0; j < this.fileSize; j += Integer.BYTES) {
                byteBuffer.putInt(j);
            }
            byteBuffer.flip();
            fileOutputStream.write(byteBuffer.array());

            fileOutputStream.close();
        }
    }

    @Setup
    public void prepare() throws IOException {
        System.out.println("使用临时目录创建文件：" + directory);

        for (int i = 0; i < count; i++) {
            final String filePath = buildFilePath(i);
            final File file = new File(filePath);

            if (!file.exists() || file.delete()) {
                if (!file.createNewFile()) {
                    throw new RuntimeException("无法创建文件：" + filePath);
                }
            } else {
                throw new RuntimeException("无法创建文件：" + filePath + "，无法删除已存在的文件");
            }
        }
    }

    @TearDown
    public void shutdown() {
        for (int i = 0; i < count; i++) {
            final String filePath = buildFilePath(i);
            final File file = new File(filePath);

            if (!file.delete()) {
                System.out.println("无法清除文件：" + filePath);
            }
        }
    }

    private String buildFilePath(int index) {
        return directory + File.separator + "file" + index;
    }
}