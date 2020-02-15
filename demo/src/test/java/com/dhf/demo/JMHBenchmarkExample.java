package com.dhf.demo;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.util.concurrent.TimeUnit;

/*
如果测试类中存在带有Setup和TearDown注解的方法，则表示该测试是有状态的测试，用State注解表示状态的作用域
Thread: 该状态为每个线程独享
Group: 该状态为同一个组里面所有线程共享
Benchmark: 该状态在所有线程间共享

demo: http://hg.openjdk.java.net/code-tools/jmh/file/cb9aa824b55a/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_03_States.java
 */
@State(Scope.Benchmark)
public class JMHBenchmarkExample {
    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(JMHBenchmarkExample.class.getSimpleName())
                .forks(1) // 用一个进程测试
                /*
                iteration是JMH进行测试的最小单位。在大部分模式下，一次iteration代表的是一秒，JMH会在这一秒内不断调用需要benchmark的
                方法，然后根据模式对其采样，计算吞吐量，计算平均执行时间等
                 */
                .measurementIterations(5)
                .measurementTime(TimeValue.seconds(1)) // 每次测试的时间
                /*
                预热次数
                为什么需要预热？因为JVM的JIT机制的存在，如果某个函数被调用多次之后，JVM会尝试将其编译成为机器码从而提高执行速度。所以为
                了让benchmark的结果更加接近真实情况就需要进行预热。
                 */
                .warmupIterations(5)
                .warmupTime(TimeValue.seconds(1)) // 每次预热的时间
                .timeUnit(TimeUnit.MILLISECONDS) // 测试输出的单位
                .threads(Runtime.getRuntime().availableProcessors()) // 测试线程数
                /*
                 以方法执行时间为测试目标，可选的有：
                 Throughput: 整体吞吐量，例如“1秒内可以执行多少次调用”
                 AverageTime: 调用的平均时间，例如“每次调用平均耗时xxx毫秒”
                 SampleTime: 随机取样，最后输出取样结果的分布，例如“99%的调用在xxx毫秒以内，99.99%的调用在xxx毫秒以内”
                 SingleShotTime: 以上模式都是默认一次iteration是1s，唯有SingleShotTime是只运行一次。往往同时把warmup次数设为0，用于测试冷启动时的性能
                 */
                .mode(Mode.AverageTime)
                .build();

        new Runner(opt).run();
    }

    @Benchmark
    public int sleepAWhile() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            // ignore
        }
        return 0;
    }

    @Setup
    public void prepare() {
        // start test
    }

    @TearDown
    public void shutdown() {
        // end test
    }
}

/*
下面是测试的输出：
# JMH version: 1.23
# VM version: JDK 1.8.0_191, Java HotSpot(TM) 64-Bit Server VM, 25.191-b12
# VM invoker: /Library/Java/JavaVirtualMachines/jdk1.8.0_191.jdk/Contents/Home/jre/bin/java
# VM options: -javaagent:/Users/dhf/Library/Application Support/JetBrains/Toolbox/apps/IDEA-U/ch-0/192.7142.36/IntelliJ IDEA.app/Contents/lib/idea_rt.jar=60311:/Users/dhf/Library/Application Support/JetBrains/Toolbox/apps/IDEA-U/ch-0/192.7142.36/IntelliJ IDEA.app/Contents/bin -Dfile.encoding=UTF-8
# Warmup: 5 iterations, 1 s each
# Measurement: 5 iterations, 1 s each
# Timeout: 10 min per iteration
# Threads: 4 threads, will synchronize iterations
# Benchmark mode: Average time, time/op
# Benchmark: com.dhf.demo.JMHBenchmarkExample.sleepAWhile

# Run progress: 0.00% complete, ETA 00:00:10
# Fork: 1 of 1
# Warmup Iteration   1: 501.593 ±(99.9%) 0.319 ms/op # 预热5次，每次其实都是一次正常的测试
# Warmup Iteration   2: 501.789 ±(99.9%) 1.484 ms/op
# Warmup Iteration   3: 502.281 ±(99.9%) 2.317 ms/op
# Warmup Iteration   4: 501.286 ±(99.9%) 0.652 ms/op
# Warmup Iteration   5: 500.804 ±(99.9%) 6.172 ms/op
Iteration   1: 502.096 ±(99.9%) 3.277 ms/op # 测试5次
Iteration   2: 500.536 ±(99.9%) 0.612 ms/op
Iteration   3: 503.384 ±(99.9%) 3.885 ms/op
Iteration   4: 501.153 ±(99.9%) 0.594 ms/op
Iteration   5: 500.827 ±(99.9%) 7.099 ms/op


Result "com.dhf.demo.JMHBenchmarkExample.sleepAWhile":
  501.599 ±(99.9%) 4.457 ms/op [Average]
  (min, avg, max) = (500.536, 501.599, 503.384), stdev = 1.158
  CI (99.9%): [497.142, 506.056] (assumes normal distribution)


# Run complete. Total time: 00:00:18

REMEMBER: The numbers below are just data. To gain reusable insights, you need to follow up on
why the numbers are the way they are. Use profilers (see -prof, -lprof), design factorial
experiments, perform baseline and negative tests that provide experimental control, make sure
the benchmarking environment is safe on JVM/OS/HW level, ask for reviews from the domain experts.
Do not assume the numbers tell you what you want them to tell.

# Score就是结果，值为501.599 ± 4.457
Benchmark                                            Mode  Cnt    Score   Error  Units
JMHBenchmarkExample.sleepAWhile  avgt    5  501.599 ± 4.457  ms/op
 */
