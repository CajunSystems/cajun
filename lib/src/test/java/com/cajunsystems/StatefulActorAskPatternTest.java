package com.cajunsystems;

import com.cajunsystems.handler.StatefulHandler;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to verify that the ask pattern works correctly with stateful actors.
 * This test reproduces the bug where context.getSender() was always empty in stateful actors.
 */
public class StatefulActorAskPatternTest {

    private ActorSystem system;

    @BeforeEach
    public void setup() {
        system = new ActorSystem();
    }

    @AfterEach
    public void teardown() throws InterruptedException {
        if (system != null) {
            system.shutdown();
            Thread.sleep(100); // Give time for cleanup
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
        @Override
        public Map<String, String> receive(KVCommand command, Map<String, String> state, ActorContext context) {
            switch (command) {
                case KVCommand.Put put -> {
                    state.put(put.key(), put.value());
                }
                case KVCommand.Get get -> {
                    // This is where the bug manifested - getSender() was always empty
                    context.getSender().ifPresentOrElse(
                        sender -> {
                            String value = state.get(get.key());
                            // Wrap the value in GetResponse to handle null values properly
                            // (actor mailboxes don't accept null messages)
                            sender.tell(new GetResponse(value));
                        },
                        () -> {
                            context.getLogger().error("No sender context for Get request - this is the bug!");
                        }
                    );
                }
                case KVCommand.Delete delete -> {
                    state.remove(delete.key());
                }
            }
            return state;
        }
    }

    @Test
    public void testAskPatternWithStatefulActor() throws Exception {
        // Create KVStore actor with initial empty state
        Pid kvStore = system.statefulActorOf(KVStoreHandler.class, new HashMap<String, String>())
                .spawn();

        // Put a value
        kvStore.tell(new KVCommand.Put("testKey", "testValue"));
        Thread.sleep(50); // Small delay to ensure Put is processed before Get

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

        // Small delay to ensure actor is fully started and ready
        Thread.sleep(50);

        // Ask for a non-existent key
        CompletableFuture<GetResponse> future = system.ask(kvStore, new KVCommand.Get("nonExistent"), Duration.of(10, ChronoUnit.SECONDS));

        // Verify we got null for non-existent key (wrapped in GetResponse)
        GetResponse response = future.get(5, TimeUnit.SECONDS);
        assertNotNull(response, "Should receive a GetResponse even for non-existent key");
        assertNull(response.value(), "Should return null value for non-existent key");
    }

    @Test
    public void testMultipleAskRequests() throws Exception {
        // Create KVStore actor with initial empty state
        Pid kvStore = system.statefulActorOf(KVStoreHandler.class, new HashMap<String, String>())
                .spawn();

        // Put multiple values
        kvStore.tell(new KVCommand.Put("key1", "value1"));
        kvStore.tell(new KVCommand.Put("key2", "value2"));
        kvStore.tell(new KVCommand.Put("key3", "value3"));
        Thread.sleep(50); // Small delay to ensure Puts are processed before Gets

        // Make multiple concurrent ask requests
        CompletableFuture<GetResponse> future1 = system.ask(kvStore, new KVCommand.Get("key1"), Duration.of(10, ChronoUnit.SECONDS));
        CompletableFuture<GetResponse> future2 = system.ask(kvStore, new KVCommand.Get("key2"), Duration.of(10, ChronoUnit.SECONDS));
        CompletableFuture<GetResponse> future3 = system.ask(kvStore, new KVCommand.Get("key3"), Duration.of(10, ChronoUnit.SECONDS));

        // Wait for all futures to complete
        CompletableFuture.allOf(future1, future2, future3).get(5, TimeUnit.SECONDS);

        // Verify all responses are correct (no race condition)
        assertEquals("value1", future1.get().value());
        assertEquals("value2", future2.get().value());
        assertEquals("value3", future3.get().value());
    }
}

