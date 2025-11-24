package com.cajunsystems;

import com.cajunsystems.handler.StatefulHandler;
import com.cajunsystems.test.AsyncAssertion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to verify that the ask pattern works correctly with stateful actors.
 * Demonstrates proper use of test-utils library for async actor testing.
 */
public class StatefulActorAskPatternTest {

    private ActorSystem system;

    @BeforeEach
    public void setup() {
        KVStoreHandler.resetCounter(); // Reset counter before each test
        system = new ActorSystem();
    }

    @AfterEach
    public void teardown() throws InterruptedException {
        if (system != null) {
            system.shutdown();
            Thread.sleep(200); // Give more time for cleanup to avoid test interference
        }
    }

    /**
     * Command interface for KVStore
     */
    sealed interface KVCommand extends Serializable {
        record Put(String key, String value) implements KVCommand {}
        record Get(String key) implements KVCommand {}
        record Delete(String key) implements KVCommand {}
    }

    /**
     * Response wrapper to handle null values
     */
    record GetResponse(String value) implements Serializable {}

    /**
     * KVStore handler that uses getSender() to reply to Get requests
     */
    static class KVStoreHandler implements StatefulHandler<Map<String, String>, KVCommand> {
        private static final AtomicInteger processedMessages = new AtomicInteger(0);

        @Override
        public Map<String, String> receive(KVCommand command, Map<String, String> state, ActorContext context) {
            switch (command) {
                case KVCommand.Put put -> {
                    state.put(put.key(), put.value());
                    processedMessages.incrementAndGet();
                }
                case KVCommand.Get get -> {
                    // This is where the bug manifested - getSender() was always empty
                    context.getSender().ifPresentOrElse(
                        sender -> {
                            String value = state.get(get.key());
                            // Wrap the value in GetResponse to handle null values properly
                            // (actor mailboxes don't accept null messages)
                            sender.tell(new GetResponse(value));
                            processedMessages.incrementAndGet();
                        },
                        () -> {
                            context.getLogger().error("No sender context for Get request - this is the bug!");
                        }
                    );
                }
                case KVCommand.Delete delete -> {
                    state.remove(delete.key());
                    processedMessages.incrementAndGet();
                }
            }
            return state;
        }

        public static void resetCounter() {
            processedMessages.set(0);
        }

        public static int getProcessedCount() {
            return processedMessages.get();
        }
    }

    @Test
    public void testAskPatternWithStatefulActor() throws Exception {
        // Create KVStore actor with initial empty state
        Pid kvStore = system.statefulActorOf(KVStoreHandler.class, new HashMap<String, String>())
                .spawn();

        // Wait for actor state initialization to complete (more reliable than Thread.sleep)
        StatefulActor<?, ?> actor = (StatefulActor<?, ?>) system.getActor(kvStore);
        assertTrue(actor.waitForStateInitialization(5000), "Actor state should initialize within 5 seconds");

        // Put a value
        kvStore.tell(new KVCommand.Put("testKey", "testValue"));

        // Wait for Put to be processed using AsyncAssertion (more reliable than Thread.sleep)
        AsyncAssertion.eventually(() -> KVStoreHandler.getProcessedCount() >= 1,
            Duration.ofSeconds(3),
            10); // poll every 10ms

        // Use ask pattern to get the value - this should work now
        CompletableFuture<GetResponse> future = system.ask(kvStore, new KVCommand.Get("testKey"), Duration.of(10, ChronoUnit.SECONDS));

        // Verify we got the correct response
        GetResponse response = future.get(5, TimeUnit.SECONDS);
        assertNotNull(response, "Should receive a GetResponse");
        assertEquals("testValue", response.value(), "Should retrieve the value that was put");
    }

    @Test
    public void testAskPatternWithNonExistentKey() throws Exception {
        // Create KVStore actor with initial empty state
        Pid kvStore = system.statefulActorOf(KVStoreHandler.class, new HashMap<String, String>())
                .spawn();

        // Wait for actor state initialization to complete (more reliable than Thread.sleep)
        StatefulActor<?, ?> actor = (StatefulActor<?, ?>) system.getActor(kvStore);
        assertTrue(actor.waitForStateInitialization(5000), "Actor state should initialize within 5 seconds");

        // Ask for a non-existent key
        CompletableFuture<GetResponse> future = system.ask(kvStore, new KVCommand.Get("nonExistent"), Duration.of(10, ChronoUnit.SECONDS));

        // Verify we got null for non-existent key (wrapped in GetResponse)
        GetResponse response = future.get(10, TimeUnit.SECONDS);
        assertNotNull(response, "Should receive a GetResponse even for non-existent key");
        assertNull(response.value(), "Should return null value for non-existent key");
    }

    @Test
    public void testMultipleAskRequests() throws Exception {
        // Create KVStore actor with initial empty state
        Pid kvStore = system.statefulActorOf(KVStoreHandler.class, new HashMap<String, String>())
                .spawn();

        // Wait for actor state initialization to complete (more reliable than Thread.sleep)
        StatefulActor<?, ?> actor = (StatefulActor<?, ?>) system.getActor(kvStore);
        assertTrue(actor.waitForStateInitialization(5000), "Actor state should initialize within 5 seconds");

        // Put multiple values
        kvStore.tell(new KVCommand.Put("key1", "value1"));
        kvStore.tell(new KVCommand.Put("key2", "value2"));
        kvStore.tell(new KVCommand.Put("key3", "value3"));

        // Wait for all Puts to be processed using AsyncAssertion (dogfooding test-utils!)
        AsyncAssertion.eventually(() -> KVStoreHandler.getProcessedCount() >= 3,
            Duration.ofSeconds(3),
            10); // poll every 10ms

        // Make multiple concurrent ask requests
        CompletableFuture<GetResponse> future1 = system.ask(kvStore, new KVCommand.Get("key1"), Duration.of(10, ChronoUnit.SECONDS));
        CompletableFuture<GetResponse> future2 = system.ask(kvStore, new KVCommand.Get("key2"), Duration.of(10, ChronoUnit.SECONDS));
        CompletableFuture<GetResponse> future3 = system.ask(kvStore, new KVCommand.Get("key3"), Duration.of(10, ChronoUnit.SECONDS));

        // Wait for all futures to complete
        CompletableFuture.allOf(future1, future2, future3).get(10, TimeUnit.SECONDS);

        // Verify all responses are correct (no race condition)
        assertEquals("value1", future1.get().value());
        assertEquals("value2", future2.get().value());
        assertEquals("value3", future3.get().value());
    }

    /**
     * Test direct StatefulActor subclass (not using StatefulHandler interface)
     * This test catches the bug where getSender() wasn't overridden in StatefulActor
     */
    @Test
    public void testAskPatternWithDirectStatefulActorSubclass() throws Exception {
        // Create a direct StatefulActor subclass with unique ID to avoid state recovery
        String actorId = "direct-counter-" + System.currentTimeMillis();
        Pid counter = system.register(DirectCounterActor.class, actorId);
        
        // Wait for actor state initialization
        StatefulActor<?, ?> actor = (StatefulActor<?, ?>) system.getActor(counter);
        assertTrue(actor.waitForStateInitialization(5000), "Actor state should initialize within 5 seconds");
        
        // Test increment with ask pattern
        CompletableFuture<String> future1 = system.ask(counter, "increment", Duration.ofSeconds(5));
        String response1 = future1.get(5, TimeUnit.SECONDS);
        assertEquals("Count: 1", response1, "Should get count 1 after first increment");
        
        // Test another increment
        CompletableFuture<String> future2 = system.ask(counter, "increment", Duration.ofSeconds(5));
        String response2 = future2.get(5, TimeUnit.SECONDS);
        assertEquals("Count: 2", response2, "Should get count 2 after second increment");
        
        // Test get without increment
        CompletableFuture<String> future3 = system.ask(counter, "get", Duration.ofSeconds(5));
        String response3 = future3.get(5, TimeUnit.SECONDS);
        assertEquals("Count: 2", response3, "Should still be 2 after get");
    }

    /**
     * Direct StatefulActor subclass for testing getSender() in processMessage()
     */
    static class DirectCounterActor extends StatefulActor<Integer, String> {
        public DirectCounterActor(ActorSystem system, String actorId) {
            super(system, actorId, 0);
        }

        @Override
        protected Integer processMessage(Integer state, String message) {
            if ("increment".equals(message)) {
                int newCount = state + 1;
                // Use getSender() directly - this is where the bug was
                getSender().ifPresentOrElse(
                    sender -> sender.tell("Count: " + newCount),
                    () -> getLogger().error("No sender for increment - BUG!")
                );
                return newCount;
            } else if ("get".equals(message)) {
                getSender().ifPresentOrElse(
                    sender -> sender.tell("Count: " + state),
                    () -> getLogger().error("No sender for get - BUG!")
                );
                return state;
            }
            return state;
        }
    }
}

