package com.cajunsystems.benchmarks.stateful;

import com.cajunsystems.ActorContext;
import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.handler.Handler;
import org.openjdk.jmh.annotations.*;

import java.io.Serializable;
import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * IO-oriented comparison benchmarks running identical workloads across
 * actors, threads, and structured concurrency.
 *
 * These are designed to highlight scenarios where virtual threads and
 * actor-based coordination are expected to perform well, since the
 * dominant cost is blocking/waiting rather than pure CPU.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(2)
public class ComparisonBenchmarkIO {

    private ActorSystem actorSystem;
    private ExecutorService executor;

    // Reusable pool of IO actors for fairer batch comparisons
    private Pid[] ioActorPool;
    private static final int IO_ACTOR_POOL_SIZE = Runtime.getRuntime().availableProcessors();

    // Shared workload parameters
    private static final int WORKLOAD_SIZE = 100;
    private static final long IO_MILLIS = 5L;

    // Actor message types for IO
    public sealed interface IoMessage extends Serializable {
        record Single(long millis, CompletableFuture<Long> result) implements IoMessage {
            private static final long serialVersionUID = 1L;
        }
        record Batch(int count, CountDownLatch latch, long millis) implements IoMessage {
            private static final long serialVersionUID = 1L;
        }
    }

    // Actor handler for IO-bound work
    public static class IoWorkHandler implements Handler<IoMessage> {
        @Override
        public void receive(IoMessage message, ActorContext context) {
            switch (message) {
                case IoMessage.Single single -> {
                    long result = doIoWork(single.millis());
                    single.result().complete(result);
                }
                case IoMessage.Batch batch -> {
                    for (int i = 0; i < batch.count(); i++) {
                        doIoWork(batch.millis());
                    }
                    batch.latch().countDown();
                }
            }
        }

        private long doIoWork(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return millis;
        }
    }

    @Setup
    public void setup() {
        actorSystem = new ActorSystem();
        executor = Executors.newVirtualThreadPerTaskExecutor();

        // Initialize a reusable pool of IO actors
        ioActorPool = new Pid[IO_ACTOR_POOL_SIZE];
        for (int i = 0; i < IO_ACTOR_POOL_SIZE; i++) {
            ioActorPool[i] = actorSystem.actorOf(IoWorkHandler.class)
                .withId("io-pool-worker-" + i)
                .spawn();
        }
    }

    @TearDown
    public void tearDown() {
        if (actorSystem != null) {
            actorSystem.shutdown();
        }
        if (executor != null) {
            executor.shutdown();
        }

        cleanupTempFiles();
    }

    // Same cleanup as ComparisonBenchmark
    private void cleanupTempFiles() {
        String tempDir = System.getProperty("java.io.tmpdir");
        try {
            try (Stream<Path> paths = Files.list(Paths.get(tempDir))) {
                paths.filter(path -> {
                    String fileName = path.getFileName().toString();
                    return fileName.startsWith("cajun-benchmark-") ||
                           fileName.endsWith(".wal") ||
                           fileName.endsWith(".snapshot") ||
                           fileName.contains("cajun-test") ||
                           fileName.contains("actor-");
                })
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        // ignore cleanup failures in benchmarks
                    }
                });
            }
        } catch (IOException e) {
            // ignore cleanup failures in benchmarks
        }
    }

    // Helper method for IO-bound work used by threads/structured variants
    private static long doIoWorkStatic(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return millis;
    }

    /**
     * Scenario 1: Single IO-bound task
     */
    @Benchmark
    public long singleTaskIO_Actors() throws Exception {
        Pid worker = actorSystem.actorOf(IoWorkHandler.class)
            .withId("io-worker-" + System.nanoTime())
            .spawn();

        try {
            CompletableFuture<Long> result = new CompletableFuture<>();
            worker.tell(new IoMessage.Single(IO_MILLIS, result));
            return result.get(5, TimeUnit.SECONDS);
        } finally {
            actorSystem.stopActor(worker);
        }
    }

    @Benchmark
    public long singleTaskIO_Threads() throws Exception {
        Future<Long> future = executor.submit(() -> doIoWorkStatic(IO_MILLIS));
        return future.get();
    }

    @Benchmark
    public long singleTaskIO_StructuredConcurrency() throws Exception {
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            var task = scope.fork(() -> doIoWorkStatic(IO_MILLIS));
            scope.join();
            scope.throwIfFailed();
            return task.get();
        }
    }

    /**
     * Scenario 2: Parallel batch of IO-bound tasks
     */
    @Benchmark
    @OperationsPerInvocation(WORKLOAD_SIZE)
    public void batchProcessingIO_Actors() throws Exception {
        CountDownLatch latch = new CountDownLatch(WORKLOAD_SIZE);

        Pid[] workers = new Pid[WORKLOAD_SIZE];
        for (int i = 0; i < WORKLOAD_SIZE; i++) {
            workers[i] = actorSystem.actorOf(IoWorkHandler.class)
                .withId("io-batch-worker-" + i + "-" + System.nanoTime())
                .spawn();
            workers[i].tell(new IoMessage.Batch(1, latch, IO_MILLIS));
        }

        latch.await(30, TimeUnit.SECONDS);

        for (Pid worker : workers) {
            actorSystem.stopActor(worker);
        }
    }

    /**
     * Scenario 2 (pooled): Parallel batch of IO-bound tasks using a reusable actor pool.
     * This avoids per-task actor creation and is closer to how actors would be used in
     * a real system, making the comparison with threads/structured concurrency fairer.
     */
    @Benchmark
    @OperationsPerInvocation(WORKLOAD_SIZE)
    public void batchProcessingIO_Actors_Pooled() throws Exception {
        CountDownLatch latch = new CountDownLatch(WORKLOAD_SIZE);

        for (int i = 0; i < WORKLOAD_SIZE; i++) {
            Pid worker = ioActorPool[i % IO_ACTOR_POOL_SIZE];
            worker.tell(new IoMessage.Batch(1, latch, IO_MILLIS));
        }

        latch.await(30, TimeUnit.SECONDS);
    }

    @Benchmark
    @OperationsPerInvocation(WORKLOAD_SIZE)
    public void batchProcessingIO_Threads() throws Exception {
        CountDownLatch latch = new CountDownLatch(WORKLOAD_SIZE);

        for (int i = 0; i < WORKLOAD_SIZE; i++) {
            executor.submit(() -> {
                doIoWorkStatic(IO_MILLIS);
                latch.countDown();
            });
        }

        latch.await(30, TimeUnit.SECONDS);
    }

    @Benchmark
    @OperationsPerInvocation(WORKLOAD_SIZE)
    public void batchProcessingIO_StructuredConcurrency() throws Exception {
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            var tasks = IntStream.range(0, WORKLOAD_SIZE)
                .mapToObj(i -> scope.fork(() -> doIoWorkStatic(IO_MILLIS)))
                .toList();

            scope.join();
            scope.throwIfFailed();

            for (var task : tasks) {
                task.get();
            }
        }
    }
}
