package com.dhf.demo;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
public class FileChannelAndMappedByteBufferReadBenchmark {
    @Param({"32", "64", "128", "256", "512", "1024", "2048", "4096", "8192"})
    private int fileSize;

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(FileChannelAndMappedByteBufferReadBenchmark.class.getSimpleName())
                .forks(1)
                .measurementIterations(1)
                .measurementTime(TimeValue.seconds(1))
                .warmupIterations(1)
                .warmupTime(TimeValue.seconds(1))
                .timeUnit(TimeUnit.MILLISECONDS)
                .threads(Runtime.getRuntime().availableProcessors())
                .mode(Mode.SingleShotTime)
                .build();

        new Runner(opt).run();
    }

    @Benchmark
    public void fileChannelRead() {
        System.out.println("测试: " + fileSize);
    }

    @Setup
    public void mappedByteBufferRead() {
        System.out.println("测试: " + fileSize);
    }

    @TearDown
    public void shutdown() {
        System.out.println("删除文件: " + fileSize);
    }
}