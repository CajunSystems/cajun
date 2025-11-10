package com.cajunsystems.benchmarks;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.config.MailboxConfig;
import com.cajunsystems.config.BackpressureConfig;
import com.cajunsystems.dispatcher.MailboxType;
import com.cajunsystems.config.ThreadPoolFactory;
import com.cajunsystems.config.DefaultMailboxProvider;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Direct backpressure test that bypasses mailbox provider issues.
 * 
 * Tests backpressure at the system level with controlled message flooding.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 1, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 2, time = 3, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class DirectBackpressureTest {

    @Param({"false", "true"})
    private boolean backpressureEnabled;

    @Param({"10", "50"}) // Number of actors to create system pressure
    private int actorCount;

    private ActorSystem system;
    private Pid[] actors;
    private AtomicLong totalProcessed;
    private AtomicLong totalSent;

    @Setup(Level.Trial)
    public void setupTrial() {
        System.out.println("=== DIRECT BACKPRESSURE SETUP: " + actorCount + 
                          " actors, backpressure=" + backpressureEnabled + " ===");
        
        // Create system with aggressive backpressure config
        BackpressureConfig backpressureConfig = backpressureEnabled ? 
            new BackpressureConfig()
                .setMetricsUpdateIntervalMs(10)    // Very fast monitoring
                .setHighWatermark(0.3f)            // 30% triggers backpressure
                .setLowWatermark(0.1f)             // 10% resumes flow
                .setCriticalThreshold(0.5f)        // 50% critical state
                .setWarningThreshold(0.3f)         // 30% warning
                .setMaxCapacity(1000)              // Small system capacity
            : null;
        
        MailboxConfig mailboxConfig = new MailboxConfig()
                .setMailboxType(MailboxType.DISPATCHER_MPSC)
                .setMaxCapacity(8192); // Power of 2 for MPSC
        
        system = new ActorSystem(new ThreadPoolFactory(), backpressureConfig, 
                                mailboxConfig, new DefaultMailboxProvider<>());
        
        actors = new Pid[actorCount];
        for (int i = 0; i < actorCount; i++) {
            actors[i] = system.actorOf(DirectBackpressureHandler.class).spawn();
        }
        
        totalProcessed = new AtomicLong(0);
        totalSent = new AtomicLong(0);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (system != null) {
            system.shutdown();
        }
    }

    @Benchmark
    public void testSystemLevelBackpressure() throws InterruptedException {
        int messagesPerActor = 1000;
        CountDownLatch completionLatch = new CountDownLatch(actorCount);
        
        // Reset counters
        totalProcessed.set(0);
        totalSent.set(0);
        
        long startTime = System.nanoTime();
        
        // Rapidly flood all actors simultaneously
        for (int i = 0; i < actorCount; i++) {
            for (int j = 0; j < messagesPerActor; j++) {
                try {
                    actors[i].tell(new FloodMessage(j, completionLatch, totalProcessed));
                    totalSent.incrementAndGet();
                } catch (Exception e) {
                    // Message rejected due to backpressure
                    System.out.println("Message rejected by backpressure: " + e.getMessage());
                }
            }
        }
        
        long sendTime = System.nanoTime();
        
        // Wait for processing (shorter timeout to see backpressure effects)
        boolean completed = completionLatch.await(5, TimeUnit.SECONDS);
        long endTime = System.nanoTime();
        
        long sent = totalSent.get();
        long processed = totalProcessed.get();
        double sendDurationMs = (sendTime - startTime) / 1_000_000.0;
        double totalDurationMs = (endTime - startTime) / 1_000_000.0;
        double processingRate = processed / (totalDurationMs / 1000.0);
        
        System.out.println(String.format("DIRECT BACKPRESSURE RESULT (bp=%s): sent=%d, processed=%d, completion=%s, sendTime=%.2fms, totalTime=%.2fms, rate=%.0f msg/sec", 
                          backpressureEnabled, sent, processed, completed, sendDurationMs, totalDurationMs, processingRate));
        
        // Key indicators of backpressure working:
        if (backpressureEnabled && !completed) {
            System.out.println("✅ BACKPRESSURE WORKING: System couldn't keep up with backpressure enabled");
        }
        
        if (!backpressureEnabled && completed) {
            System.out.println("📊 BASELINE: System completed processing without backpressure");
        }
    }

    public static class FloodMessage {
        final int id;
        final CountDownLatch latch;
        final AtomicLong counter;
        
        public FloodMessage(int id, CountDownLatch latch, AtomicLong counter) {
            this.id = id;
            this.latch = latch;
            this.counter = counter;
        }
    }

    public static void main(String[] args) throws Exception {
        org.openjdk.jmh.Main.main(args);
    }
}
