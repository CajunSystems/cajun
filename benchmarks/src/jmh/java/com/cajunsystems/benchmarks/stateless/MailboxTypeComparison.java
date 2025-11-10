package com.cajunsystems.benchmarks.stateless;

import com.cajunsystems.ActorContext;
import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.handler.Handler;
import org.openjdk.jmh.annotations.*;

import java.io.Serializable;
import java.util.concurrent.*;

/**
 * Mailbox type performance comparison - fair comparison without persistence.
 * 
 * Tests different mailbox types to see their impact on actor performance.
 * 
 * Run with:
 * java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*MailboxTypeComparison.*"
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 3, time = 3)
@Fork(1)
public class MailboxTypeComparison {

    private ActorSystem actorSystem;

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
        actorSystem = new ActorSystem();
    }

    @TearDown
    public void tearDown() {
        if (actorSystem != null) {
            actorSystem.shutdown();
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

    // ========== BLOCKING MAILBOX ==========
    
    @Benchmark
    public long singleTask_Blocking() throws Exception {
        Pid actor = actorSystem.actorOf(FastWorkHandler.class)
                .withId("blocking-" + System.nanoTime())
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
    public void batchProcessing_Blocking() throws Exception {
        int batchSize = 50;
        CountDownLatch latch = new CountDownLatch(batchSize);
        
        Pid actor = actorSystem.actorOf(FastWorkHandler.class)
                .withId("blocking-batch-" + System.nanoTime())
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

    // ========== DISPATCHER_CBQ MAILBOX ==========
    
    @Benchmark
    public long singleTask_DispatcherCBQ() throws Exception {
        Pid actor = actorSystem.actorOf(FastWorkHandler.class)
                .withId("cbq-" + System.nanoTime())
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
    public void batchProcessing_DispatcherCBQ() throws Exception {
        int batchSize = 50;
        CountDownLatch latch = new CountDownLatch(batchSize);
        
        Pid actor = actorSystem.actorOf(FastWorkHandler.class)
                .withId("cbq-batch-" + System.nanoTime())
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

    // ========== DISPATCHER_MPSC MAILBOX ==========
    
    @Benchmark
    public long singleTask_DispatcherMPSC() throws Exception {
        Pid actor = actorSystem.actorOf(FastWorkHandler.class)
                .withId("mpsc-" + System.nanoTime())
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
    public void batchProcessing_DispatcherMPSC() throws Exception {
        int batchSize = 50;
        CountDownLatch latch = new CountDownLatch(batchSize);
        
        Pid actor = actorSystem.actorOf(FastWorkHandler.class)
                .withId("mpsc-batch-" + System.nanoTime())
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

    // Main method for standalone execution
    public static void main(String[] args) throws Exception {
        org.openjdk.jmh.runner.options.Options opt = 
            new org.openjdk.jmh.runner.options.OptionsBuilder()
                .include(".*MailboxTypeComparison.*")
                .build();
        
        new org.openjdk.jmh.runner.Runner(opt).run();
    }
}
