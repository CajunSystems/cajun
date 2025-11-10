package com.cajunsystems.benchmarks.stateless;

import com.cajunsystems.benchmarks.DirectBackpressureTest;
import com.cajunsystems.benchmarks.ExtremeBackpressureTest;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.TimeUnit;

/**
 * Backpressure comparison benchmarks - stateless.
 * 
 * These benchmarks test backpressure system performance without file I/O.
 * They can be run independently and don't require cleanup.
 * 
 * Run with:
 * java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*BackpressureComparisonSuite.*"
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 1, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 3, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class BackpressureComparisonSuite {

    /**
     * Main method to run backpressure benchmarks independently.
     */
    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(".*BackpressureComparisonSuite.*")
                .build();
        
        new Runner(opt).run();
    }

    /**
     * Test direct backpressure scenarios.
     */
    @Benchmark
    public void testSystemLevelBackpressure() throws Exception {
        new DirectBackpressureTest().testSystemLevelBackpressure();
    }

    /**
     * Test extreme backpressure scenarios.
     */
    @Benchmark
    public void testExtremeBackpressure() throws Exception {
        new ExtremeBackpressureTest().testExtremeBackpressure();
    }
}
