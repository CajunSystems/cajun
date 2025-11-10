package com.cajunsystems.benchmarks.stateless;

import org.openjdk.jmh.annotations.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Benchmarks for traditional thread-based concurrency patterns.
 * Provides comparison baseline against actor-based approaches.
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(2)
public class ThreadBenchmark {

    private ExecutorService executorService;
    private ExecutorService fixedThreadPool;
    private AtomicInteger counter;
    private SharedCounter sharedCounter;
    private BlockingQueue<Integer> queue;

    static class SharedCounter {
        private int count = 0;
        private final Lock lock = new ReentrantLock();

        public void increment(int amount) {
            lock.lock();
            try {
                count += amount;
            } finally {
                lock.unlock();
            }
        }

        public int getCount() {
            lock.lock();
            try {
                return count;
            } finally {
                lock.unlock();
            }
        }

        public void reset() {
            lock.lock();
            try {
                count = 0;
            } finally {
                lock.unlock();
            }
        }
    }

    static class PingPongWorker implements Runnable {
        private final BlockingQueue<String> inQueue;
        private final BlockingQueue<String> outQueue;
        private final CountDownLatch latch;
        private final int messageCount;

        PingPongWorker(BlockingQueue<String> inQueue, BlockingQueue<String> outQueue,
                       CountDownLatch latch, int messageCount) {
            this.inQueue = inQueue;
            this.outQueue = outQueue;
            this.latch = latch;
            this.messageCount = messageCount;
        }

        @Override
        public void run() {
            try {
                for (int i = 0; i < messageCount; i++) {
                    String message = inQueue.poll(1, TimeUnit.SECONDS);
                    if (message != null) {
                        outQueue.offer(message, 1, TimeUnit.SECONDS);
                    }
                }
                latch.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Setup
    public void setup() {
        executorService = Executors.newVirtualThreadPerTaskExecutor();
        fixedThreadPool = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors()
        );
        counter = new AtomicInteger(0);
        sharedCounter = new SharedCounter();
        queue = new LinkedBlockingQueue<>(10000);
    }

    @TearDown
    public void tearDown() {
        if (executorService != null) {
            executorService.shutdown();
            try {
                executorService.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (fixedThreadPool != null) {
            fixedThreadPool.shutdown();
            try {
                fixedThreadPool.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Benchmark: Atomic counter increment (thread-safe)
     * Direct comparison to actor message throughput
     */
    @Benchmark
    public void atomicIncrement() {
        counter.incrementAndGet();
    }

    /**
     * Benchmark: Lock-based counter increment
     * Shows overhead of explicit locking
     */
    @Benchmark
    public void lockedIncrement() {
        sharedCounter.increment(1);
    }

    /**
     * Benchmark: Task submission to thread pool
     * Measures overhead of thread pool task scheduling
     */
    @Benchmark
    public void taskSubmission() throws Exception {
        Future<?> future = executorService.submit(() -> counter.incrementAndGet());
        future.get();
    }

    /**
     * Benchmark: Virtual thread creation (Java 21)
     * Measures cost of creating virtual threads
     */
    @Benchmark
    public void virtualThreadCreation() throws Exception {
        Thread.ofVirtual().start(() -> counter.incrementAndGet()).join();
    }

    /**
     * Benchmark: Platform thread creation
     * Shows traditional thread overhead
     */
    @Benchmark
    public void platformThreadCreation() throws Exception {
        Thread.ofPlatform().start(() -> counter.incrementAndGet()).join();
    }

    /**
     * Benchmark: Ping-pong with blocking queues
     * Comparable to actor ping-pong
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OperationsPerInvocation(100)
    public void pingPongWithQueues() throws Exception {
        CountDownLatch latch = new CountDownLatch(2);
        BlockingQueue<String> queue1 = new LinkedBlockingQueue<>();
        BlockingQueue<String> queue2 = new LinkedBlockingQueue<>();

        Thread t1 = Thread.ofVirtual().start(
            new PingPongWorker(queue1, queue2, latch, 50)
        );
        Thread t2 = Thread.ofVirtual().start(
            new PingPongWorker(queue2, queue1, latch, 50)
        );

        // Start the ping-pong
        queue1.offer("ping");

        latch.await(5, TimeUnit.SECONDS);
        t1.join(100);
        t2.join(100);
    }

    /**
     * Benchmark: CompletableFuture computation
     * Comparable to actor request-reply
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    public int completableFutureCompute() throws Exception {
        CompletableFuture<Integer> future = CompletableFuture.supplyAsync(
            () -> fibonacci(20),
            executorService
        );
        return future.get();
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
     * Benchmark: High-volume task submission
     * Comparable to actor message bursts
     */
    @Benchmark
    @OperationsPerInvocation(1000)
    public void taskBurst() throws Exception {
        CountDownLatch latch = new CountDownLatch(1000);
        for (int i = 0; i < 1000; i++) {
            executorService.submit(() -> {
                counter.incrementAndGet();
                latch.countDown();
            });
        }
        latch.await(5, TimeUnit.SECONDS);
    }

    /**
     * Benchmark: Multiple threads with blocking queues
     * Comparable to multi-actor concurrency
     */
    @Benchmark
    @OperationsPerInvocation(100)
    public void multiThreadConcurrency() throws Exception {
        CountDownLatch latch = new CountDownLatch(100);
        BlockingQueue<String>[] queues = new BlockingQueue[10];

        for (int i = 0; i < 10; i++) {
            queues[i] = new LinkedBlockingQueue<>();
        }

        // Create 10 worker threads
        Thread[] threads = new Thread[10];
        for (int i = 0; i < 10; i++) {
            final int index = i;
            threads[i] = Thread.ofVirtual().start(() -> {
                try {
                    for (int j = 0; j < 10; j++) {
                        String msg = queues[index].poll(1, TimeUnit.SECONDS);
                        if (msg != null) {
                            counter.incrementAndGet();
                        }
                        latch.countDown();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        // Send messages to queues
        for (int i = 0; i < 10; i++) {
            for (BlockingQueue<String> queue : queues) {
                queue.offer("message");
            }
        }

        latch.await(5, TimeUnit.SECONDS);
        for (Thread thread : threads) {
            thread.join(100);
        }
    }

    /**
     * Benchmark: Fixed thread pool vs virtual threads
     * Shows difference between thread models
     */
    @Benchmark
    public void fixedThreadPoolTask() throws Exception {
        Future<?> future = fixedThreadPool.submit(() -> counter.incrementAndGet());
        future.get();
    }

    /**
     * Benchmark: Shared state contention
     * Shows cost of lock contention
     */
    @Benchmark
    @Threads(4)
    public void lockContention() {
        sharedCounter.increment(1);
    }
}
