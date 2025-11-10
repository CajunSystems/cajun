package com.cajunsystems.benchmarks.stateless;

import com.cajunsystems.ActorContext;
import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.config.MailboxConfig;
import com.cajunsystems.config.ThreadPoolFactory;
import com.cajunsystems.config.DefaultMailboxProvider;
import com.cajunsystems.dispatcher.MailboxType;
import com.cajunsystems.handler.Handler;
import org.openjdk.jmh.annotations.*;

import java.io.Serializable;
import java.util.concurrent.*;

/**
 * Fair comparison benchmark - actors vs threads vs structured concurrency.
 * 
 * This benchmarks core concurrency models without persistence overhead.
 * Actors run in-memory only, threads and structured concurrency are baseline.
 * Tests different mailbox types to see performance impact.
 * 
 * Run with:
 * java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*FairComparisonBenchmark.*"
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(2)
public class FairComparisonBenchmark {

    @Param({"BLOCKING", "DISPATCHER_CBQ", "DISPATCHER_MPSC"})
    public String mailboxType;

    private ActorSystem actorSystem;
    private ExecutorService executor;

    // Workload parameters
    private static final int COMPUTE_ITERATIONS = 20;

    // Message types for actors
    public sealed interface WorkMessage extends Serializable {
        record Compute(int iterations, CompletableFuture<Long> result) implements WorkMessage {
            private static final long serialVersionUID = 1L;
        }
        record Batch(int count, CountDownLatch latch) implements WorkMessage {
            private static final long serialVersionUID = 1L;
        }
    }

    // Fast actor handler - no persistence, minimal processing
    public static class FastWorkHandler implements Handler<WorkMessage> {
        @Override
        public void receive(WorkMessage message, ActorContext context) {
            switch (message) {
                case WorkMessage.Compute compute -> {
                    long result = doWork(compute.iterations());
                    compute.result().complete(result);
                }
                case WorkMessage.Batch batch -> {
                    for (int i = 0; i < batch.count(); i++) {
                        doWork(10); // Light work
                    }
                    batch.latch().countDown();
                }
            }
        }
    }

    @Setup
    public void setup() {
        System.out.println("=== FAIR COMPARISON: " + mailboxType + " mailbox type ===");
        
        // Create mailbox config based on parameter
        MailboxConfig mailboxConfig = createMailboxConfig();
        
        // Create actor system with specific mailbox type
        actorSystem = new ActorSystem(new ThreadPoolFactory(), null, 
                                    mailboxConfig, new DefaultMailboxProvider<>());
        executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Create mailbox configuration based on parameter.
     */
    private MailboxConfig createMailboxConfig() {
        MailboxConfig config = new MailboxConfig();
        
        switch (mailboxType) {
            case "BLOCKING":
                config.setMailboxType(MailboxType.BLOCKING)
                      .setInitialCapacity(1024)
                      .setMaxCapacity(2048);
                break;
            case "DISPATCHER_CBQ":
                config.setMailboxType(MailboxType.DISPATCHER_CBQ)
                      .setMaxCapacity(8192)
                      .setThroughput(64);
                break;
            case "DISPATCHER_MPSC":
                config.setMailboxType(MailboxType.DISPATCHER_MPSC)
                      .setMaxCapacity(8192)  // Power of 2 for MPSC
                      .setThroughput(128);
                break;
            default:
                throw new IllegalArgumentException("Unknown mailbox type: " + mailboxType);
        }
        
        return config;
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

    private static long fibonacci(int n) {
        if (n <= 1) return n;
        return fibonacci(n - 1) + fibonacci(n - 2);
    }

    // ========== SINGLE TASK COMPARISON ==========
    
    @Benchmark
    public long singleTask_Actors() throws Exception {
        Pid actor = actorSystem.actorOf(FastWorkHandler.class)
                .withId("worker-" + mailboxType + "-" + System.nanoTime())
                .spawn();

        try {
            CompletableFuture<Long> result = new CompletableFuture<>();
            actor.tell(new WorkMessage.Compute(COMPUTE_ITERATIONS, result));
            return result.get(5, TimeUnit.SECONDS);
        } finally {
            actorSystem.stopActor(actor);
        }
    }

    @Benchmark
    public long singleTask_Threads() throws Exception {
        CompletableFuture<Long> future = CompletableFuture.supplyAsync(() -> 
            doWork(COMPUTE_ITERATIONS), executor);
        return future.get();
    }

    @Benchmark
    public long singleTask_StructuredConcurrency() throws Exception {
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            var future = scope.fork(() -> doWork(COMPUTE_ITERATIONS));
            scope.join();
            scope.throwIfFailed();
            return future.get();
        }
    }

    // ========== BATCH PROCESSING COMPARISON ==========

    @Benchmark
    public void batchProcessing_Actors() throws Exception {
        int batchSize = 50;
        CountDownLatch latch = new CountDownLatch(batchSize);
        
        Pid actor = actorSystem.actorOf(FastWorkHandler.class)
                .withId("batch-" + mailboxType + "-" + System.nanoTime())
                .spawn();

        try {
            for (int i = 0; i < batchSize; i++) {
                actor.tell(new WorkMessage.Batch(10, latch));
            }
            latch.await();
        } finally {
            actorSystem.stopActor(actor);
        }
    }

    @Benchmark
    public void batchProcessing_Threads() throws Exception {
        int batchSize = 50;
        CountDownLatch latch = new CountDownLatch(batchSize);
        
        for (int i = 0; i < batchSize; i++) {
            executor.submit(() -> {
                doWork(10);
                latch.countDown();
            });
        }
        
        latch.await();
    }

    @Benchmark
    public void batchProcessing_StructuredConcurrency() throws Exception {
        int batchSize = 50;
        
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            for (int i = 0; i < batchSize; i++) {
                scope.fork(() -> doWork(10));
            }
            scope.join();
            scope.throwIfFailed();
        }
    }

    // ========== PIPELINE COMPARISON ==========

    @Benchmark
    public long pipeline_Actors() throws Exception {
        // Create pipeline actors
        Pid stage1 = actorSystem.actorOf(FastWorkHandler.class)
                .withId("stage1-" + mailboxType + "-" + System.nanoTime())
                .spawn();

        Pid stage2 = actorSystem.actorOf(FastWorkHandler.class)
                .withId("stage2-" + mailboxType + "-" + System.nanoTime())
                .spawn();

        try {
            CompletableFuture<Long> result = new CompletableFuture<>();
            
            // Pipeline: input -> stage1 -> stage2 -> output
            CompletableFuture<Long> stage1Result = new CompletableFuture<>();
            stage1.tell(new WorkMessage.Compute(15, stage1Result));
            
            stage1Result.thenAccept(res -> {
                stage2.tell(new WorkMessage.Compute(15, result));
            });
            
            return result.get(5, TimeUnit.SECONDS);
        } finally {
            actorSystem.stopActor(stage1);
            actorSystem.stopActor(stage2);
        }
    }

    @Benchmark
    public long pipeline_Threads() throws Exception {
        return CompletableFuture.supplyAsync(() -> doWork(15), executor)
                .thenCompose(res -> CompletableFuture.supplyAsync(() -> doWork(15), executor))
                .get();
    }

    @Benchmark
    public long pipeline_StructuredConcurrency() throws Exception {
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            var stage1 = scope.fork(() -> doWork(15));
            scope.join();
            scope.throwIfFailed();
            
            var stage2 = scope.fork(() -> doWork(15));
            scope.join();
            scope.throwIfFailed();
            
            return stage2.get();
        }
    }

    // Main method for standalone execution
    public static void main(String[] args) throws Exception {
        org.openjdk.jmh.runner.options.Options opt = 
            new org.openjdk.jmh.runner.options.OptionsBuilder()
                .include(".*FairComparisonBenchmark.*")
                .build();
        
        new org.openjdk.jmh.runner.Runner(opt).run();
    }
}
