package com.cajunsystems.benchmarks;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.config.MailboxConfig;
import com.cajunsystems.config.BackpressureConfig;
import com.cajunsystems.dispatcher.MailboxType;
import com.cajunsystems.dispatcher.OverflowStrategy;
import com.cajunsystems.config.ThreadPoolFactory;
import com.cajunsystems.config.DefaultMailboxProvider;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stress test comparing mailbox performance with and without backpressure.
 * 
 * This reveals:
 * - Message loss without backpressure
 * - Graceful flow control with backpressure
 * - Performance impact of backpressure mechanisms
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 1, time = 3, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 2, time = 5, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class BackpressureStressBenchmark {

    @Param({"BLOCKING", "DISPATCHER_LBQ", "DISPATCHER_MPSC"})
    private String mailboxType;

    @Param({"false", "true"}) // Backpressure enabled/disabled
    private boolean backpressureEnabled;

    @Param({"10", "25"}) // Actor count
    private int actorCount;

    @Param({"20000"}) // Messages per actor
    private int messagesPerActor;

    private ActorSystem system;
    private Pid[] actors;
    private CountDownLatch completionLatch;

    @Setup(Level.Trial)
    public void setupTrial() {
        System.out.println("=== BACKPRESSURE SETUP: " + actorCount + " actors, " + 
                          messagesPerActor + " messages each, " + mailboxType + 
                          ", backpressure=" + backpressureEnabled + " ===");
        
        MailboxConfig mailboxConfig = createMailboxConfig(mailboxType);
        BackpressureConfig backpressureConfig = backpressureEnabled ? 
            createBackpressureConfig() : null;
        
        system = new ActorSystem(new ThreadPoolFactory(), backpressureConfig, 
                                mailboxConfig, new DefaultMailboxProvider<>());
        
        actors = new Pid[actorCount];
        for (int i = 0; i < actorCount; i++) {
            actors[i] = system.actorOf(BackpressureStressHandler.class).spawn();
        }
        
        completionLatch = new CountDownLatch(actorCount);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (system != null) {
            system.shutdown();
        }
    }

    @Benchmark
    public void backpressureAwareThroughput() throws InterruptedException {
        AtomicLong totalMessages = new AtomicLong(0);
        AtomicLong droppedMessages = new AtomicLong(0);
        CountDownLatch sendCompleteLatch = new CountDownLatch(actorCount);
        
        long startTime = System.nanoTime();
        
        // First: Tell all actors to start slow processing (creates backlog)
        for (Pid actor : actors) {
            actor.tell(new SlowProcessingMessage(sendCompleteLatch));
        }
        
        // Wait a bit for actors to start processing
        Thread.sleep(10);
        
        // Now: Rapidly send messages to overwhelm the mailboxes
        for (int i = 0; i < actorCount; i++) {
            for (int j = 0; j < messagesPerActor; j++) {
                try {
                    actors[i].tell(new BackpressureMessage(j, completionLatch, totalMessages, droppedMessages));
                } catch (Exception e) {
                    // Message dropped due to backpressure
                    droppedMessages.incrementAndGet();
                }
            }
        }
        
        // Wait for all messages to be processed
        boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
        long endTime = System.nanoTime();
        
        if (!completed) {
            throw new RuntimeException("Timeout processing messages");
        }
        
        long totalProcessed = totalMessages.get();
        long totalDropped = droppedMessages.get();
        
        double totalDurationMs = (endTime - startTime) / 1_000_000.0;
        double throughput = totalProcessed / (totalDurationMs / 1000.0);
        double lossRate = (totalDropped * 100.0) / (totalProcessed + totalDropped);
        
        System.out.println(String.format("BACKPRESSURE RESULT: %s (bp=%s) with %d actors: %.0f msg/sec, loss=%.1f%% (%d dropped)", 
                          mailboxType, backpressureEnabled, actorCount, throughput, lossRate, totalDropped));
        
        // For backpressure-enabled tests, we expect zero loss
        if (backpressureEnabled && totalDropped > 0) {
            System.out.println("WARNING: Backpressure enabled but still dropping messages!");
        }
    }

    private MailboxConfig createMailboxConfig(String type) {
        MailboxConfig config = new MailboxConfig();
        
        switch (type) {
            case "BLOCKING":
                config.setMailboxType(MailboxType.BLOCKING)
                      .setInitialCapacity(512) // Smaller to trigger overflow
                      .setMaxCapacity(5000);   // Small capacity for stress test
                break;
                
            case "DISPATCHER_CBQ":
                config.setMailboxType(MailboxType.DISPATCHER_CBQ)
                      .setMaxCapacity(5000)
                      .setThroughput(32) // Lower throughput to create backpressure
                      .setOverflowStrategy(OverflowStrategy.DROP); // Drop on overflow
                break;
                
            case "DISPATCHER_MPSC":
                config.setMailboxType(MailboxType.DISPATCHER_MPSC)
                      .setMaxCapacity(4096) // Power of 2, small for stress
                      .setThroughput(64)
                      .setOverflowStrategy(OverflowStrategy.DROP);
                break;
                
            default:
                throw new IllegalArgumentException("Unknown mailbox type: " + type);
        }
        
        return config;
    }
    
    private BackpressureConfig createBackpressureConfig() {
        return new BackpressureConfig()
                .setMetricsUpdateIntervalMs(100)  // Fast monitoring
                .setHighWatermark(0.7f)           // 70% capacity triggers backpressure
                .setLowWatermark(0.3f)            // 30% capacity resumes normal flow
                .setCriticalThreshold(0.9f)       // 90% capacity = critical state
                .setWarningThreshold(0.7f);       // 70% capacity = warning state
    }

    // Message to start slow processing
    public static class SlowProcessingMessage {
        final CountDownLatch latch;
        
        public SlowProcessingMessage(CountDownLatch latch) {
            this.latch = latch;
        }
    }

    // Message class that tracks drops
    public static class BackpressureMessage {
        final int value;
        final CountDownLatch latch;
        final AtomicLong counter;
        final AtomicLong droppedCounter;
        
        public BackpressureMessage(int value, CountDownLatch latch, 
                                  AtomicLong counter, AtomicLong droppedCounter) {
            this.value = value;
            this.latch = latch;
            this.counter = counter;
            this.droppedCounter = droppedCounter;
        }
    }

    public static void main(String[] args) throws Exception {
        org.openjdk.jmh.Main.main(args);
    }
}
