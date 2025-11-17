package com.cajunsystems.benchmarks.stateful;

import com.cajunsystems.ActorContext;
import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.handler.Handler;
import com.cajunsystems.handler.StatefulHandler;
import org.openjdk.jmh.annotations.*;

import java.io.Serializable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Benchmarks for actor-based concurrency patterns.
 * Tests message passing throughput, request-reply latency, and state management.
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(2)
public class ActorBenchmark {

    private ActorSystem system;
    private Pid pingPongActor;
    private Pid counterActor;
    private Pid computeActor;

    // Track actors created for cleanup
    private final java.util.List<Pid> actorsToCleanup = new java.util.concurrent.CopyOnWriteArrayList<>();

    // Message types
    public sealed interface PingPongMessage extends Serializable {
        record Ping() implements PingPongMessage {
            private static final long serialVersionUID = 1L;
        }
        record Pong() implements PingPongMessage {
            private static final long serialVersionUID = 1L;
        }
        record Stop(CountDownLatch latch) implements PingPongMessage {
            private static final long serialVersionUID = 1L;
        }
    }

    public sealed interface CounterMessage extends Serializable {
        record Increment(int amount) implements CounterMessage {
            private static final long serialVersionUID = 1L;
        }
        record GetCount(CompletableFuture<Integer> result) implements CounterMessage {
            private static final long serialVersionUID = 1L;
        }
        record Reset() implements CounterMessage {
            private static final long serialVersionUID = 1L;
        }
    }

    public sealed interface ComputeMessage extends Serializable {
        record Calculate(int value, CompletableFuture<Integer> result) implements ComputeMessage {
            private static final long serialVersionUID = 1L;
        }
    }

    // Actor implementations
    public static class PingPongHandler implements Handler<PingPongMessage> {
        private long messageCount = 0;

        @Override
        public void receive(PingPongMessage message, ActorContext context) {
            switch (message) {
                case PingPongMessage.Ping ping -> {
                    messageCount++;
                    context.getSender().ifPresent(sender ->
                        sender.tell(new PingPongMessage.Pong())
                    );
                }
                case PingPongMessage.Pong pong -> {
                    messageCount++;
                    context.getSender().ifPresent(sender ->
                        sender.tell(new PingPongMessage.Ping())
                    );
                }
                case PingPongMessage.Stop stop -> {
                    stop.latch().countDown();
                }
            }
        }
    }

    public static class CounterHandler implements StatefulHandler<Integer, CounterMessage> {
        @Override
        public Integer receive(CounterMessage message, Integer state, ActorContext context) {
            return switch (message) {
                case CounterMessage.Increment inc -> state + inc.amount();
                case CounterMessage.GetCount get -> {
                    get.result().complete(state);
                    yield state;
                }
                case CounterMessage.Reset reset -> 0;
            };
        }
    }

    public static class ComputeHandler implements Handler<ComputeMessage> {
        @Override
        public void receive(ComputeMessage message, ActorContext context) {
            if (message instanceof ComputeMessage.Calculate calc) {
                // Simulate some computation
                int result = fibonacci(calc.value());
                calc.result().complete(result);
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
    }

    @Setup
    public void setup() {
        system = new ActorSystem();

        pingPongActor = system.actorOf(PingPongHandler.class)
            .withId("ping-pong")
            .spawn();

        // Stateful actor with aggressive snapshot/truncation strategy for benchmarks
        // Take snapshot every 1000 changes to ensure frequent truncation
        // This prevents unbounded journal growth during high-throughput benchmarks
        com.cajunsystems.persistence.TruncationConfig benchmarkTruncation = 
            com.cajunsystems.persistence.TruncationConfig.builder()
                .enableSnapshotBasedTruncation(true)
                .snapshotsToKeep(2)  // Keep only 2 snapshots for benchmarks
                .truncateJournalOnSnapshot(true)
                .changesBeforeSnapshot(1000)  // Snapshot every 1000 changes
                .snapshotIntervalMs(5000)  // Or every 5 seconds
                .build();
        
        counterActor = system.statefulActorOf(CounterHandler.class, 0)
            .withId("counter")
            .withTruncationConfig(benchmarkTruncation)
            .spawn();

        computeActor = system.actorOf(ComputeHandler.class)
            .withId("compute")
            .spawn();
    }

    @TearDown
    public void tearDown() {
        if (system != null) {
            system.shutdown();
        }
    }

    /**
     * Benchmark: Message passing throughput (fire-and-forget)
     * Measures how many messages can be sent per second
     */
    @Benchmark
    public void messageThroughput() {
        counterActor.tell(new CounterMessage.Increment(1));
    }

    /**
     * Benchmark: Ping-pong message exchange
     * Measures round-trip message latency
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    public void pingPongLatency() throws Exception {
        CountDownLatch latch = new CountDownLatch(2);

        Pid actor1 = system.actorOf(PingPongHandler.class)
            .withId("ping-" + System.nanoTime())
            .spawn();

        Pid actor2 = system.actorOf(PingPongHandler.class)
            .withId("pong-" + System.nanoTime())
            .spawn();

        // Start ping-pong exchange (50 rounds = 100 messages)
        for (int i = 0; i < 50; i++) {
            actor1.tell(new PingPongMessage.Ping());
            actor2.tell(new PingPongMessage.Pong());
        }

        // Stop actors
        actor1.tell(new PingPongMessage.Stop(latch));
        actor2.tell(new PingPongMessage.Stop(latch));

        latch.await(5, TimeUnit.SECONDS);

        // Clean up actors
        system.stopActor(actor1);
        system.stopActor(actor2);
    }

    /**
     * Benchmark: Stateful actor state updates
     * Measures performance of state-changing operations
     */
    @Benchmark
    public void statefulUpdates() {
        counterActor.tell(new CounterMessage.Increment(1));
    }

    /**
     * Benchmark: Actor creation and spawning
     * Measures the overhead of creating new actors
     */
    @Benchmark
    public void actorCreation() {
        Pid pid = system.actorOf(PingPongHandler.class)
            .withId("temp-" + System.nanoTime())
            .spawn();
        // Track for cleanup to prevent memory leaks
        actorsToCleanup.add(pid);
    }

    /**
     * Clean up actors created during actorCreation benchmark
     */
    @TearDown(Level.Iteration)
    public void cleanupCreatedActors() {
        for (Pid pid : actorsToCleanup) {
            try {
                system.stopActor(pid);
            } catch (Exception e) {
                // Ignore cleanup errors
            }
        }
        actorsToCleanup.clear();
    }

    /**
     * Benchmark: Request-reply pattern with computation
     * Measures end-to-end latency including computation
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    public int requestReplyWithCompute() throws Exception {
        CompletableFuture<Integer> result = new CompletableFuture<>();
        computeActor.tell(new ComputeMessage.Calculate(20, result));
        return result.get(1, TimeUnit.SECONDS);
    }

    /**
     * Benchmark: High-volume message bursts
     * Tests system behavior under load
     */
    @Benchmark
    @OperationsPerInvocation(1000)
    public void messageBurst() {
        for (int i = 0; i < 1000; i++) {
            counterActor.tell(new CounterMessage.Increment(1));
        }
    }

    /**
     * Benchmark: Multiple actors concurrent message processing
     * Tests scalability with multiple actors
     */
    @Benchmark
    @OperationsPerInvocation(100)
    public void multiActorConcurrency() throws Exception {
        CountDownLatch latch = new CountDownLatch(10);

        // Create 10 actors
        Pid[] actors = new Pid[10];
        for (int i = 0; i < 10; i++) {
            actors[i] = system.actorOf(PingPongHandler.class)
                .withId("concurrent-" + i + "-" + System.nanoTime())
                .spawn();
        }

        // Send 10 messages to each (100 total messages)
        for (int i = 0; i < 10; i++) {
            for (Pid actor : actors) {
                actor.tell(new PingPongMessage.Ping());
            }
        }

        // Stop all actors
        for (Pid actor : actors) {
            actor.tell(new PingPongMessage.Stop(latch));
        }

        latch.await(5, TimeUnit.SECONDS);

        // Clean up actors
        for (Pid actor : actors) {
            system.stopActor(actor);
        }
    }
}
