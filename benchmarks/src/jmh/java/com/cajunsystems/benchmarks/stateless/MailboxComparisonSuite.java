package com.cajunsystems.benchmarks.stateless;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.TimeUnit;

/**
 * Stateless mailbox comparison benchmarks.
 * 
 * These benchmarks test pure mailbox performance without any file I/O
 * or persistent state. They can be run independently and don't require cleanup.
 * 
 * Run with:
 * java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*MailboxComparisonSuite.*"
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 1, time = 3, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 5, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class MailboxComparisonSuite {

    /**
     * Main method to run mailbox comparison benchmarks independently.
     */
    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(".*MailboxComparisonSuite.*")
                .build();
        
        new Runner(opt).run();
    }

    /**
     * Delegate to the dramatic mailbox comparison for actual benchmarking.
     */
    @Benchmark
    public long measureMailboxThroughput() throws InterruptedException {
        return new DramaticMailboxComparison().measureMailboxThroughput();
    }
}
