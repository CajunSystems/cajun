package com.cajunsystems.benchmarks;

import com.cajunsystems.ActorContext;
import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.handler.Handler;
import org.openjdk.jmh.annotations.*;

import java.io.Serializable;
import java.util.concurrent.*;
import java.util.stream.IntStream;

/**
 * Fair comparison benchmarks that separate actor creation from actual work.
 * 
 * Actors are created once in @Setup and reused across benchmark iterations,
 * making the comparison with threads and structured concurrency fair.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(2)
public class FairComparisonBenchmark {

    private ActorSystem actorSystem;
    private ExecutorService executor;
    
    // Pre-created actors for fair comparison
    private Pid singleWorker;
    private Pid[] batchWorkers;
    private Pid[] scatterGatherWorkers;
    private Pid pipelineStage1;
    private Pid pipelineStage2;

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

    @Setup(Level.Trial)
    public void setupTrial() {
        actorSystem = new ActorSystem();
        executor = Executors.newVirtualThreadPerTaskExecutor();
        
        // Create actors once for the entire benchmark trial
        singleWorker = actorSystem.actorOf(WorkHandler.class)
            .withId("single-worker")
            .spawn();
            
        // Create batch workers once
        batchWorkers = new Pid[WORKLOAD_SIZE];
        for (int i = 0; i < WORKLOAD_SIZE; i++) {
            batchWorkers[i] = actorSystem.actorOf(WorkHandler.class)
                .withId("batch-worker-" + i)
                .spawn();
        }

        // Create scatter-gather workers once
        scatterGatherWorkers = new Pid[10];
        for (int i = 0; i < 10; i++) {
            scatterGatherWorkers[i] = actorSystem.actorOf(WorkHandler.class)
                .withId("sg-worker-" + i)
                .spawn();
        }

        // Create pipeline workers once
        pipelineStage1 = actorSystem.actorOf(WorkHandler.class)
            .withId("pipeline-stage1")
            .spawn();
        pipelineStage2 = actorSystem.actorOf(WorkHandler.class)
            .withId("pipeline-stage2")
            .spawn();
    }

    @TearDown(Level.Trial)
    public void tearDownTrial() {
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
     * FAIR Scenario 1: Single task with computation
     * Actor is created once per invocation, not per measurement
     */
    @Benchmark
    public long singleTask_Actors_Fair() throws Exception {
        CompletableFuture<Long> result = new CompletableFuture<>();
        singleWorker.tell(new WorkMessage.Compute(COMPUTE_ITERATIONS, result));
        return result.get(5, TimeUnit.SECONDS);
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
     * FAIR Scenario 2: Pre-created actors for batch processing
     */
    @Benchmark
    @OperationsPerInvocation(WORKLOAD_SIZE)
    public void batchProcessing_Actors_PreCreated() throws Exception {
        CountDownLatch latch = new CountDownLatch(WORKLOAD_SIZE);

        for (int i = 0; i < WORKLOAD_SIZE; i++) {
            batchWorkers[i].tell(new WorkMessage.Batch(1, latch));
        }

        latch.await(10, TimeUnit.SECONDS);
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
     * FAIR Scenario 3: Pre-created actors for request-reply
     */
    @Benchmark
    public long requestReply_Actors_PreCreated() throws Exception {
        CompletableFuture<Long> result = new CompletableFuture<>();
        singleWorker.tell(new WorkMessage.Compute(COMPUTE_ITERATIONS, result));
        return result.get(5, TimeUnit.SECONDS);
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
     * FAIR Scenario 4: Pre-created pipeline actors
     */
    @Benchmark
    public long pipeline_Actors_PreCreated() throws Exception {
        CompletableFuture<Long> result1 = new CompletableFuture<>();
        pipelineStage1.tell(new WorkMessage.Compute(10, result1));
        long r1 = result1.get(5, TimeUnit.SECONDS);

        CompletableFuture<Long> result2 = new CompletableFuture<>();
        pipelineStage2.tell(new WorkMessage.Compute(10, result2));
        long r2 = result2.get(5, TimeUnit.SECONDS);

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
     * FAIR Scenario 5: Pre-created scatter-gather actors
     */
    @Benchmark
    @OperationsPerInvocation(10)
    public long scatterGather_Actors_PreCreated() throws Exception {
        CompletableFuture<Long>[] results = new CompletableFuture[10];

        for (int i = 0; i < 10; i++) {
            results[i] = new CompletableFuture<>();
            scatterGatherWorkers[i].tell(new WorkMessage.Compute(10, results[i]));
        }

        long sum = 0;
        for (CompletableFuture<Long> result : results) {
            sum += result.get(5, TimeUnit.SECONDS);
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
