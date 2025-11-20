package com.cajunsystems.benchmarks;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.handler.Handler;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/**
 * Benchmark specifically measuring actor creation overhead.
 * 
 * This helps quantify the cost of actor creation vs actual work execution.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(2)
public class ActorCreationBenchmark {

    private ActorSystem actorSystem;

    // Simple handler for creation testing
    public static class SimpleHandler implements Handler<String> {
        @Override
        public void receive(String message, com.cajunsystems.ActorContext context) {
            // Do nothing
        }
    }

    @Setup(Level.Trial)
    public void setup() {
        actorSystem = new ActorSystem();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (actorSystem != null) {
            actorSystem.shutdown();
        }
    }

    /**
     * Measure single actor creation overhead
     */
    @Benchmark
    public Pid createSingleActor() {
        return actorSystem.actorOf(SimpleHandler.class)
            .withId("test-actor-" + System.nanoTime())
            .spawn();
    }

    /**
     * Measure single actor creation and destruction
     */
    @Benchmark
    public void createAndDestroySingleActor() {
        Pid actor = actorSystem.actorOf(SimpleHandler.class)
            .withId("test-actor-" + System.nanoTime())
            .spawn();
        actorSystem.stopActor(actor);
    }

    /**
     * Measure batch actor creation (100 actors)
     */
    @Benchmark
    @OperationsPerInvocation(100)
    public Pid[] createBatchActors() {
        Pid[] actors = new Pid[100];
        for (int i = 0; i < 100; i++) {
            actors[i] = actorSystem.actorOf(SimpleHandler.class)
                .withId("batch-actor-" + i + "-" + System.nanoTime())
                .spawn();
        }
        return actors;
    }

    /**
     * Measure batch actor creation and destruction (100 actors)
     */
    @Benchmark
    @OperationsPerInvocation(100)
    public void createAndDestroyBatchActors() {
        Pid[] actors = new Pid[100];
        for (int i = 0; i < 100; i++) {
            actors[i] = actorSystem.actorOf(SimpleHandler.class)
                .withId("batch-actor-" + i + "-" + System.nanoTime())
                .spawn();
        }
        
        // Clean up
        for (Pid actor : actors) {
            actorSystem.stopActor(actor);
        }
    }
}
