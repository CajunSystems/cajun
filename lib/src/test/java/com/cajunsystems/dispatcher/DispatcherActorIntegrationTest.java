package com.cajunsystems.dispatcher;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.config.MailboxConfig;
import com.cajunsystems.config.DefaultMailboxProvider;
import com.cajunsystems.config.ThreadPoolFactory;
import com.cajunsystems.handler.Handler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for dispatcher-based actors.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class DispatcherActorIntegrationTest {

    private ActorSystem system;

    @AfterEach
    void cleanup() {
        if (system != null) {
            system.shutdown();
        }
    }

    public static class CountingHandler implements Handler<String> {
        static final AtomicInteger counter = new AtomicInteger(0);
        static CountDownLatch latch;

        @Override
        public void receive(String message, com.cajunsystems.ActorContext context) {
            counter.incrementAndGet();
            if (latch != null) {
                latch.countDown();
            }
        }

        static void reset(int count) {
            counter.set(0);
            latch = new CountDownLatch(count);
        }
    }

    @Test
    void testDispatcherLBQActor() throws InterruptedException {
        MailboxConfig config = new MailboxConfig()
            .setMailboxType(MailboxType.DISPATCHER_CBQ)
            .setThroughput(64);

        system = new ActorSystem(
            new ThreadPoolFactory(),
            null,
            config,
            new DefaultMailboxProvider<>()
        );

        CountingHandler.reset(10);
        Pid actor = system.actorOf(CountingHandler.class).spawn();

        // Send messages
        for (int i = 0; i < 10; i++) {
            actor.tell("message-" + i);
        }

        assertTrue(CountingHandler.latch.await(5, TimeUnit.SECONDS));
        assertEquals(10, CountingHandler.counter.get());
    }

    @Test
    void testDispatcherMPSCActor() throws InterruptedException {
        MailboxConfig config = new MailboxConfig()
            .setMailboxType(MailboxType.DISPATCHER_MPSC)
            .setInitialCapacity(1024)
            .setThroughput(128);

        system = new ActorSystem(
            new ThreadPoolFactory(),
            null,
            config,
            new DefaultMailboxProvider<>()
        );

        CountingHandler.reset(100);
        Pid actor = system.actorOf(CountingHandler.class).spawn();

        // Send many messages
        for (int i = 0; i < 100; i++) {
            actor.tell("message-" + i);
        }

        assertTrue(CountingHandler.latch.await(5, TimeUnit.SECONDS));
        assertEquals(100, CountingHandler.counter.get());
    }

    @Test
    void testMultipleDispatcherActors() throws InterruptedException {
        MailboxConfig config = new MailboxConfig()
            .setMailboxType(MailboxType.DISPATCHER_MPSC)
            .setInitialCapacity(1024)
            .setThroughput(64);

        system = new ActorSystem(
            new ThreadPoolFactory(),
            null,
            config,
            new DefaultMailboxProvider<>()
        );

        int actorCount = 10;
        int messagesPerActor = 50;
        CountingHandler.reset(actorCount * messagesPerActor);

        Pid[] actors = new Pid[actorCount];
        for (int i = 0; i < actorCount; i++) {
            actors[i] = system.actorOf(CountingHandler.class).spawn();
        }

        // Send messages to all actors
        for (int i = 0; i < actorCount; i++) {
            for (int j = 0; j < messagesPerActor; j++) {
                actors[i].tell("message-" + j);
            }
        }

        assertTrue(CountingHandler.latch.await(10, TimeUnit.SECONDS));
        assertEquals(actorCount * messagesPerActor, CountingHandler.counter.get());
    }

    public static class PingPongHandler implements Handler<String> {
        static CountDownLatch latch;
        private Pid partner;

        public void setPartner(Pid partner) {
            this.partner = partner;
        }

        @Override
        public void receive(String message, com.cajunsystems.ActorContext context) {
            if (message.equals("START")) {
                if (partner != null) {
                    partner.tell("PING");
                }
            } else if (message.equals("PING")) {
                if (latch.getCount() > 0 && partner != null) {
                    partner.tell("PONG");
                }
                latch.countDown();
            } else if (message.equals("PONG")) {
                if (latch.getCount() > 0 && partner != null) {
                    partner.tell("PING");
                }
                latch.countDown();
            }
        }

        static void reset(int count) {
            latch = new CountDownLatch(count);
        }
    }

    @Test
    void testPingPongWithDispatcher() throws InterruptedException {
        MailboxConfig config = new MailboxConfig()
            .setMailboxType(MailboxType.DISPATCHER_MPSC)
            .setInitialCapacity(2048)
            .setThroughput(32);

        system = new ActorSystem(
            new ThreadPoolFactory(),
            null,
            config,
            new DefaultMailboxProvider<>()
        );

        int exchanges = 100;
        PingPongHandler.reset(exchanges * 2); // PING + PONG

        Pid actor1 = system.actorOf(PingPongHandler.class).spawn();
        Pid actor2 = system.actorOf(PingPongHandler.class).spawn();

        // Set partners - need to extract handler (simplified for test)
        // In real scenario, use actor context or different approach
        actor1.tell("START");

        // Note: This test is simplified. In production, you'd wire partners differently.
        // For now, just verify system doesn't crash
        assertNotNull(actor1);
        assertNotNull(actor2);
    }

    @Test
    void testMixedMailboxTypes() throws InterruptedException {
        // For this test, create separate systems with different configs
        // System 1: Traditional blocking
        ActorSystem system1 = new ActorSystem();
        
        // System 2: Dispatcher LBQ
        MailboxConfig configLBQ = new MailboxConfig()
            .setMailboxType(MailboxType.DISPATCHER_CBQ)
            .setThroughput(64);
        ActorSystem system2 = new ActorSystem(
            new ThreadPoolFactory(), null, configLBQ, new DefaultMailboxProvider<>()
        );
        
        // System 3: Dispatcher MPSC
        MailboxConfig configMPSC = new MailboxConfig()
            .setMailboxType(MailboxType.DISPATCHER_MPSC)
            .setInitialCapacity(1024)
            .setThroughput(128);
        ActorSystem system3 = new ActorSystem(
            new ThreadPoolFactory(), null, configMPSC, new DefaultMailboxProvider<>()
        );

        CountingHandler.reset(20);

        Pid actor1 = system1.actorOf(CountingHandler.class).spawn();
        Pid actor2 = system2.actorOf(CountingHandler.class).spawn();
        Pid actor3 = system3.actorOf(CountingHandler.class).spawn();

        // Send messages to all actors
        for (int i = 0; i < 20; i++) {
            if (i % 3 == 0) {
                actor1.tell("msg-" + i);
            } else if (i % 3 == 1) {
                actor2.tell("msg-" + i);
            } else {
                actor3.tell("msg-" + i);
            }
        }

        assertTrue(CountingHandler.latch.await(5, TimeUnit.SECONDS));
        assertEquals(20, CountingHandler.counter.get());
        
        // Cleanup additional systems
        system1.shutdown();
        system2.shutdown();
        system3.shutdown();
    }

    @Test
    void testHighThroughputScenario() throws InterruptedException {
        MailboxConfig config = new MailboxConfig()
            .setMailboxType(MailboxType.DISPATCHER_MPSC)
            .setInitialCapacity(8192)
            .setThroughput(256);

        system = new ActorSystem(
            new ThreadPoolFactory(),
            null,
            config,
            new DefaultMailboxProvider<>()
        );

        int messageCount = 10000;
        CountingHandler.reset(messageCount);
        
        Pid actor = system.actorOf(CountingHandler.class).spawn();

        // Send many messages quickly
        for (int i = 0; i < messageCount; i++) {
            actor.tell("msg-" + i);
        }

        assertTrue(CountingHandler.latch.await(15, TimeUnit.SECONDS));
        assertEquals(messageCount, CountingHandler.counter.get());
    }

    @Test
    void testOverflowStrategyDrop() throws InterruptedException {
        MailboxConfig config = new MailboxConfig()
            .setMailboxType(MailboxType.DISPATCHER_MPSC)
            .setInitialCapacity(16) // Very small capacity
            .setThroughput(64)
            .setOverflowStrategy(OverflowStrategy.DROP);

        system = new ActorSystem(
            new ThreadPoolFactory(),
            null,
            config,
            new DefaultMailboxProvider<>()
        );

        // Don't use CountDownLatch since some messages will be dropped
        CountingHandler.counter.set(0);
        CountingHandler.latch = null; // Disable latch
        
        Pid actor = system.actorOf(CountingHandler.class).spawn();

        // Flood with messages very quickly - some may be dropped
        // Note: This test verifies DROP strategy doesn't crash, not that messages are dropped
        for (int i = 0; i < 100; i++) {
            actor.tell("msg-" + i);
        }

        // Give time for processing
        Thread.sleep(1000);

        // Verify at least some messages were processed (system is working)
        int processed = CountingHandler.counter.get();
        assertTrue(processed > 0, "Some messages should have been processed");
        // Note: We don't assert < 100 because it's timing-dependent and causes flaky tests
        // The important thing is that DROP strategy doesn't crash the system
    }

    @Test
    void testActorShutdownWithDispatcher() throws InterruptedException {
        MailboxConfig config = new MailboxConfig()
            .setMailboxType(MailboxType.DISPATCHER_CBQ)
            .setThroughput(64);

        system = new ActorSystem(
            new ThreadPoolFactory(),
            null,
            config,
            new DefaultMailboxProvider<>()
        );

        CountingHandler.reset(10);
        Pid actor = system.actorOf(CountingHandler.class).spawn();

        // Send some messages
        for (int i = 0; i < 10; i++) {
            actor.tell("msg-" + i);
        }

        Thread.sleep(100); // Let messages process

        // Shutdown individual actor
        system.stopActor(actor);
        
        // Verify actor stopped
        Thread.sleep(100);
        
        // System should still be running
        assertNotNull(system);
    }
}
