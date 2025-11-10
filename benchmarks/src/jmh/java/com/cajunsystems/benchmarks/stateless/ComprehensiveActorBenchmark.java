package com.cajunsystems.benchmarks.stateless;

import com.cajunsystems.ActorContext;
import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.config.*;
import com.cajunsystems.dispatcher.MailboxType;
import com.cajunsystems.handler.Handler;
import com.cajunsystems.handler.StatefulHandler;
import org.openjdk.jmh.annotations.*;

import java.io.Serializable;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Comprehensive Cajun Actor Performance Benchmark
 * 
 * Focuses on actor-specific capabilities and optimizations:
 * - Stateless vs Stateful performance
 * - Mailbox type impact
 * - Backpressure effectiveness
 * - Throughput characteristics
 * - Memory and resource usage patterns
 * 
 * Run with:
 * java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*ComprehensiveActorBenchmark.*"
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 5)
@Fork(2)
public class ComprehensiveActorBenchmark {

    // Benchmark parameters
    @Param({"STATELESS", "STATEFUL"})
    public String actorType;
    
    @Param({"BLOCKING", "DISPATCHER_CBQ", "DISPATCHER_MPSC"})
    public String mailboxType;
    
    @Param({"NONE", "BASIC", "AGGRESSIVE"})
    public String backpressureType;

    private ActorSystem actorSystem;
    private AtomicLong messageCounter = new AtomicLong(0);

    // Simple message type for throughput testing
    public record WorkMessage(long id, AtomicLong counter) implements Serializable {
        private static final long serialVersionUID = 1L;
    }

    // State message for stateful actors
    public record StateMessage(int value) implements Serializable {
        private static final long serialVersionUID = 1L;
    }

    // Stateless handler
    public static class StatelessWorkHandler implements Handler<WorkMessage> {
        @Override
        public void receive(WorkMessage message, ActorContext context) {
            doLightWork();
            message.counter().incrementAndGet();
        }
    }

    // Stateful handler
    public static class StatefulWorkHandler implements StatefulHandler<Integer, WorkMessage> {
        @Override
        public Integer receive(WorkMessage message, Integer state, ActorContext context) {
            doLightWork();
            message.counter().incrementAndGet();
            return state + 1; // Increment state on each message
        }
    }

    // State update handler
    public static class StateUpdateHandler implements StatefulHandler<Integer, StateMessage> {
        @Override
        public Integer receive(StateMessage message, Integer state, ActorContext context) {
            return state + message.value();
        }
    }

    @Setup
    public void setup() {
        System.out.println("=== COMPREHENSIVE ACTOR BENCHMARK ===");
        System.out.println("Actor Type: " + actorType);
        System.out.println("Mailbox Type: " + mailboxType);
        System.out.println("Backpressure: " + backpressureType);
        
        // Create optimized mailbox configuration
        MailboxConfig mailboxConfig = createOptimizedMailboxConfig();
        
        // Create actor system with configurations
        actorSystem = new ActorSystem(new ThreadPoolFactory(), null, 
                                    mailboxConfig, new DefaultMailboxProvider<>());
        
        messageCounter.set(0);
    }

    @TearDown
    public void tearDown() {
        if (actorSystem != null) {
            actorSystem.shutdown();
        }
    }

    /**
     * Create optimized mailbox configuration based on type.
     */
    private MailboxConfig createOptimizedMailboxConfig() {
        MailboxConfig config = new MailboxConfig();
        
        switch (mailboxType) {
            case "BLOCKING":
                config.setMailboxType(MailboxType.BLOCKING)
                      .setInitialCapacity(2048)
                      .setMaxCapacity(8192);
                break;
            case "DISPATCHER_CBQ":
                config.setMailboxType(MailboxType.DISPATCHER_CBQ)
                      .setMaxCapacity(16384)
                      .setThroughput(128);
                break;
            case "DISPATCHER_MPSC":
                config.setMailboxType(MailboxType.DISPATCHER_MPSC)
                      .setMaxCapacity(16384)  // Power of 2 for MPSC
                      .setThroughput(256);
                break;
            default:
                throw new IllegalArgumentException("Unknown mailbox type: " + mailboxType);
        }
        
        return config;
    }

    /**
     * Create backpressure configuration based on type.
     */
    private BackpressureConfig createBackpressureConfig() {
        return switch (backpressureType) {
            case "NONE" -> null;
            case "BASIC" -> new BackpressureConfig()
                .setHighWatermark(0.8f)
                .setLowWatermark(0.2f);
            case "AGGRESSIVE" -> new BackpressureConfig()
                .setHighWatermark(0.7f)
                .setLowWatermark(0.1f)
                .setWarningThreshold(0.6f)
                .setCriticalThreshold(0.8f);
            default -> throw new IllegalArgumentException("Unknown backpressure type: " + backpressureType);
        };
    }

    // ========== THROUGHPUT BENCHMARKS ==========

    @Benchmark
    public long messageThroughput() throws Exception {
        Pid actor = createWorkActor();
        
        try {
            AtomicLong counter = new AtomicLong(0);
            WorkMessage message = new WorkMessage(messageCounter.incrementAndGet(), counter);
            actor.tell(message);
            return counter.get();
        } finally {
            actorSystem.stopActor(actor);
        }
    }

    @Benchmark
    public long concurrentMessageThroughput() throws Exception {
        int actorCount = 5;
        Pid[] actors = new Pid[actorCount];
        
        try {
            // Create multiple actors
            for (int i = 0; i < actorCount; i++) {
                actors[i] = createWorkActor();
            }
            
            // Send messages to all actors
            AtomicLong totalCounter = new AtomicLong(0);
            for (Pid actor : actors) {
                WorkMessage message = new WorkMessage(messageCounter.incrementAndGet(), totalCounter);
                actor.tell(message);
            }
            
            return totalCounter.get();
        } finally {
            // Cleanup all actors
            for (Pid actor : actors) {
                if (actor != null) {
                    actorSystem.stopActor(actor);
                }
            }
        }
    }

    @Benchmark
    public int stateUpdateThroughput() throws Exception {
        if ("STATELESS".equals(actorType)) {
            return -1; // No state for stateless actors
        }
        
        Pid actor = createStateActor();
        
        try {
            StateMessage message = new StateMessage(1);
            actor.tell(message);
            return 1;
        } finally {
            actorSystem.stopActor(actor);
        }
    }

    // ========== HELPER METHODS ==========

    private Pid createWorkActor() throws Exception {
        return switch (actorType) {
            case "STATELESS" -> actorSystem.actorOf(StatelessWorkHandler.class)
                .withId("stateless-" + System.nanoTime())
                .withBackpressureConfig(createBackpressureConfig())
                .spawn();
            case "STATEFUL" -> actorSystem.statefulActorOf(StatefulWorkHandler.class, 0)
                .withId("stateful-" + System.nanoTime())
                .withBackpressureConfig(createBackpressureConfig())
                .spawn();
            default -> throw new IllegalArgumentException("Unknown actor type: " + actorType);
        };
    }

    private Pid createStateActor() throws Exception {
        return actorSystem.statefulActorOf(StateUpdateHandler.class, 0)
            .withId("state-update-" + System.nanoTime())
            .withBackpressureConfig(createBackpressureConfig())
            .spawn();
    }

    // Work simulation methods
    private static void doLightWork() {
        // Simulate light processing
        long sum = 0;
        for (int i = 0; i < 100; i++) {
            sum += i;
        }
    }

    // Main method for standalone execution
    public static void main(String[] args) throws Exception {
        org.openjdk.jmh.runner.options.Options opt = 
            new org.openjdk.jmh.runner.options.OptionsBuilder()
                .include(".*ComprehensiveActorBenchmark.*")
                .build();
        
        new org.openjdk.jmh.runner.Runner(opt).run();
    }
}
