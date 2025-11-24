package com.cajunsystems.benchmarks;

import com.cajunsystems.ActorContext;
import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.handler.Handler;
import com.cajunsystems.mailbox.LinkedMailbox;
import com.cajunsystems.mailbox.MpscMailbox;
import com.cajunsystems.mailbox.config.MailboxProvider;
import org.openjdk.jmh.annotations.*;

import java.io.Serializable;
import java.util.concurrent.*;
import java.util.stream.IntStream;

/**
 * Enhanced benchmark comparing:
 * 1. Different mailbox implementations (LinkedMailbox vs MpscMailbox)
 * 2. I/O-bound vs CPU-bound workloads
 * 3. Actors vs Threads vs Structured Concurrency
 *
 * Key insight: Actors with virtual threads should excel at I/O-bound workloads
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(2)
public class EnhancedWorkloadBenchmark {

    private ActorSystem actorSystem;
    private ExecutorService executor;

    // Actors with different mailbox implementations
    private Pid linkedMailboxActor;
    private Pid mpscMailboxActor;
    private Pid[] linkedMailboxPool;
    private Pid[] mpscMailboxPool;

    private static final int POOL_SIZE = 10;
    private static final int WORKLOAD_SIZE = 100;

    // Workload parameters
    private static final int CPU_ITERATIONS = 20;
    private static final int IO_DELAY_MS = 10;  // Simulate 10ms I/O operation

    // Actor message types
    public sealed interface WorkMessage extends Serializable {
        record CpuWork(CompletableFuture<Long> result) implements WorkMessage {
            private static final long serialVersionUID = 1L;
        }
        record IoWork(CompletableFuture<Long> result) implements WorkMessage {
            private static final long serialVersionUID = 1L;
        }
        record MixedWork(CompletableFuture<Long> result) implements WorkMessage {
            private static final long serialVersionUID = 1L;
        }
    }

    // Actor handler
    public static class WorkHandler implements Handler<WorkMessage> {
        @Override
        public void receive(WorkMessage message, ActorContext context) {
            switch (message) {
                case WorkMessage.CpuWork work -> {
                    long result = cpuBoundWork(CPU_ITERATIONS);
                    work.result().complete(result);
                }
                case WorkMessage.IoWork work -> {
                    long result = ioBoundWork(IO_DELAY_MS);
                    work.result().complete(result);
                }
                case WorkMessage.MixedWork work -> {
                    long result = mixedWork();
                    work.result().complete(result);
                }
            }
        }
    }

    // Workload implementations
    private static long cpuBoundWork(int iterations) {
        long sum = 0;
        for (int i = 0; i < iterations; i++) {
            sum += fibonacci(15);
        }
        return sum;
    }

    private static long ioBoundWork(int delayMs) {
        try {
            // Simulate I/O with virtual thread-friendly blocking
            Thread.sleep(delayMs);
            return System.currentTimeMillis();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    private static long mixedWork() {
        // Do some CPU work
        long cpuResult = cpuBoundWork(5);
        // Then some I/O
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return cpuResult + System.currentTimeMillis();
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

    @Setup(Level.Trial)
    public void setupTrial() {
        actorSystem = new ActorSystem();
        executor = Executors.newVirtualThreadPerTaskExecutor();

        // Custom mailbox providers
        MailboxProvider<WorkMessage> linkedProvider = (config, workloadHint) ->
            new LinkedMailbox<>(config != null ? config.getMaxCapacity() : 10000);

        MailboxProvider<WorkMessage> mpscProvider = (config, workloadHint) ->
            new MpscMailbox<>(128);

        // Single actors with different mailboxes
        linkedMailboxActor = actorSystem.actorOf(WorkHandler.class)
            .withId("linked-actor")
            .withMailboxProvider(linkedProvider)
            .spawn();

        mpscMailboxActor = actorSystem.actorOf(WorkHandler.class)
            .withId("mpsc-actor")
            .withMailboxProvider(mpscProvider)
            .spawn();

        // Actor pools with different mailboxes
        linkedMailboxPool = new Pid[POOL_SIZE];
        mpscMailboxPool = new Pid[POOL_SIZE];

        for (int i = 0; i < POOL_SIZE; i++) {
            linkedMailboxPool[i] = actorSystem.actorOf(WorkHandler.class)
                .withId("linked-pool-" + i)
                .withMailboxProvider(linkedProvider)
                .spawn();

            mpscMailboxPool[i] = actorSystem.actorOf(WorkHandler.class)
                .withId("mpsc-pool-" + i)
                .withMailboxProvider(mpscProvider)
                .spawn();
        }
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

    // ==================== CPU-BOUND WORKLOADS ====================

    @Benchmark
    public long cpuBound_Actors_LinkedMailbox() throws Exception {
        CompletableFuture<Long> result = new CompletableFuture<>();
        linkedMailboxActor.tell(new WorkMessage.CpuWork(result));
        return result.get(5, TimeUnit.SECONDS);
    }

    @Benchmark
    public long cpuBound_Actors_MpscMailbox() throws Exception {
        CompletableFuture<Long> result = new CompletableFuture<>();
        mpscMailboxActor.tell(new WorkMessage.CpuWork(result));
        return result.get(5, TimeUnit.SECONDS);
    }

    @Benchmark
    public long cpuBound_Threads() throws Exception {
        Future<Long> future = executor.submit(() -> cpuBoundWork(CPU_ITERATIONS));
        return future.get();
    }

    @Benchmark
    public long cpuBound_StructuredConcurrency() throws Exception {
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            var task = scope.fork(() -> cpuBoundWork(CPU_ITERATIONS));
            scope.join();
            scope.throwIfFailed();
            return task.get();
        }
    }

    // ==================== I/O-BOUND WORKLOADS ====================

    @Benchmark
    public long ioBound_Actors_LinkedMailbox() throws Exception {
        CompletableFuture<Long> result = new CompletableFuture<>();
        linkedMailboxActor.tell(new WorkMessage.IoWork(result));
        return result.get(15, TimeUnit.SECONDS);
    }

    @Benchmark
    public long ioBound_Actors_MpscMailbox() throws Exception {
        CompletableFuture<Long> result = new CompletableFuture<>();
        mpscMailboxActor.tell(new WorkMessage.IoWork(result));
        return result.get(15, TimeUnit.SECONDS);
    }

    @Benchmark
    public long ioBound_Threads() throws Exception {
        Future<Long> future = executor.submit(() -> ioBoundWork(IO_DELAY_MS));
        return future.get();
    }

    @Benchmark
    public long ioBound_StructuredConcurrency() throws Exception {
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            var task = scope.fork(() -> ioBoundWork(IO_DELAY_MS));
            scope.join();
            scope.throwIfFailed();
            return task.get();
        }
    }

    // ==================== PARALLEL I/O WORKLOADS ====================

    @Benchmark
    @OperationsPerInvocation(WORKLOAD_SIZE)
    public long parallelIo_Actors_LinkedMailbox() throws Exception {
        CompletableFuture<Long>[] results = new CompletableFuture[WORKLOAD_SIZE];

        for (int i = 0; i < WORKLOAD_SIZE; i++) {
            results[i] = new CompletableFuture<>();
            linkedMailboxPool[i % POOL_SIZE].tell(new WorkMessage.IoWork(results[i]));
        }

        long sum = 0;
        for (CompletableFuture<Long> result : results) {
            sum += result.get(20, TimeUnit.SECONDS);
        }
        return sum;
    }

    @Benchmark
    @OperationsPerInvocation(WORKLOAD_SIZE)
    public long parallelIo_Actors_MpscMailbox() throws Exception {
        CompletableFuture<Long>[] results = new CompletableFuture[WORKLOAD_SIZE];

        for (int i = 0; i < WORKLOAD_SIZE; i++) {
            results[i] = new CompletableFuture<>();
            mpscMailboxPool[i % POOL_SIZE].tell(new WorkMessage.IoWork(results[i]));
        }

        long sum = 0;
        for (CompletableFuture<Long> result : results) {
            sum += result.get(20, TimeUnit.SECONDS);
        }
        return sum;
    }

    @Benchmark
    @OperationsPerInvocation(WORKLOAD_SIZE)
    public long parallelIo_Threads() throws Exception {
        CompletableFuture<Long>[] futures = new CompletableFuture[WORKLOAD_SIZE];

        for (int i = 0; i < WORKLOAD_SIZE; i++) {
            futures[i] = CompletableFuture.supplyAsync(
                () -> ioBoundWork(IO_DELAY_MS),
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
    @OperationsPerInvocation(WORKLOAD_SIZE)
    public long parallelIo_StructuredConcurrency() throws Exception {
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            var tasks = IntStream.range(0, WORKLOAD_SIZE)
                .mapToObj(i -> scope.fork(() -> ioBoundWork(IO_DELAY_MS)))
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

    // ==================== MIXED WORKLOADS ====================

    @Benchmark
    public long mixed_Actors_LinkedMailbox() throws Exception {
        CompletableFuture<Long> result = new CompletableFuture<>();
        linkedMailboxActor.tell(new WorkMessage.MixedWork(result));
        return result.get(10, TimeUnit.SECONDS);
    }

    @Benchmark
    public long mixed_Actors_MpscMailbox() throws Exception {
        CompletableFuture<Long> result = new CompletableFuture<>();
        mpscMailboxActor.tell(new WorkMessage.MixedWork(result));
        return result.get(10, TimeUnit.SECONDS);
    }

    @Benchmark
    public long mixed_Threads() throws Exception {
        Future<Long> future = executor.submit(() -> mixedWork());
        return future.get();
    }

    @Benchmark
    public long mixed_StructuredConcurrency() throws Exception {
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            var task = scope.fork(() -> mixedWork());
            scope.join();
            scope.throwIfFailed();
            return task.get();
        }
    }
}

