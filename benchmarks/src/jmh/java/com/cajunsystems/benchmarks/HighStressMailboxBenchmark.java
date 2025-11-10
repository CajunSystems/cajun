package com.cajunsystems.benchmarks;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.config.MailboxConfig;
import com.cajunsystems.dispatcher.MailboxType;
import com.cajunsystems.dispatcher.OverflowStrategy;
import com.cajunsystems.config.ThreadPoolFactory;
import com.cajunsystems.config.DefaultMailboxProvider;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-stress benchmark to reveal real mailbox performance differences.
 * 
 * This benchmark creates extreme conditions:
 * - High message volume (1M+ messages)
 * - Many concurrent actors (50-200)
 * - High contention scenarios
 * - Measures pure throughput (no startup/shutdown overhead)
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 5, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 10, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class HighStressMailboxBenchmark {

    @Param({"BLOCKING", "DISPATCHER_LBQ", "DISPATCHER_MPSC"})
    private String mailboxType;

    @Param({"50", "100", "200"})
    private int actorCount;

    @Param({"100000", "1000000"}) // 100K to 1M messages per actor
    private int messagesPerActor;

    private ActorSystem system;
    private Pid[] actors;
    private AtomicLong totalMessagesProcessed;
    private CountDownLatch startLatch;
    private CountDownLatch endLatch;

    @Setup(Level.Trial)
    public void setupTrial() {
        System.out.println("=== HIGH STRESS SETUP: " + actorCount + " actors, " + 
                          messagesPerActor + " messages each, " + mailboxType + " ===");
        
        MailboxConfig config = createMailboxConfig(mailboxType);
        system = new ActorSystem(new ThreadPoolFactory(), null, config, new DefaultMailboxProvider<>());
        
        System.out.println("Creating " + actorCount + " actors...");
        actors = new Pid[actorCount];
        for (int i = 0; i < actorCount; i++) {
            actors[i] = system.actorOf(HighStressHandler.class).spawn();
        }
        
        // Warm up actors
        System.out.println("Warming up actors...");
        for (Pid actor : actors) {
            actor.tell(new WarmupMessage());
        }
        
        // Wait for warmup
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        totalMessagesProcessed = new AtomicLong(0);
        startLatch = new CountDownLatch(1);
        endLatch = new CountDownLatch(actorCount);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (system != null) {
            system.shutdown();
        }
    }

    @Benchmark
    public void highStressThroughput() throws InterruptedException {
        // Reset counters
        totalMessagesProcessed.set(0);
        
        // Tell all actors to prepare for stress test
        for (Pid actor : actors) {
            actor.tell(new StressTestStart(startLatch, endLatch, messagesPerActor, totalMessagesProcessed));
        }
        
        // Start the stress test (all actors begin processing simultaneously)
        long startTime = System.nanoTime();
        startLatch.countDown();
        
        // Wait for all actors to complete (each actor counts down when it finishes its batch)
        boolean completed = endLatch.await(60, TimeUnit.SECONDS);
        long endTime = System.nanoTime();
        
        if (!completed) {
            throw new RuntimeException("Stress test timeout - actors may be blocked");
        }
        
        long totalProcessed = totalMessagesProcessed.get();
        long expectedTotal = (long) messagesPerActor * actorCount;
        
        if (totalProcessed < expectedTotal * 0.95) { // Allow 5% tolerance
            throw new RuntimeException("Message loss: expected " + expectedTotal + 
                                      ", got " + totalProcessed + " (" + 
                                      (totalProcessed * 100.0 / expectedTotal) + "%)");
        }
        
        double durationMs = (endTime - startTime) / 1_000_000.0;
        double throughput = totalProcessed / (durationMs / 1000.0); // messages per second
        
        System.out.println(String.format("STRESS RESULT: %s with %d actors: %.0f msg/sec (%.2f ms total)", 
                          mailboxType, actorCount, throughput, durationMs));
    }

    @Benchmark
    public void highContentionFanOut() throws InterruptedException {
        // All actors send messages to all other actors (N^2 communication)
        totalMessagesProcessed.set(0);
        CountDownLatch completionLatch = new CountDownLatch(actorCount * actorCount);
        
        long startTime = System.nanoTime();
        
        // Each actor sends messages to all other actors
        for (int sender = 0; sender < actorCount; sender++) {
            for (int receiver = 0; receiver < actorCount; receiver++) {
                for (int i = 0; i < 100; i++) { // 100 messages per pair
                    actors[sender].tell(new ContentionMessage(actors[receiver], completionLatch));
                }
            }
        }
        
        // Wait for all messages to be processed
        boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
        long endTime = System.nanoTime();
        
        if (!completed) {
            throw new RuntimeException("Contention test timeout");
        }
        
        double durationMs = (endTime - startTime) / 1_000_000.0;
        System.out.println(String.format("CONTENTION RESULT: %s with %d actors: %.2f ms for %d messages", 
                          mailboxType, actorCount, durationMs, actorCount * actorCount * 100));
    }

    private MailboxConfig createMailboxConfig(String type) {
        MailboxConfig config = new MailboxConfig();
        
        switch (type) {
            case "BLOCKING":
                config.setMailboxType(MailboxType.BLOCKING)
                      .setInitialCapacity(1024)
                      .setMaxCapacity(10000);
                break;
                
            case "DISPATCHER_CBQ":
                config.setMailboxType(MailboxType.DISPATCHER_CBQ)
                      .setMaxCapacity(65536)  // Larger for high stress
                      .setThroughput(64)
                      .setOverflowStrategy(OverflowStrategy.BLOCK);
                break;
                
            case "DISPATCHER_MPSC":
                config.setMailboxType(MailboxType.DISPATCHER_MPSC)
                      .setMaxCapacity(65536)  // Must be power of 2
                      .setThroughput(128)
                      .setOverflowStrategy(OverflowStrategy.BLOCK);
                break;
                
            default:
                throw new IllegalArgumentException("Unknown mailbox type: " + type);
        }
        
        return config;
    }

    // Message classes
    public static class WarmupMessage {}
    
    public static class StressTestStart {
        final CountDownLatch startLatch;
        final CountDownLatch endLatch;
        final int messageCount;
        final AtomicLong counter;
        
        public StressTestStart(CountDownLatch startLatch, CountDownLatch endLatch, 
                              int messageCount, AtomicLong counter) {
            this.startLatch = startLatch;
            this.endLatch = endLatch;
            this.messageCount = messageCount;
            this.counter = counter;
        }
    }
    
    public static class ContentionMessage {
        final Pid target;
        final CountDownLatch latch;
        
        public ContentionMessage(Pid target, CountDownLatch latch) {
            this.target = target;
            this.latch = latch;
        }
    }
    
    public static class ProcessedMessage {
        final CountDownLatch latch;
        
        public ProcessedMessage(CountDownLatch latch) {
            this.latch = latch;
        }
    }

    public static void main(String[] args) throws Exception {
        org.openjdk.jmh.Main.main(args);
    }
}
