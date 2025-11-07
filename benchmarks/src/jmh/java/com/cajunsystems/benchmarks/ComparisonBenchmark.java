package com.cajunsystems.benchmarks;

import com.cajunsystems.ActorContext;
import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.handler.Handler;
import org.openjdk.jmh.annotations.*;

import java.io.Serializable;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * Direct comparison benchmarks running identical workloads across
 * actors, threads, and structured concurrency.
 *
 * This allows for apples-to-apples comparison of the three approaches.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(2)
public class ComparisonBenchmark {

    private ActorSystem actorSystem;
    private ExecutorService executor;

    // Shared workload parameters
    private static final int WORKLOAD_SIZE = 100;
    private static final int COMPUTE_ITERATIONS = 20;

    // Actor message types
    public sealed interface WorkMessage extends Serializable {
        record Compute(int iterations, CompletableFuture<Long> result) implements WorkMessage {
            private static final long serialVersionUID = 1L;
        }
        record Batch(int count, CountDownLatch latch) implements WorkMessage {
            private static final long serialVersionUID = 1L;
        }
    }

    // Actor handler
    public static class WorkHandler implements Handler<WorkMessage> {
        @Override
        public void receive(WorkMessage message, ActorContext context) {
            switch (message) {
                case WorkMessage.Compute compute -> {
                    long result = doWork(compute.iterations());
                    compute.result().complete(result);
                }
                case WorkMessage.Batch batch -> {
                    for (int i = 0; i < batch.count(); i++) {
                        doWork(COMPUTE_ITERATIONS);
                    }
                    batch.latch().countDown();
                }
            }
        }

        private long doWork(int iterations) {
            long sum = 0;
            for (int i = 0; i < iterations; i++) {
                sum += fibonacci(15);
            }
            return sum;
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
    }

    @Setup
    public void setup() {
        actorSystem = new ActorSystem();
        executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @TearDown
    public void tearDown() {
        if (actorSystem != null) {
            actorSystem.shutdown();
        }
        if (executor != null) {
            executor.shutdown();
        }
    }

    // Helper method for CPU-bound work
    private static long doWork(int iterations) {
        long sum = 0;
        for (int i = 0; i < iterations; i++) {
            sum += fibonacci(15);
        }
        return sum;
    }

    private static int fibonacci(int n) {
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
     * Scenario 1: Single task with computation
     */
    @Benchmark
    public long singleTask_Actors() throws Exception {
        Pid worker = actorSystem.actorOf(WorkHandler.class)
            .withId("worker-" + System.nanoTime())
            .spawn();

        try {
            CompletableFuture<Long> result = new CompletableFuture<>();
            worker.tell(new WorkMessage.Compute(COMPUTE_ITERATIONS, result));
            return result.get(5, TimeUnit.SECONDS);
        } finally {
            actorSystem.stopActor(worker);
        }
    }

    @Benchmark
    public long singleTask_Threads() throws Exception {
        Future<Long> future = executor.submit(() -> doWork(COMPUTE_ITERATIONS));
        return future.get();
    }

    @Benchmark
    public long singleTask_StructuredConcurrency() throws Exception {
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            var task = scope.fork(() -> doWork(COMPUTE_ITERATIONS));
            scope.join();
            scope.throwIfFailed();
            return task.get();
        }
    }

    /**
     * Scenario 2: Parallel batch processing (100 tasks)
     */
    @Benchmark
    @OperationsPerInvocation(WORKLOAD_SIZE)
    public void batchProcessing_Actors() throws Exception {
        CountDownLatch latch = new CountDownLatch(WORKLOAD_SIZE);

        Pid[] workers = new Pid[WORKLOAD_SIZE];
        for (int i = 0; i < WORKLOAD_SIZE; i++) {
            workers[i] = actorSystem.actorOf(WorkHandler.class)
                .withId("batch-worker-" + i + "-" + System.nanoTime())
                .spawn();
            workers[i].tell(new WorkMessage.Batch(1, latch));
        }

        latch.await(10, TimeUnit.SECONDS);

        // Clean up actors
        for (Pid worker : workers) {
            actorSystem.stopActor(worker);
        }
    }

    @Benchmark
    @OperationsPerInvocation(WORKLOAD_SIZE)
    public void batchProcessing_Threads() throws Exception {
        CountDownLatch latch = new CountDownLatch(WORKLOAD_SIZE);

        for (int i = 0; i < WORKLOAD_SIZE; i++) {
            executor.submit(() -> {
                doWork(COMPUTE_ITERATIONS);
                latch.countDown();
            });
        }

        latch.await(10, TimeUnit.SECONDS);
    }

    @Benchmark
    @OperationsPerInvocation(WORKLOAD_SIZE)
    public void batchProcessing_StructuredConcurrency() throws Exception {
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            var tasks = IntStream.range(0, WORKLOAD_SIZE)
                .mapToObj(i -> scope.fork(() -> doWork(COMPUTE_ITERATIONS)))
                .toList();

            scope.join();
            scope.throwIfFailed();

            for (var task : tasks) {
                task.get();
            }
        }
    }

    /**
     * Scenario 3: Request-reply pattern
     */
    @Benchmark
    public long requestReply_Actors() throws Exception {
        Pid worker = actorSystem.actorOf(WorkHandler.class)
            .withId("rr-worker-" + System.nanoTime())
            .spawn();

        try {
            CompletableFuture<Long> result = new CompletableFuture<>();
            worker.tell(new WorkMessage.Compute(COMPUTE_ITERATIONS, result));
            return result.get(5, TimeUnit.SECONDS);
        } finally {
            actorSystem.stopActor(worker);
        }
    }

    @Benchmark
    public long requestReply_Threads() throws Exception {
        CompletableFuture<Long> future = CompletableFuture.supplyAsync(
            () -> doWork(COMPUTE_ITERATIONS),
            executor
        );
        return future.get();
    }

    @Benchmark
    public long requestReply_StructuredConcurrency() throws Exception {
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            var task = scope.fork(() -> doWork(COMPUTE_ITERATIONS));
            scope.join();
            scope.throwIfFailed();
            return task.get();
        }
    }

    /**
     * Scenario 4: Pipeline processing (sequential stages)
     */
    @Benchmark
    public long pipeline_Actors() throws Exception {
        // Stage 1
        Pid stage1 = actorSystem.actorOf(WorkHandler.class)
            .withId("pipeline-1-" + System.nanoTime())
            .spawn();
        CompletableFuture<Long> result1 = new CompletableFuture<>();
        stage1.tell(new WorkMessage.Compute(10, result1));
        long r1 = result1.get(5, TimeUnit.SECONDS);

        // Stage 2
        Pid stage2 = actorSystem.actorOf(WorkHandler.class)
            .withId("pipeline-2-" + System.nanoTime())
            .spawn();
        CompletableFuture<Long> result2 = new CompletableFuture<>();
        stage2.tell(new WorkMessage.Compute(10, result2));
        long r2 = result2.get(5, TimeUnit.SECONDS);

        // Clean up actors
        actorSystem.stopActor(stage1);
        actorSystem.stopActor(stage2);

        return r1 + r2;
    }

    @Benchmark
    public long pipeline_Threads() throws Exception {
        CompletableFuture<Long> stage1 = CompletableFuture.supplyAsync(
            () -> doWork(10),
            executor
        );
        CompletableFuture<Long> stage2 = stage1.thenApplyAsync(
            result -> result + doWork(10),
            executor
        );
        return stage2.get();
    }

    @Benchmark
    public long pipeline_StructuredConcurrency() throws Exception {
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            var task1 = scope.fork(() -> doWork(10));
            scope.join();
            scope.throwIfFailed();
            long result1 = task1.get();

            // Second stage
            try (var scope2 = new StructuredTaskScope.ShutdownOnFailure()) {
                var task2 = scope2.fork(() -> result1 + doWork(10));
                scope2.join();
                scope2.throwIfFailed();
                return task2.get();
            }
        }
    }

    /**
     * Scenario 5: Scatter-gather pattern
     */
    @Benchmark
    @OperationsPerInvocation(10)
    public long scatterGather_Actors() throws Exception {
        Pid[] workers = new Pid[10];
        CompletableFuture<Long>[] results = new CompletableFuture[10];

        for (int i = 0; i < 10; i++) {
            workers[i] = actorSystem.actorOf(WorkHandler.class)
                .withId("sg-worker-" + i + "-" + System.nanoTime())
                .spawn();

            results[i] = new CompletableFuture<>();
            final int index = i;
            workers[i].tell(new WorkMessage.Compute(10, results[index]));
        }

        long sum = 0;
        for (CompletableFuture<Long> result : results) {
            sum += result.get(5, TimeUnit.SECONDS);
        }

        // Clean up actors
        for (Pid worker : workers) {
            actorSystem.stopActor(worker);
        }

        return sum;
    }

    @Benchmark
    @OperationsPerInvocation(10)
    public long scatterGather_Threads() throws Exception {
        CompletableFuture<Long>[] futures = new CompletableFuture[10];

        for (int i = 0; i < 10; i++) {
            futures[i] = CompletableFuture.supplyAsync(
                () -> doWork(10),
                executor
            );
        }

        long sum = 0;
        for (CompletableFuture<Long> future : futures) {
            sum += future.get();
        }
        return sum;
    }

    @Benchmark
    @OperationsPerInvocation(10)
    public long scatterGather_StructuredConcurrency() throws Exception {
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            var tasks = IntStream.range(0, 10)
                .mapToObj(i -> scope.fork(() -> doWork(10)))
                .toList();

            scope.join();
            scope.throwIfFailed();

            long sum = 0;
            for (var task : tasks) {
                sum += task.get();
            }
            return sum;
        }
    }
}
