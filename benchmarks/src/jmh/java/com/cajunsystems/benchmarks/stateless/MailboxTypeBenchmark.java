package com.cajunsystems.benchmarks.stateless;

import com.cajunsystems.ActorContext;
import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.config.DefaultMailboxProvider;
import com.cajunsystems.config.MailboxConfig;
import com.cajunsystems.config.ThreadPoolFactory;
import com.cajunsystems.dispatcher.MailboxType;
import com.cajunsystems.dispatcher.OverflowStrategy;
import com.cajunsystems.handler.Handler;
import org.openjdk.jmh.annotations.*;

import java.io.Serializable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Benchmark comparing different mailbox types:
 * - BLOCKING: Traditional thread-per-actor with LinkedBlockingQueue
 * - DISPATCHER_LBQ: Dispatcher with LinkedBlockingQueue
 * - DISPATCHER_MPSC: Dispatcher with lock-free JCTools MpscArrayQueue
 * 
 * Measures throughput and latency across different workload patterns.
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 1)
@Measurement(iterations = 3)
@Fork(1)
public class MailboxTypeBenchmark {

    @Param({"BLOCKING", "DISPATCHER_LBQ", "DISPATCHER_MPSC"})
    private String mailboxType;

    @Param({"1000", "10000"})
    private int messageCount;

    @Param({"1", "4"})
    private int actorCount;

    private ActorSystem system;

    // Message types
    public sealed interface BenchMessage extends Serializable {
        record SimpleMessage(int value) implements BenchMessage {
            private static final long serialVersionUID = 1L;
        }
        record CompleteSignal(CountDownLatch latch) implements BenchMessage {
            private static final long serialVersionUID = 1L;
        }
    }

    // Simple message handler that counts messages
    public static class CountingHandler implements Handler<BenchMessage> {
        private int count = 0;

        @Override
        public void receive(BenchMessage message, ActorContext context) {
            switch (message) {
                case BenchMessage.SimpleMessage msg -> count++;
                case BenchMessage.CompleteSignal signal -> signal.latch().countDown();
            }
        }
    }

    @Setup(Level.Invocation)
    public void setup() {
        MailboxConfig config = createMailboxConfig(mailboxType);
        System.out.println("=== BENCHMARK: Creating ActorSystem with mailboxType=" + mailboxType + 
                           ", config.getMailboxType()=" + config.getMailboxType() +
                           ", isDispatcherMode=" + config.isDispatcherMode());
        system = new ActorSystem(
            new ThreadPoolFactory(),
            null,
            config,
            new DefaultMailboxProvider<>()
        );
    }

    @TearDown(Level.Invocation)
    public void teardown() {
        if (system != null) {
            system.shutdown();
            system = null;
        }
    }

    /**
     * Benchmark: Single actor processing many messages
     * Tests raw message processing throughput for each mailbox type.
     */
    @Benchmark
    public void singleActorThroughput() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Pid actor = system.actorOf(CountingHandler.class).spawn();

        // Give actor time to start (especially important for BLOCKING type)
        // Increased to 500ms due to non-deterministic thread startup with Level.Invocation
        Thread.sleep(500);

        // Send messages
        for (int i = 0; i < messageCount; i++) {
            actor.tell(new BenchMessage.SimpleMessage(i));
        }

        // Signal completion
        actor.tell(new BenchMessage.CompleteSignal(latch));
        
        if (!latch.await(30, TimeUnit.SECONDS)) {
            throw new RuntimeException("Benchmark timeout - actor may not be processing messages");
        }
    }

    /**
     * Benchmark: Multiple actors processing messages concurrently
     * Tests dispatcher efficiency with concurrent actors.
     */
    @Benchmark
    public void multipleActorsThroughput() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(actorCount);
        Pid[] actors = new Pid[actorCount];

        // Create actors
        for (int i = 0; i < actorCount; i++) {
            actors[i] = system.actorOf(CountingHandler.class).spawn();
        }

        // Give actors time to start (especially important for BLOCKING type)
        Thread.sleep(200);

        // Send messages to each actor
        int messagesPerActor = messageCount / actorCount;
        for (int i = 0; i < actorCount; i++) {
            for (int j = 0; j < messagesPerActor; j++) {
                actors[i].tell(new BenchMessage.SimpleMessage(j));
            }
            // Signal completion for this actor
            actors[i].tell(new BenchMessage.CompleteSignal(latch));
        }

        if (!latch.await(30, TimeUnit.SECONDS)) {
            throw new RuntimeException("Benchmark timeout - actors may not be processing messages");
        }
    }

    /**
     * Benchmark: Message fan-out pattern
     * One sender broadcasts to multiple actors - tests scheduling efficiency.
     */
    @Benchmark
    public void fanOutPattern() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(actorCount);
        Pid[] actors = new Pid[actorCount];

        // Create actors
        for (int i = 0; i < actorCount; i++) {
            actors[i] = system.actorOf(CountingHandler.class).spawn();
        }

        // Give actors time to start (especially important for BLOCKING type)
        Thread.sleep(200);

        // Broadcast same messages to all actors
        int messagesPerActor = messageCount / actorCount;
        for (int i = 0; i < messagesPerActor; i++) {
            BenchMessage.SimpleMessage msg = new BenchMessage.SimpleMessage(i);
            for (int j = 0; j < actorCount; j++) {
                actors[j].tell(msg);
            }
        }

        // Signal completion for all actors
        for (int i = 0; i < actorCount; i++) {
            actors[i].tell(new BenchMessage.CompleteSignal(latch));
        }

        if (!latch.await(30, TimeUnit.SECONDS)) {
            throw new RuntimeException("Benchmark timeout - actors may not be processing messages");
        }
    }

    /**
     * Benchmark: Burst sending pattern
     * Tests mailbox handling of rapid message bursts from main thread.
     */
    @Benchmark
    public void burstSendingPattern() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Pid actor = system.actorOf(CountingHandler.class).spawn();

        // Give actor time to start (especially important for BLOCKING type)
        // Increased to 500ms due to non-deterministic thread startup with Level.Invocation
        Thread.sleep(500);

        // Send messages in tight loop (burst)
        for (int i = 0; i < messageCount; i++) {
            actor.tell(new BenchMessage.SimpleMessage(i));
        }

        // Signal completion
        actor.tell(new BenchMessage.CompleteSignal(latch));

        if (!latch.await(30, TimeUnit.SECONDS)) {
            throw new RuntimeException("Benchmark timeout - actor may not be processing messages");
        }
    }

    /**
     * Create mailbox configuration based on type.
     */
    private MailboxConfig createMailboxConfig(String type) {
        MailboxConfig config = new MailboxConfig();
        
        switch (type) {
            case "BLOCKING":
                config.setMailboxType(MailboxType.BLOCKING)
                      .setInitialCapacity(1024);
                break;
                
            case "DISPATCHER_CBQ":
                config.setMailboxType(MailboxType.DISPATCHER_CBQ)
                      .setMaxCapacity(8192)  // Match MPSC capacity for fair comparison
                      .setThroughput(64)
                      .setOverflowStrategy(OverflowStrategy.BLOCK);
                break;
                
            case "DISPATCHER_MPSC":
                config.setMailboxType(MailboxType.DISPATCHER_MPSC)
                      .setMaxCapacity(8192)  // Must be power of 2 for MPSC queue
                      .setThroughput(128)
                      .setOverflowStrategy(OverflowStrategy.BLOCK);
                break;
                
            default:
                throw new IllegalArgumentException("Unknown mailbox type: " + type);
        }
        
        return config;
    }

    public static void main(String[] args) throws Exception {
        org.openjdk.jmh.Main.main(args);
    }
}
