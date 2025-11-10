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
 * Real backpressure test that measures actual queue overflow and flow control.
 * 
 * This test:
 * - Creates controlled overflow scenarios
 * - Measures message drops vs blocking behavior
 * - Shows backpressure activation
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 1, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 2, time = 3, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class RealBackpressureBenchmark {

    @Param({"BLOCKING", "DISPATCHER_LBQ", "DISPATCHER_MPSC"})
    private String mailboxType;

    @Param({"DROP", "BLOCK"}) // Overflow strategy
    private String overflowStrategy;

    @Param({"false", "true"})
    private boolean backpressureEnabled;

    private ActorSystem system;
    private Pid testActor;
    private AtomicLong processedCount;
    private AtomicLong droppedCount;

    @Setup(Level.Trial)
    public void setupTrial() {
        System.out.println("=== REAL BACKPRESSURE SETUP: " + mailboxType + 
                          ", overflow=" + overflowStrategy + 
                          ", bp=" + backpressureEnabled + " ===");
        
        MailboxConfig mailboxConfig = createMailboxConfig(mailboxType, overflowStrategy);
        BackpressureConfig backpressureConfig = backpressureEnabled ? 
            createBackpressureConfig() : null;
        
        system = new ActorSystem(new ThreadPoolFactory(), backpressureConfig, 
                                mailboxConfig, new DefaultMailboxProvider<>());
        
        testActor = system.actorOf(RealBackpressureHandler.class).spawn();
        processedCount = new AtomicLong(0);
        droppedCount = new AtomicLong(0);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (system != null) {
            system.shutdown();
        }
    }

    @Benchmark
    public void measureRealBackpressure() throws InterruptedException {
        int messageCount = 5000; // Enough to overflow small mailboxes
        CountDownLatch processingComplete = new CountDownLatch(1);
        
        // Reset counters
        processedCount.set(0);
        droppedCount.set(0);
        
        long startTime = System.nanoTime();
        
        // Send messages rapidly to overflow the mailbox
        int actuallySent = 0;
        for (int i = 0; i < messageCount; i++) {
            try {
                testActor.tell(new TestMessage(i, processingComplete, processedCount, droppedCount));
                actuallySent++;
            } catch (Exception e) {
                // Message was dropped (backpressure in action)
                droppedCount.incrementAndGet();
            }
        }
        
        long sendTime = System.nanoTime();
        
        // Tell actor to finish processing
        testActor.tell(new FinishMessage(processingComplete));
        
        // Wait for processing to complete (with timeout)
        boolean completed = processingComplete.await(10, TimeUnit.SECONDS);
        long endTime = System.nanoTime();
        
        if (!completed) {
            System.out.println("TIMEOUT: Actor couldn't process all messages in time");
        }
        
        long processed = processedCount.get();
        long dropped = droppedCount.get();
        double sendDurationMs = (sendTime - startTime) / 1_000_000.0;
        double totalDurationMs = (endTime - startTime) / 1_000_000.0;
        
        System.out.println(String.format("REAL BACKPRESSURE RESULT: %s (%s, bp=%s): sent=%d, processed=%d, dropped=%d, sendTime=%.2fms, totalTime=%.2fms", 
                          mailboxType, overflowStrategy, backpressureEnabled, 
                          actuallySent, processed, dropped, sendDurationMs, totalDurationMs));
        
        // Verify backpressure behavior
        if (overflowStrategy.equals("DROP") && dropped == 0 && actuallySent == messageCount) {
            System.out.println("WARNING: DROP strategy but no messages dropped - mailbox didn't overflow");
        }
        
        if (overflowStrategy.equals("BLOCK") && dropped > 0) {
            System.out.println("INFO: BLOCK strategy but some messages dropped - backpressure system working");
        }
    }

    private MailboxConfig createMailboxConfig(String type, String strategy) {
        MailboxConfig config = new MailboxConfig();
        OverflowStrategy overflow = OverflowStrategy.valueOf(strategy);
        
        switch (type) {
            case "BLOCKING":
                config.setMailboxType(MailboxType.BLOCKING)
                      .setInitialCapacity(64)
                      .setMaxCapacity(256); // Very small to force overflow
                break;
                
            case "DISPATCHER_CBQ":
                config.setMailboxType(MailboxType.DISPATCHER_CBQ)
                      .setMaxCapacity(256)
                      .setThroughput(16)
                      .setOverflowStrategy(overflow);
                break;
                
            case "DISPATCHER_MPSC":
                config.setMailboxType(MailboxType.DISPATCHER_MPSC)
                      .setMaxCapacity(256) // Power of 2, very small
                      .setThroughput(32)
                      .setOverflowStrategy(overflow);
                break;
                
            default:
                throw new IllegalArgumentException("Unknown mailbox type: " + type);
        }
        
        return config;
    }
    
    private BackpressureConfig createBackpressureConfig() {
        return new BackpressureConfig()
                .setMetricsUpdateIntervalMs(50)   // Very fast monitoring
                .setHighWatermark(0.5f)           // 50% capacity triggers backpressure
                .setLowWatermark(0.2f)            // 20% capacity resumes normal flow
                .setCriticalThreshold(0.8f)       // 80% capacity = critical state
                .setWarningThreshold(0.5f);       // 50% capacity = warning state
    }

    // Test messages
    public static class TestMessage {
        final int id;
        final CountDownLatch latch;
        final AtomicLong processedCounter;
        final AtomicLong droppedCounter;
        
        public TestMessage(int id, CountDownLatch latch, AtomicLong processedCounter, AtomicLong droppedCounter) {
            this.id = id;
            this.latch = latch;
            this.processedCounter = processedCounter;
            this.droppedCounter = droppedCounter;
        }
    }
    
    public static class FinishMessage {
        final CountDownLatch latch;
        
        public FinishMessage(CountDownLatch latch) {
            this.latch = latch;
        }
    }

    public static void main(String[] args) throws Exception {
        org.openjdk.jmh.Main.main(args);
    }
}
