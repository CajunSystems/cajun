package com.cajunsystems.benchmarks;

import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Main benchmark runner for organized benchmark execution.
 * 
 * Usage:
 * 
 * # Run all stateless benchmarks (mailbox, backpressure, etc.)
 * java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*stateless.*"
 * 
 * # Run all stateful benchmarks (with file cleanup)
 * java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*stateful.*"
 * 
 * # Run only mailbox comparisons
 * java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*MailboxComparison.*"
 * 
 * # Run only comparison benchmark in isolation
 * java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*ComparisonBenchmark.*"
 * 
 * # Run only backpressure tests
 * java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*Backpressure.*"
 */
public class BenchmarkRunner {

    public static void main(String[] args) throws RunnerException {
        String pattern = args.length > 0 ? args[0] : ".*stateless.*";
        
        Options opt = new OptionsBuilder()
                .include(pattern)
                .build();
        
        System.out.println("Running benchmarks with pattern: " + pattern);
        new Runner(opt).run();
    }
}
