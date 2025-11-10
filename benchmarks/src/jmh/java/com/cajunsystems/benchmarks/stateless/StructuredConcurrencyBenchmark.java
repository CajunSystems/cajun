package com.cajunsystems.benchmarks.stateless;

import org.openjdk.jmh.annotations.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * Benchmarks for Java 21 Structured Concurrency patterns.
 * Demonstrates modern approaches to concurrent programming.
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(2)
public class StructuredConcurrencyBenchmark {

    private AtomicInteger counter;

    @Setup
    public void setup() {
        counter = new AtomicInteger(0);
    }

    /**
     * Benchmark: Simple task with StructuredTaskScope
     * Measures overhead of structured concurrency
     */
    @Benchmark
    public void structuredTaskSingle() throws Exception {
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            var task = scope.fork(() -> {
                counter.incrementAndGet();
                return counter.get();
            });

            scope.join();
            scope.throwIfFailed();
            task.get();
        }
    }

    /**
     * Benchmark: Multiple parallel tasks with structured concurrency
     * Comparable to multi-actor scenarios
     */
    @Benchmark
    @OperationsPerInvocation(10)
    public void structuredTaskMultiple() throws Exception {
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            var tasks = IntStream.range(0, 10)
                .mapToObj(i -> scope.fork(() -> {
                    counter.incrementAndGet();
                    return i;
                }))
                .toList();

            scope.join();
            scope.throwIfFailed();

            for (var task : tasks) {
                task.get();
            }
        }
    }

    /**
     * Benchmark: Structured concurrency with computation
     * Comparable to actor request-reply with compute
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    public int structuredTaskWithCompute() throws Exception {
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            var task = scope.fork(() -> fibonacci(20));

            scope.join();
            scope.throwIfFailed();

            return task.get();
        }
    }

    private int fibonacci(int n) {
        if (n <= 1) return n;
        int a = 0, b = 1;
        for (int i = 2; i <= n; i++) {
            int temp = a + b;
            a = b;
            b = temp;
        }
        return b;
    }

    /**
     * Benchmark: Shutdown on first success pattern
     * Demonstrates racing multiple tasks
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    public int structuredTaskRace() throws Exception {
        try (var scope = new StructuredTaskScope.ShutdownOnSuccess<Integer>()) {
            // Fork multiple tasks, first to complete wins
            scope.fork(() -> fibonacci(15));
            scope.fork(() -> fibonacci(16));
            scope.fork(() -> fibonacci(17));

            scope.join();

            return scope.result();
        }
    }

    /**
     * Benchmark: Parallel computation with aggregation
     * Tests structured concurrency for parallel work
     */
    @Benchmark
    @OperationsPerInvocation(100)
    public int structuredTaskAggregation() throws Exception {
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            var tasks = IntStream.range(0, 100)
                .mapToObj(i -> scope.fork(() -> fibonacci(10)))
                .toList();

            scope.join();
            scope.throwIfFailed();

            int sum = 0;
            for (var task : tasks) {
                sum += task.get();
            }
            return sum;
        }
    }

    /**
     * Benchmark: Nested structured task scopes
     * Tests hierarchical task organization
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    public int nestedStructuredTasks() throws Exception {
        try (var outerScope = new StructuredTaskScope.ShutdownOnFailure()) {
            var outerTask = outerScope.fork(() -> {
                try (var innerScope = new StructuredTaskScope.ShutdownOnFailure()) {
                    var task1 = innerScope.fork(() -> fibonacci(10));
                    var task2 = innerScope.fork(() -> fibonacci(12));

                    innerScope.join();
                    innerScope.throwIfFailed();

                    return task1.get() + task2.get();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });

            outerScope.join();
            outerScope.throwIfFailed();

            return outerTask.get();
        }
    }

    /**
     * Benchmark: Error handling with shutdown on failure
     * Tests fail-fast behavior
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    public void structuredTaskWithFailure() throws Exception {
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            scope.fork(() -> {
                counter.incrementAndGet();
                return 1;
            });
            scope.fork(() -> {
                counter.incrementAndGet();
                return 2;
            });
            scope.fork(() -> {
                counter.incrementAndGet();
                return 3;
            });

            scope.join();
            scope.throwIfFailed();
        }
    }

    /**
     * Benchmark: High-volume parallel tasks
     * Comparable to actor message bursts
     */
    @Benchmark
    @OperationsPerInvocation(1000)
    public void structuredTaskBurst() throws Exception {
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            var tasks = IntStream.range(0, 1000)
                .mapToObj(i -> scope.fork(() -> {
                    counter.incrementAndGet();
                    return i;
                }))
                .toList();

            scope.join();
            scope.throwIfFailed();

            for (var task : tasks) {
                task.get();
            }
        }
    }

    /**
     * Benchmark: Mixed CPU and IO-bound tasks
     * Tests handling of different workload types
     */
    @Benchmark
    @OperationsPerInvocation(20)
    public void structuredTaskMixedWorkload() throws Exception {
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            var tasks = IntStream.range(0, 20)
                .mapToObj(i -> {
                    if (i % 2 == 0) {
                        // CPU-bound task
                        return scope.fork(() -> fibonacci(15));
                    } else {
                        // Simulated IO-bound task
                        return scope.fork(() -> {
                            Thread.sleep(1);
                            return counter.incrementAndGet();
                        });
                    }
                })
                .toList();

            scope.join();
            scope.throwIfFailed();

            int sum = 0;
            for (var task : tasks) {
                sum += task.get();
            }
        }
    }

    /**
     * Benchmark: Comparison with virtual threads directly
     * Shows overhead of StructuredTaskScope
     */
    @Benchmark
    @OperationsPerInvocation(10)
    public void virtualThreadsDirect() throws Exception {
        CountDownLatch latch = new CountDownLatch(10);
        for (int i = 0; i < 10; i++) {
            Thread.ofVirtual().start(() -> {
                counter.incrementAndGet();
                latch.countDown();
            });
        }
        latch.await(5, TimeUnit.SECONDS);
    }
}
