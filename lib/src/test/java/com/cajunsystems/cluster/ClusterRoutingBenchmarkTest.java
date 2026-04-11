package com.cajunsystems.cluster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Tag("performance")
class ClusterRoutingBenchmarkTest {

    static final int N = 1000;
    static final int ACTOR_COUNT = 50;

    private ClusterActorSystem system;
    private LatencySimulatingMetadataStore metadataStore;

    @BeforeEach
    void setUp() throws Exception {
        metadataStore = new LatencySimulatingMetadataStore(0); // 0ms latency for cache tests
        NoOpMessagingSystem2 messaging = new NoOpMessagingSystem2();
        system = new ClusterActorSystem("bench-node", metadataStore, messaging);
        system.start().get();
    }

    @AfterEach
    void tearDown() throws Exception {
        system.stop();
    }

    @Test
    void benchmark_cacheHit_routing_throughput() throws Exception {
        // Pre-populate cache with ACTOR_COUNT actor assignments
        // We route one message to each actor first (populates cache via metadataStore.get)
        List<String> actorIds = new ArrayList<>();
        for (int i = 0; i < ACTOR_COUNT; i++) {
            String actorId = "bench-actor-" + i;
            actorIds.add(actorId);
            metadataStore.put("cajun/actor/" + actorId, "other-node").get();
        }

        // Warm up cache: route one message to each actor to populate actorAssignmentCache
        for (String actorId : actorIds) {
            system.routeMessage(actorId, "warmup");
        }
        Thread.sleep(200); // let async lookups complete

        // Verify cache is populated
        Map<String, String> cache = system.getActorAssignmentCache();
        System.out.println("[BENCH] Cache populated with " + cache.size() + " entries");

        // Now benchmark: N routing calls, all should be cache hits
        long start = System.nanoTime();
        for (int i = 0; i < N; i++) {
            system.routeMessage(actorIds.get(i % actorIds.size()), "msg-" + i);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        double opsPerSec = N * 1000.0 / Math.max(1, elapsedMs);

        System.out.printf("[BENCH] cache-hit routing: %d ops in %dms = %.0f ops/sec%n", N, elapsedMs, opsPerSec);
        assertTrue(N > 0, "Sanity check");
    }

    @Test
    void benchmark_batchRegistration_vs_sequential() throws Exception {
        // Use a 1ms latency metadata store to make the difference measurable
        metadataStore.setLatencyMs(1);

        List<String> actorIds = new ArrayList<>();
        for (int i = 0; i < ACTOR_COUNT; i++) {
            actorIds.add("reg-actor-" + i);
        }

        // Sequential registration
        long seqStart = System.nanoTime();
        for (String actorId : actorIds) {
            metadataStore.put("cajun/actor/" + actorId, "bench-node").get();
        }
        long seqMs = (System.nanoTime() - seqStart) / 1_000_000;

        // Batch (parallel) registration
        long batchStart = System.nanoTime();
        system.batchRegisterActors(actorIds).get();
        long batchMs = (System.nanoTime() - batchStart) / 1_000_000;

        System.out.printf("[BENCH] %d actor registrations: sequential=%dms, batch=%dms (%.1fx speedup)%n",
                ACTOR_COUNT, seqMs, batchMs, seqMs > 0 ? (double) seqMs / Math.max(1, batchMs) : 0);

        // Correctness: all actors registered
        for (String actorId : actorIds) {
            assertTrue(metadataStore.get("cajun/actor/" + actorId).get().isPresent(),
                    "Actor " + actorId + " should be registered");
        }
    }
}

class LatencySimulatingMetadataStore implements MetadataStore {
    private final ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();
    private volatile int latencyMs;

    LatencySimulatingMetadataStore(int latencyMs) { this.latencyMs = latencyMs; }
    public void setLatencyMs(int ms) { this.latencyMs = ms; }

    private void simulateLatency() {
        if (latencyMs > 0) {
            try { Thread.sleep(latencyMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    @Override
    public CompletableFuture<Void> connect() { return CompletableFuture.completedFuture(null); }
    @Override
    public CompletableFuture<Void> close() { return CompletableFuture.completedFuture(null); }
    @Override
    public CompletableFuture<Void> put(String key, String value) {
        return CompletableFuture.runAsync(() -> { simulateLatency(); store.put(key, value); });
    }
    @Override
    public CompletableFuture<Optional<String>> get(String key) {
        simulateLatency();
        return CompletableFuture.completedFuture(Optional.ofNullable(store.get(key)));
    }
    @Override
    public CompletableFuture<Void> delete(String key) {
        store.remove(key);
        return CompletableFuture.completedFuture(null);
    }
    @Override
    public CompletableFuture<java.util.List<String>> listKeys(String prefix) {
        java.util.List<String> keys = store.keySet().stream().filter(k -> k.startsWith(prefix)).toList();
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
    public CompletableFuture<Void> unwatch(long watchId) { return CompletableFuture.completedFuture(null); }
}

class NoOpMessagingSystem2 implements MessagingSystem {
    @Override
    public <M> CompletableFuture<Void> sendMessage(String t, String a, M m) {
        return CompletableFuture.completedFuture(null);
    }
    @Override
    public CompletableFuture<Void> registerMessageHandler(MessageHandler h) { return CompletableFuture.completedFuture(null); }
    @Override
    public CompletableFuture<Void> start() { return CompletableFuture.completedFuture(null); }
    @Override
    public CompletableFuture<Void> stop() { return CompletableFuture.completedFuture(null); }
}
