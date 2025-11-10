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
 * Simple stress test that reveals real mailbox performance differences.
 * 
 * Tests pure message sending throughput without complex processing.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 1, time = 3, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 2, time = 5, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class SimpleMailboxStressBenchmark {

    @Param({"BLOCKING", "DISPATCHER_LBQ", "DISPATCHER_MPSC"})
    private String mailboxType;

    @Param({"10", "50", "100"})
    private int actorCount;

    @Param({"10000", "50000"}) // Messages per actor
    private int messagesPerActor;

    private ActorSystem system;
    private Pid[] actors;
    private CountDownLatch completionLatch;

    @Setup(Level.Trial)
    public void setupTrial() {
        System.out.println("=== SETUP: " + actorCount + " actors, " + 
                          messagesPerActor + " messages each, " + mailboxType + " ===");
        
        MailboxConfig config = createMailboxConfig(mailboxType);
        system = new ActorSystem(new ThreadPoolFactory(), null, config, new DefaultMailboxProvider<>());
        
        actors = new Pid[actorCount];
        for (int i = 0; i < actorCount; i++) {
            actors[i] = system.actorOf(SimpleStressHandler.class).spawn();
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
    public void messageSendingThroughput() throws InterruptedException {
        AtomicLong totalMessages = new AtomicLong(0);
        
        long startTime = System.nanoTime();
        
        // Send messages to all actors (this tests mailbox enqueue performance)
        for (int i = 0; i < actorCount; i++) {
            for (int j = 0; j < messagesPerActor; j++) {
                actors[i].tell(new SimpleMessage(j, completionLatch, totalMessages));
            }
        }
        
        long sendTime = System.nanoTime();
        
        // Wait for all messages to be processed
        boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
        long endTime = System.nanoTime();
        
        if (!completed) {
            throw new RuntimeException("Timeout processing messages");
        }
        
        long totalProcessed = totalMessages.get();
        long expectedTotal = (long) messagesPerActor * actorCount;
        
        if (totalProcessed < expectedTotal * 0.95) {
            throw new RuntimeException("Message loss: expected " + expectedTotal + 
                                      ", got " + totalProcessed);
        }
        
        double sendDurationMs = (sendTime - startTime) / 1_000_000.0;
        double totalDurationMs = (endTime - startTime) / 1_000_000.0;
        double throughput = totalProcessed / (totalDurationMs / 1000.0);
        
        System.out.println(String.format("RESULT: %s with %d actors: %.0f msg/sec (send: %.2f ms, total: %.2f ms)", 
                          mailboxType, actorCount, throughput, sendDurationMs, totalDurationMs));
    }

    private MailboxConfig createMailboxConfig(String type) {
        MailboxConfig config = new MailboxConfig();
        
        switch (type) {
            case "BLOCKING":
                config.setMailboxType(MailboxType.BLOCKING)
                      .setInitialCapacity(1024)
                      .setMaxCapacity(100000); // Larger capacity for stress test
                break;
                
            case "DISPATCHER_CBQ":
                config.setMailboxType(MailboxType.DISPATCHER_CBQ)
                      .setMaxCapacity(100000)
                      .setThroughput(64)
                      .setOverflowStrategy(OverflowStrategy.BLOCK);
                break;
                
            case "DISPATCHER_MPSC":
                config.setMailboxType(MailboxType.DISPATCHER_MPSC)
                      .setMaxCapacity(131072) // Power of 2, larger capacity
                      .setThroughput(128)
                      .setOverflowStrategy(OverflowStrategy.BLOCK);
                break;
                
            default:
                throw new IllegalArgumentException("Unknown mailbox type: " + type);
        }
        
        return config;
    }

    // Simple message class
    public static class SimpleMessage {
        final int value;
        final CountDownLatch latch;
        final AtomicLong counter;
        
        public SimpleMessage(int value, CountDownLatch latch, AtomicLong counter) {
            this.value = value;
            this.latch = latch;
            this.counter = counter;
        }
    }

    public static void main(String[] args) throws Exception {
        org.openjdk.jmh.Main.main(args);
    }
}
