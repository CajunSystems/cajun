package com.cajunsystems.benchmarks.stateful;

import com.cajunsystems.ActorContext;
import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.handler.StatefulHandler;
import org.openjdk.jmh.annotations.*;

import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * LMDB Stateful Actor Benchmark
 * 
 * Compares performance of stateful actors using:
 * 1. Default file-based persistence
 * 2. LMDB-based persistence (Phase 1 implementation)
 * 
 * This benchmark measures the impact of persistence backend on stateful actor performance.
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(2)
public class LmdbStatefulBenchmark {

    private ActorSystem fileBasedSystem;
    private ActorSystem lmdbSystem;
    
    private Path tempDir;
    private Path lmdbDir;
    
    // Test actors
    private Pid fileBasedCounter;
    private Pid lmdbCounter;
    private Pid fileBasedAccumulator;
    private Pid lmdbAccumulator;

    // Message types for testing
    public sealed interface StateMessage extends Serializable {
        record Increment(int value) implements StateMessage {
            private static final long serialVersionUID = 1L;
        }
        record Get() implements StateMessage {
            private static final long serialVersionUID = 1L;
        }
        record Reset() implements StateMessage {
            private static final long serialVersionUID = 1L;
        }
        record Batch(int count) implements StateMessage {
            private static final long serialVersionUID = 1L;
        }
        record Compute(int value) implements StateMessage {
            private static final long serialVersionUID = 1L;
        }
    }

    // Stateful handlers
    public static class CounterHandler implements StatefulHandler<Integer, StateMessage> {
        @Override
        public Integer receive(StateMessage message, Integer state, ActorContext context) {
            return switch (message) {
                case StateMessage.Increment inc -> state + inc.value();
                case StateMessage.Get get -> state; // Just return current state
                case StateMessage.Reset reset -> 0;
                case StateMessage.Batch batch -> {
                    for (int i = 0; i < batch.count(); i++) {
                        state = state + 1; // Increment by 1 for each message in batch
                    }
                    yield state;
                }
                case StateMessage.Compute compute -> {
                    // Simulate some computation
                    int result = state + fibonacci(compute.value());
                    yield result;
                }
            };
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
    }

    public static class AccumulatorHandler implements StatefulHandler<Long, StateMessage> {
        @Override
        public Long receive(StateMessage message, Long state, ActorContext context) {
            return switch (message) {
                case StateMessage.Increment inc -> state + inc.value();
                case StateMessage.Get get -> state; // Just return current state
                case StateMessage.Reset reset -> 0L;
                case StateMessage.Batch batch -> {
                    for (int i = 0; i < batch.count(); i++) {
                        state = state + i; // Accumulate sequence numbers
                    }
                    yield state;
                }
                case StateMessage.Compute compute -> {
                    long result = state + compute.value() * 2;
                    yield result;
                }
            };
        }
    }

    @Setup
    public void setup() throws IOException {
        // Create temporary directories
        tempDir = Files.createTempDirectory("cajun-benchmark-file-");
        lmdbDir = Files.createTempDirectory("cajun-benchmark-lmdb-");
        
        // Initialize file-based system (default)
        fileBasedSystem = new ActorSystem();
        
        // Initialize LMDB system
        lmdbSystem = new ActorSystem();
        
        // Create test actors
        fileBasedCounter = fileBasedSystem.statefulActorOf(CounterHandler.class, 0)
            .withId("file-counter-" + System.nanoTime())
            .spawn();
            
        lmdbCounter = lmdbSystem.statefulActorOf(CounterHandler.class, 0)
            .withId("lmdb-counter-" + System.nanoTime())
            .spawn();
            
        fileBasedAccumulator = fileBasedSystem.statefulActorOf(AccumulatorHandler.class, 0L)
            .withId("file-accumulator-" + System.nanoTime())
            .spawn();
            
        lmdbAccumulator = lmdbSystem.statefulActorOf(AccumulatorHandler.class, 0L)
            .withId("lmdb-accumulator-" + System.nanoTime())
            .spawn();
    }

    @TearDown
    public void tearDown() {
        // Shutdown systems
        if (fileBasedSystem != null) {
            fileBasedSystem.shutdown();
        }
        if (lmdbSystem != null) {
            lmdbSystem.shutdown();
        }
        
        // Clean up temporary directories
        cleanupDirectory(tempDir);
        cleanupDirectory(lmdbDir);
    }
    
    private void cleanupDirectory(Path dir) {
        try {
            if (Files.exists(dir)) {
                try (Stream<Path> paths = Files.walk(dir)) {
                    paths.sorted((a, b) -> b.compareTo(a)) // Reverse order for deletion
                          .forEach(path -> {
                              try {
                                  Files.deleteIfExists(path);
                              } catch (IOException e) {
                                  System.err.println("Failed to delete: " + path + " - " + e.getMessage());
                              }
                          });
                }
                System.out.println("Cleaned up directory: " + dir);
            }
        } catch (IOException e) {
            System.err.println("Error cleaning up directory " + dir + ": " + e.getMessage());
        }
    }

    // ========== BENCHMARKS ==========
    
    /**
     * Benchmark: Single state update operation
     * Compares file-based vs LMDB persistence for simple state updates
     */
    @Benchmark
    public void singleStateUpdate_FileBased() {
        fileBasedCounter.tell(new StateMessage.Increment(1));
    }
    
    @Benchmark
    public void singleStateUpdate_LMDB() {
        lmdbCounter.tell(new StateMessage.Increment(1));
    }

    /**
     * Benchmark: State read operation
     * Measures performance of reading current state
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    public void stateRead_FileBased() {
        fileBasedCounter.tell(new StateMessage.Get());
    }
    
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    public void stateRead_LMDB() {
        lmdbCounter.tell(new StateMessage.Get());
    }

    /**
     * Benchmark: Batch state updates
     * Tests performance of multiple state operations
     */
    @Benchmark
    @OperationsPerInvocation(100)
    public void batchStateUpdates_FileBased() {
        fileBasedCounter.tell(new StateMessage.Batch(100));
    }
    
    @Benchmark
    @OperationsPerInvocation(100)
    public void batchStateUpdates_LMDB() {
        lmdbCounter.tell(new StateMessage.Batch(100));
    }

    /**
     * Benchmark: Stateful computation
     * Measures performance of state-dependent computation
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    public void statefulComputation_FileBased() {
        fileBasedCounter.tell(new StateMessage.Compute(20));
    }
    
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    public void statefulComputation_LMDB() {
        lmdbCounter.tell(new StateMessage.Compute(20));
    }

    /**
     * Benchmark: Multiple concurrent stateful actors
     * Tests scalability with multiple stateful actors
     */
    @Benchmark
    @OperationsPerInvocation(10)
    public void multipleStatefulActors_FileBased() {
        Pid[] actors = new Pid[10];
        
        // Create 10 stateful actors
        for (int i = 0; i < 10; i++) {
            actors[i] = fileBasedSystem.statefulActorOf(CounterHandler.class, 0)
                .withId("file-multi-" + i + "-" + System.nanoTime())
                .spawn();
            actors[i].tell(new StateMessage.Batch(10));
        }
        
        // Clean up actors
        for (Pid actor : actors) {
            fileBasedSystem.stopActor(actor);
        }
    }
    
    @Benchmark
    @OperationsPerInvocation(10)
    public void multipleStatefulActors_LMDB() {
        Pid[] actors = new Pid[10];
        
        // Create 10 stateful actors
        for (int i = 0; i < 10; i++) {
            actors[i] = lmdbSystem.statefulActorOf(CounterHandler.class, 0)
                .withId("lmdb-multi-" + i + "-" + System.nanoTime())
                .spawn();
            actors[i].tell(new StateMessage.Batch(10));
        }
        
        // Clean up actors
        for (Pid actor : actors) {
            lmdbSystem.stopActor(actor);
        }
    }

    /**
     * Benchmark: High-frequency state updates
     * Simulates high-load scenario with rapid state changes
     */
    @Benchmark
    @OperationsPerInvocation(1000)
    public void highFrequencyUpdates_FileBased() {
        for (int i = 0; i < 1000; i++) {
            fileBasedAccumulator.tell(new StateMessage.Increment(i));
        }
    }
    
    @Benchmark
    @OperationsPerInvocation(1000)
    public void highFrequencyUpdates_LMDB() {
        for (int i = 0; i < 1000; i++) {
            lmdbAccumulator.tell(new StateMessage.Increment(i));
        }
    }

    /**
     * Benchmark: State reset operation
     * Tests performance of state reset/clear operations
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    public void stateReset_FileBased() throws Exception {
        fileBasedCounter.tell(new StateMessage.Reset());
        // Small delay to ensure reset is processed
        Thread.sleep(1);
    }
    
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    public void stateReset_LMDB() throws Exception {
        lmdbCounter.tell(new StateMessage.Reset());
        // Small delay to ensure reset is processed
        Thread.sleep(1);
    }

    /**
     * Main method to run LMDB benchmarks independently
     */
    public static void main(String[] args) throws Exception {
        org.openjdk.jmh.runner.options.Options opt = new org.openjdk.jmh.runner.options.OptionsBuilder()
                .include(".*LmdbStatefulBenchmark.*")
                .build();
        
        new org.openjdk.jmh.runner.Runner(opt).run();
    }
}
