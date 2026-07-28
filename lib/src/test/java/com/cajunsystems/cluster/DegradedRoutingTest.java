package com.cajunsystems.cluster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

class DegradedRoutingTest {

    // The prefix used by ClusterActorSystem for actor-to-node assignments
    private static final String ACTOR_ASSIGNMENT_PREFIX = "cajun/actor/";

    private ClusterActorSystem system;
    private CapturingMetadataStore metadataStore;

    @BeforeEach
    void setUp() throws Exception {
        metadataStore = new CapturingMetadataStore();
        NoOpMessagingSystem messaging = new NoOpMessagingSystem();
        system = new ClusterActorSystem("node-a", metadataStore, messaging);
        system.start().get();
    }

    @AfterEach
    void tearDown() throws Exception {
        system.stop();
    }

    @Test
    void testCacheUpdatedOnSuccessfulLookup() throws Exception {
        // Pre-register an actor assignment in the metadata store using the correct prefix
        metadataStore.put(ACTOR_ASSIGNMENT_PREFIX + "actor-1", "node-b").get();
        // Route a message — this will look up actor-1 in metadata store
        system.routeMessage("actor-1", "hello", DeliveryGuarantee.AT_MOST_ONCE);
        Thread.sleep(100); // let the async lookup complete
        Map<String, String> cache = system.getActorAssignmentCache();
        assertTrue(cache.containsKey("actor-1"), "Cache should contain actor-1 after successful lookup");
        assertEquals("node-b", cache.get("actor-1"));
    }

    @Test
    void testDegradedRoutingUsesCache() throws Exception {
        // Pre-populate the cache by doing a successful lookup first
        metadataStore.put(ACTOR_ASSIGNMENT_PREFIX + "actor-2", "node-b").get();
        system.routeMessage("actor-2", "msg1", DeliveryGuarantee.AT_MOST_ONCE);
        Thread.sleep(100);
        assertTrue(system.getActorAssignmentCache().containsKey("actor-2"));

        // Now make the metadata store fail
        metadataStore.setShouldFail(true);
        // Route another message — should use cache (no exception thrown to caller)
        assertDoesNotThrow(() ->
            system.routeMessage("actor-2", "msg2", DeliveryGuarantee.AT_MOST_ONCE)
        );
    }
}

class CapturingMetadataStore implements MetadataStore {
    private final ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();
    private volatile boolean shouldFail = false;

    public void setShouldFail(boolean fail) { this.shouldFail = fail; }

    @Override
    public CompletableFuture<Void> connect() { return CompletableFuture.completedFuture(null); }

    @Override
    public CompletableFuture<Void> close() { return CompletableFuture.completedFuture(null); }

    @Override
    public CompletableFuture<Void> put(String key, String value) {
        store.put(key, value);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Optional<String>> get(String key) {
        if (shouldFail) return CompletableFuture.failedFuture(new RuntimeException("Simulated etcd failure"));
        return CompletableFuture.completedFuture(Optional.ofNullable(store.get(key)));
    }

    @Override
    public CompletableFuture<Void> delete(String key) {
        store.remove(key);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<List<String>> listKeys(String prefix) {
        if (shouldFail) return CompletableFuture.failedFuture(new RuntimeException("Simulated etcd failure"));
        List<String> keys = store.keySet().stream().filter(k -> k.startsWith(prefix)).toList();
        return CompletableFuture.completedFuture(keys);
    }

    @Override
    public CompletableFuture<Optional<Lock>> acquireLock(String lockName, long ttlSeconds) {
        return CompletableFuture.completedFuture(Optional.of(new Lock() {
            @Override public CompletableFuture<Void> release() { return CompletableFuture.completedFuture(null); }
            @Override public CompletableFuture<Void> refresh() { return CompletableFuture.completedFuture(null); }
        }));
    }

    @Override
    public CompletableFuture<Long> watch(String key, KeyWatcher watcher) {
        return CompletableFuture.completedFuture(0L);
    }

    @Override
    public CompletableFuture<Void> unwatch(long watchId) {
        return CompletableFuture.completedFuture(null);
    }
}

class NoOpMessagingSystem implements MessagingSystem {
    @Override
    public <M> CompletableFuture<Void> sendMessage(String targetSystemId, String actorId, M message) {
        return CompletableFuture.completedFuture(null);
    }
    @Override
    public CompletableFuture<Void> registerMessageHandler(MessageHandler handler) {
        return CompletableFuture.completedFuture(null);
    }
    @Override
    public CompletableFuture<Void> start() { return CompletableFuture.completedFuture(null); }
    @Override
    public CompletableFuture<Void> stop() { return CompletableFuture.completedFuture(null); }
}
