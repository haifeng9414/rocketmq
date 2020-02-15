package com.dhf.demo;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
public class TmpBenchmark {
    private static String directory = System.getProperty("java.io.tmpdir");
    // 每次测试的文件大小（字节）
    @Param({"32", "64"})
    private int fileSize;

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(TmpBenchmark.class.getSimpleName())
                .forks(1)
                .measurementIterations(2)
                .timeUnit(TimeUnit.NANOSECONDS)
                .mode(Mode.SingleShotTime)
                .build();

        new Runner(opt).run();
    }

    @Benchmark
    public void fileChannelWrite() throws IOException {
        System.out.println("开始测试");
    }

    @Setup
    public void prepare() throws IOException {
        System.out.println("使用临时目录创建文件：" + directory);
    }

    @TearDown
    public void shutdown() {
    }
}