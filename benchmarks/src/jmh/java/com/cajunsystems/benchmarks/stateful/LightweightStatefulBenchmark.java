package com.cajunsystems.benchmarks.stateful;

import com.cajunsystems.ActorContext;
import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.handler.StatefulHandler;
import org.openjdk.jmh.annotations.*;

import java.io.Serializable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Lightweight Stateful Actor Benchmark that minimizes filesystem impact.
 * 
 * This benchmark focuses on in-memory state operations with minimal persistence
 * overhead to avoid overwhelming the filesystem during testing.
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 1, time = 1) // Minimal warmup
@Measurement(iterations = 2, time = 2) // Minimal measurement
@Fork(1) // Single fork to minimize resource usage
public class LightweightStatefulBenchmark {

    private ActorSystem system;
    
    // Test actors
    private Pid counter;
    private Pid accumulator;
    private Pid processor;

    // Message types for testing
    public sealed interface StateMessage extends Serializable {
        record Increment(int value) implements StateMessage {
            private static final long serialVersionUID = 1L;
        }
        record Get(CompletableFuture<Integer> result) implements StateMessage {
            private static final long serialVersionUID = 1L;
        }
        record Reset() implements StateMessage {
            private static final long serialVersionUID = 1L;
        }
        record Compute(int value, CompletableFuture<Integer> result) implements StateMessage {
            private static final long serialVersionUID = 1L;
        }
    }

    // Stateful handlers
    public static class CounterHandler implements StatefulHandler<Integer, StateMessage> {
        @Override
        public Integer receive(StateMessage message, Integer state, ActorContext context) {
            return switch (message) {
                case StateMessage.Increment inc -> state + inc.value();
                case StateMessage.Get get -> {
                    get.result().complete(state);
                    yield state;
                }
                case StateMessage.Reset reset -> 0;
                case StateMessage.Compute compute -> {
                    // Lightweight computation
                    int result = state + (compute.value() * compute.value());
                    compute.result().complete(result);
                    yield result;
                }
            };
        }
    }

    public static class AccumulatorHandler implements StatefulHandler<Long, StateMessage> {
        @Override
        public Long receive(StateMessage message, Long state, ActorContext context) {
            return switch (message) {
                case StateMessage.Increment inc -> state + inc.value();
                case StateMessage.Get get -> {
                    get.result().complete(state.intValue());
                    yield state;
                }
                case StateMessage.Reset reset -> 0L;
                case StateMessage.Compute compute -> {
                    long result = state + compute.value() * 2;
                    compute.result().complete((int) result);
                    yield result;
                }
            };
        }
    }

    @Setup
    public void setup() {
        // Initialize single system
        system = new ActorSystem();
        
        // Create test actors
        counter = system.statefulActorOf(CounterHandler.class, 0)
            .withId("counter-" + System.nanoTime())
            .spawn();
            
        accumulator = system.statefulActorOf(AccumulatorHandler.class, 0L)
            .withId("accumulator-" + System.nanoTime())
            .spawn();
            
        processor = system.statefulActorOf(CounterHandler.class, 0)
            .withId("processor-" + System.nanoTime())
            .spawn();
    }

    @TearDown
    public void tearDown() {
        if (system != null) {
            system.shutdown();
        }
    }

    // ========== LIGHTWEIGHT BENCHMARKS ==========
    
    /**
     * Benchmark: Single state update operation
     */
    @Benchmark
    public void singleStateUpdate() {
        counter.tell(new StateMessage.Increment(1));
    }

    /**
     * Benchmark: State read operation
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    public int stateRead() throws Exception {
        CompletableFuture<Integer> result = new CompletableFuture<>();
        counter.tell(new StateMessage.Get(result));
        return result.get(2, TimeUnit.SECONDS);
    }

    /**
     * Benchmark: Stateful computation
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    public int statefulComputation() throws Exception {
        CompletableFuture<Integer> result = new CompletableFuture<>();
        processor.tell(new StateMessage.Compute(10, result));
        return result.get(2, TimeUnit.SECONDS);
    }

    /**
     * Benchmark: Accumulator operations
     */
    @Benchmark
    public void accumulatorUpdate() {
        accumulator.tell(new StateMessage.Increment(5));
    }

    /**
     * Benchmark: State reset operation
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    public void stateReset() throws Exception {
        counter.tell(new StateMessage.Reset());
        // Small delay to ensure reset is processed
        Thread.sleep(1);
    }

    /**
     * Benchmark: Mixed operations pattern
     */
    @Benchmark
    @OperationsPerInvocation(10)
    public void mixedOperations() throws Exception {
        CountDownLatch latch = new CountDownLatch(10);
        
        // Mix of different operations
        for (int i = 0; i < 10; i++) {
            counter.tell(new StateMessage.Increment(i));
            if (i % 3 == 0) {
                accumulator.tell(new StateMessage.Increment(i * 2));
            }
            latch.countDown();
        }
        
        latch.await(3, TimeUnit.SECONDS);
    }

    /**
     * Main method to run lightweight benchmarks independently
     */
    public static void main(String[] args) throws Exception {
        org.openjdk.jmh.runner.options.Options opt = new org.openjdk.jmh.runner.options.OptionsBuilder()
                .include(".*LightweightStatefulBenchmark.*")
                .build();
        
        new org.openjdk.jmh.runner.Runner(opt).run();
    }
}
