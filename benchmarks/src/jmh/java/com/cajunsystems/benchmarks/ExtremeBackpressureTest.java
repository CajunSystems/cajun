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
 * Extreme backpressure test to maximize the difference between enabled/disabled.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 1, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 2, time = 5, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class ExtremeBackpressureTest {

    @Param({"false", "true"})
    private boolean backpressureEnabled;

    @Param({"100", "200"}) // Extreme actor counts
    private int actorCount;

    private ActorSystem system;
    private Pid[] actors;
    private AtomicLong totalProcessed;

    @Setup(Level.Trial)
    public void setupTrial() {
        System.out.println("=== EXTREME BACKPRESSURE SETUP: " + actorCount + 
                          " actors, backpressure=" + backpressureEnabled + " ===");
        
        // Ultra-aggressive backpressure config
        BackpressureConfig backpressureConfig = backpressureEnabled ? 
            new BackpressureConfig()
                .setMetricsUpdateIntervalMs(5)     // Extremely fast monitoring
                .setHighWatermark(0.2f)            // 20% triggers backpressure
                .setLowWatermark(0.05f)            // 5% resumes flow
                .setCriticalThreshold(0.3f)        // 30% critical state
                .setWarningThreshold(0.2f)         // 20% warning
                .setMaxCapacity(500)               // Very small system capacity
            : null;
        
        MailboxConfig mailboxConfig = new MailboxConfig()
                .setMailboxType(MailboxType.DISPATCHER_MPSC)
                .setMaxCapacity(512); // Tiny mailboxes
        
        system = new ActorSystem(new ThreadPoolFactory(), backpressureConfig, 
                                mailboxConfig, new DefaultMailboxProvider<>());
        
        actors = new Pid[actorCount];
        for (int i = 0; i < actorCount; i++) {
            actors[i] = system.actorOf(ExtremeBackpressureHandler.class).spawn();
        }
        
        totalProcessed = new AtomicLong(0);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (system != null) {
            system.shutdown();
        }
    }

    @Benchmark
    public void testExtremeBackpressure() throws InterruptedException {
        int messagesPerActor = 500; // Moderate messages but many actors
        CountDownLatch completionLatch = new CountDownLatch(actorCount);
        
        // Reset counters
        totalProcessed.set(0);
        
        long startTime = System.nanoTime();
        
        // Extreme flooding: all actors get all messages simultaneously
        for (int i = 0; i < actorCount; i++) {
            for (int j = 0; j < messagesPerActor; j++) {
                actors[i].tell(new ExtremeMessage(j, completionLatch, totalProcessed));
            }
        }
        
        // Wait for processing (shorter timeout to see differences)
        completionLatch.await(3, TimeUnit.SECONDS);
        long endTime = System.nanoTime();
        
        long processed = totalProcessed.get();
        long expectedTotal = (long) messagesPerActor * actorCount;
        double totalDurationMs = (endTime - startTime) / 1_000_000.0;
        double processingRate = processed / (totalDurationMs / 1000.0);
        double completionRate = (processed * 100.0) / expectedTotal;
        
        System.out.println(String.format("EXTREME RESULT (bp=%s): actors=%d, sent=%d, processed=%d, completion=%.1f%%, rate=%.0f msg/sec", 
                          backpressureEnabled, actorCount, expectedTotal, processed, completionRate, processingRate));
        
        // Key indicators:
        if (backpressureEnabled && completionRate > 95.0) {
            System.out.println("✅ BACKPRESSURE EFFICIENT: High completion rate with flow control");
        }
        
        if (!backpressureEnabled && completionRate < 95.0) {
            System.out.println("⚠️ NO BACKPRESSURE: System struggling under extreme load");
        }
    }

    public static class ExtremeMessage {
        final int id;
        final CountDownLatch latch;
        final AtomicLong counter;
        
        public ExtremeMessage(int id, CountDownLatch latch, AtomicLong counter) {
            this.id = id;
            this.latch = latch;
            this.counter = counter;
        }
    }

    public static void main(String[] args) throws Exception {
        org.openjdk.jmh.Main.main(args);
    }
}
