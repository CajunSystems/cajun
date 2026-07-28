# Phase 29 Plan 1 — Performance Optimization

## Objective
Upgrade the actor assignment cache to a TTL-aware primary routing path (bypassing etcd on cache hits), configure gRPC connection keep-alive in `EtcdMetadataStore`, add parallel batch actor registration, and benchmark the improvements.

## Execution Context

```
cajun-core/src/main/java/com/cajunsystems/cluster/TtlCache.java               — NEW
cajun-core/src/main/java/com/cajunsystems/metrics/ClusterMetrics.java         — add cacheHit/cacheMiss counters
lib/src/main/java/com/cajunsystems/cluster/ClusterActorSystem.java            — upgrade cache + routing + batchRegister
cajun-cluster/src/main/java/com/cajunsystems/cluster/impl/EtcdMetadataStore.java — gRPC config
lib/src/test/java/com/cajunsystems/cluster/TtlCacheTest.java                  — NEW
lib/src/test/java/com/cajunsystems/cluster/ClusterRoutingBenchmarkTest.java   — NEW (perf)
```

## Key Design Decision — Cache as Primary Routing Path
The Phase 28 cache was a **fallback** (only used when etcd failed via `exceptionally()`). Phase 29 promotes it to a **primary fast path**: on a cache hit, routing skips the etcd round-trip entirely. TTL ensures stale entries expire; watcher-driven eviction handles actor moves and deletions in near-real-time.

## Constraints
- `TtlCache` lives in `cajun-core` (no external deps — just `ConcurrentHashMap` + `System.currentTimeMillis()`)
- No external cache library — AtomicLong/ConcurrentHashMap pattern consistent with existing code
- `ClusterMetrics` is already in `cajun-core` — add `cacheHitCount`/`cacheMissCount` there
- Background TTL cleanup must use the **existing** `scheduler` in `ClusterActorSystem` — do not create a new thread pool
- Two copies of `ReliableMessagingSystem` exist but are NOT touched in this phase
- `./gradlew test` must stay green

---

## Tasks

### Task 1 — `TtlCache<K, V>` in `cajun-core`

**File**: `cajun-core/src/main/java/com/cajunsystems/cluster/TtlCache.java`

```java
package com.cajunsystems.cluster;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class TtlCache<K, V> {

    private record Entry<V>(V value, long expiresAtMs) {}

    private final ConcurrentHashMap<K, Entry<V>> map = new ConcurrentHashMap<>();
    private final long ttlMs;

    public TtlCache(long ttlMs) {
        this.ttlMs = ttlMs;
    }

    public void put(K key, V value) {
        map.put(key, new Entry<>(value, System.currentTimeMillis() + ttlMs));
    }

    public Optional<V> get(K key) {
        Entry<V> entry = map.get(key);
        if (entry == null) return Optional.empty();
        if (System.currentTimeMillis() >= entry.expiresAtMs()) {
            map.remove(key, entry);   // conditional remove — only removes if still same entry
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    public void invalidate(K key) {
        map.remove(key);
    }

    /** Returns a snapshot of non-expired entries. */
    public Map<K, V> snapshot() {
        long now = System.currentTimeMillis();
        Map<K, V> result = new HashMap<>();
        map.forEach((k, e) -> {
            if (now < e.expiresAtMs()) result.put(k, e.value());
        });
        return Collections.unmodifiableMap(result);
    }

    /** Removes all expired entries. Call periodically via a scheduler. */
    public void cleanupExpired() {
        long now = System.currentTimeMillis();
        map.entrySet().removeIf(e -> now >= e.getValue().expiresAtMs());
    }

    public int size() {
        return map.size();
    }
}
```

Compile: `./gradlew :cajun-core:compileJava`

Commit: `feat(29-1): add TtlCache — TTL-aware concurrent cache`

---

### Task 2 — Upgrade `actorAssignmentCache` and routing in `ClusterActorSystem`

**File**: `lib/src/main/java/com/cajunsystems/cluster/ClusterActorSystem.java`

This task has multiple sub-changes. Read the file carefully first.

#### 2a — Add import and constant
Add to imports (if `TtlCache` is same package, no import needed — check `ClusterActorSystem`'s package):
```java
import com.cajunsystems.cluster.TtlCache;  // add only if different package
```
Add constant after `HEARTBEAT_INTERVAL_SECONDS`:
```java
private static final long ACTOR_CACHE_TTL_MS = 60_000; // 60-second TTL for actor assignments
```

#### 2b — Replace `actorAssignmentCache` field
**Before** (line 45):
```java
private final Map<String, String> actorAssignmentCache = new ConcurrentHashMap<>();
```
**After**:
```java
private final TtlCache<String, String> actorAssignmentCache = new TtlCache<>(ACTOR_CACHE_TTL_MS);
```

#### 2c — Add cache metrics fields to `ClusterMetrics`
First, update `cajun-core/src/main/java/com/cajunsystems/metrics/ClusterMetrics.java`:
Add two fields:
```java
private final AtomicLong cacheHits = new AtomicLong(0);
private final AtomicLong cacheMisses = new AtomicLong(0);
```
Add increment methods:
```java
public void incrementCacheHit() { cacheHits.incrementAndGet(); }
public void incrementCacheMiss() { cacheMisses.incrementAndGet(); }
```
Add getters:
```java
public long getCacheHits() { return cacheHits.get(); }
public long getCacheMisses() { return cacheMisses.get(); }
```
Update `toString()` to include cache hit/miss counts.

#### 2d — Restructure `routeMessage()` to use cache as primary path
**Current flow**: local actor check → etcd lookup → (cache used only on etcd failure)
**New flow**: local actor check → **cache check** → etcd lookup (cache miss only)

Find the block starting at line ~258:
```java
// Actor is not local, look up its location in the metadata store
metadataStore.get(ACTOR_ASSIGNMENT_PREFIX + actorId)
    .thenAccept(optionalNodeId -> { ...
```

Replace with:
```java
// Check assignment cache before going to metadata store
Optional<String> cachedNodeId = actorAssignmentCache.get(actorId);
if (cachedNodeId.isPresent()) {
    clusterMetrics.incrementCacheHit();
    routeToNode(actorId, cachedNodeId.get(), message, deliveryGuarantee);
    return;
}

// Cache miss — look up in metadata store
clusterMetrics.incrementCacheMiss();
metadataStore.get(ACTOR_ASSIGNMENT_PREFIX + actorId)
    .thenAccept(optionalNodeId -> {
        if (optionalNodeId.isPresent()) {
            String nodeId = optionalNodeId.get();
            actorAssignmentCache.put(actorId, nodeId);  // populate cache for next time
            routeToNode(actorId, nodeId, message, deliveryGuarantee);
        } else {
            logger.warn("Actor '{}' not found in cluster metadata store — not yet registered or already deregistered", actorId);
        }
    })
    .exceptionally(ex -> {
        // etcd unreachable — no cache entry (already checked above), message dropped
        logger.error("Metadata store unreachable and no cached assignment for actor '{}' — message dropped", actorId);
        return null;
    });
```

Extract the routing-to-a-specific-node logic into a private helper method `routeToNode()` to avoid duplication between cache hit and cache miss paths:
```java
@SuppressWarnings("unchecked")
private <Message> void routeToNode(String actorId, String nodeId, Message message,
                                    DeliveryGuarantee deliveryGuarantee) {
    if (nodeId.equals(systemId)) {
        // Registered to this node — find locally
        Actor<Message> localActor = (Actor<Message>) getActor(new Pid(actorId, this));
        if (localActor != null) {
            clusterMetrics.incrementLocalMessagesRouted();
            localActor.tell(message);
        } else {
            logger.warn("Actor '{}' is registered to node '{}' (this node) but not found in local actor registry — it may still be initializing", actorId, systemId);
        }
    } else {
        // Remote node
        long routeStart = System.nanoTime();
        clusterMetrics.incrementRemoteMessagesRouted();
        CompletableFuture<Void> sendFuture;
        if (messagingSystem instanceof ReliableMessagingSystem rms) {
            sendFuture = rms.sendMessage(nodeId, actorId, message, deliveryGuarantee);
        } else {
            sendFuture = messagingSystem.sendMessage(nodeId, actorId, message);
        }
        sendFuture
            .thenRun(() -> clusterMetrics.recordRoutingLatency(System.nanoTime() - routeStart))
            .exceptionally(ex -> {
                clusterMetrics.incrementRemoteMessageFailures();
                logger.error("Failed to send message to actor '{}' on node '{}': {}",
                        actorId, nodeId, ex.getMessage(), ex);
                return null;
            });
    }
}
```

Note: The old `exceptionally()` fallback in `routeMessage()` is now simplified — the cache-as-primary pattern means by the time we reach `exceptionally()`, we know the cache had no entry AND etcd failed, so we just log and drop.

#### 2e — Add actor assignment watcher in `start()`
In `start()`, after the node watcher block (around line 117), add:
```java
// Watch for actor assignment changes — drive cache eviction/update
metadataStore.watch(ACTOR_ASSIGNMENT_PREFIX, new MetadataStore.KeyWatcher() {
    @Override
    public void onPut(String key, String value) {
        // Actor moved to a different node — update cache immediately
        String watchedActorId = key.substring(ACTOR_ASSIGNMENT_PREFIX.length());
        actorAssignmentCache.put(watchedActorId, value);
        logger.debug("Actor assignment updated via watcher: '{}' → node '{}'", watchedActorId, value);
    }
    @Override
    public void onDelete(String key) {
        // Actor deregistered — evict from cache immediately
        String watchedActorId = key.substring(ACTOR_ASSIGNMENT_PREFIX.length());
        actorAssignmentCache.invalidate(watchedActorId);
        logger.debug("Actor assignment evicted via watcher: '{}'", watchedActorId);
    }
});

// Schedule periodic TTL cleanup (every 30 seconds)
scheduler.scheduleAtFixedRate(
        actorAssignmentCache::cleanupExpired,
        30,
        30,
        TimeUnit.SECONDS
);
```

#### 2f — Update `getActorAssignmentCache()` accessor
Find current:
```java
public Map<String, String> getActorAssignmentCache() {
    return Collections.unmodifiableMap(actorAssignmentCache);
}
```
Replace with:
```java
public Map<String, String> getActorAssignmentCache() {
    return actorAssignmentCache.snapshot();
}
```

Compile: `./gradlew :cajun-core:compileJava :lib:compileJava`

Commit: `feat(29-1): upgrade actorAssignmentCache to TtlCache with primary cache-hit routing path`

---

### Task 3 — gRPC channel configuration in `EtcdMetadataStore`

**File**: `cajun-cluster/src/main/java/com/cajunsystems/cluster/impl/EtcdMetadataStore.java`

**1. Add new fields** (after `endpoints`):
```java
private final long keepAliveTimeSeconds;
private final long keepAliveTimeoutSeconds;
private final boolean keepAliveWithoutCalls;
```

**2. Add overloaded constructors** — keep the existing one-arg constructor working:
```java
public EtcdMetadataStore(String... endpoints) {
    this(5L, 3L, true, endpoints);
}

public EtcdMetadataStore(long keepAliveTimeSeconds, long keepAliveTimeoutSeconds,
                          boolean keepAliveWithoutCalls, String... endpoints) {
    this.endpoints = endpoints;
    this.keepAliveTimeSeconds = keepAliveTimeSeconds;
    this.keepAliveTimeoutSeconds = keepAliveTimeoutSeconds;
    this.keepAliveWithoutCalls = keepAliveWithoutCalls;
}
```

**3. Update `connect()`** — add imports and builder options:
```java
import java.util.concurrent.TimeUnit; // (already imported?)
```

Update `connect()`:
```java
@Override
public CompletableFuture<Void> connect() {
    return CompletableFuture.runAsync(() -> {
        client = Client.builder()
                .endpoints(endpoints)
                .keepAliveTime(keepAliveTimeSeconds, TimeUnit.SECONDS)
                .keepAliveTimeout(keepAliveTimeoutSeconds, TimeUnit.SECONDS)
                .keepAliveWithoutCalls(keepAliveWithoutCalls)
                .build();
    });
}
```

Note: check if `Client.builder()` in the version of jetcd used (0.8.4) supports these methods. If `keepAliveWithoutCalls` isn't available, use just `keepAliveTime` and `keepAliveTimeout`.

Compile: `./gradlew :cajun-cluster:compileJava`

Commit: `feat(29-1): configure gRPC keep-alive and connection settings in EtcdMetadataStore`

---

### Task 4 — Parallel batch actor registration

**File**: `lib/src/main/java/com/cajunsystems/cluster/ClusterActorSystem.java`

Add a new public method for batch-parallel actor registration:

```java
/**
 * Registers multiple actors in the metadata store in parallel.
 * This is significantly faster than registering actors one-by-one for large numbers of actors.
 *
 * @param actorIds List of actor IDs to register (must already be registered locally)
 * @return A CompletableFuture that completes when all registrations are done
 */
public CompletableFuture<Void> batchRegisterActors(List<String> actorIds) {
    if (actorIds == null || actorIds.isEmpty()) {
        return CompletableFuture.completedFuture(null);
    }
    long start = System.nanoTime();
    List<CompletableFuture<Void>> futures = actorIds.stream()
            .map(actorId -> metadataStore.put(ACTOR_ASSIGNMENT_PREFIX + actorId, systemId)
                    .exceptionally(ex -> {
                        logger.error("Failed to batch-register actor '{}' in metadata store: {}",
                                actorId, ex.getMessage());
                        return null;
                    }))
            .toList();
    return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
            .thenRun(() -> logger.info("Batch registered {} actors in {}ms",
                    actorIds.size(), (System.nanoTime() - start) / 1_000_000));
}
```

Add `import java.util.List;` if not already present.

Compile: `./gradlew :lib:compileJava`

Commit: `feat(29-1): add batchRegisterActors for parallel metadata store registration`

---

### Task 5 — Tests and benchmarks

#### 5a. `TtlCacheTest.java`
**File**: `lib/src/test/java/com/cajunsystems/cluster/TtlCacheTest.java`

Tests (all unit, no external dependencies):
- `testPutAndGet()` — put "k"/"v", get returns Optional.of("v")
- `testExpiredEntryReturnsEmpty()` — put with 50ms TTL, sleep 60ms, get returns Optional.empty()
- `testInvalidateRemovesEntry()` — put, invalidate, get returns empty
- `testSnapshot_excludesExpiredEntries()` — put two entries (one with 50ms TTL), sleep 60ms, snapshot has only the live one
- `testCleanupExpired_removesFromInternalMap()` — put with 50ms TTL, sleep 60ms, cleanupExpired(), size() == 0
- `testConcurrentPutAndGet()` — 10 threads each put 100 entries, assert snapshot().size() ≈ 1000 (no NPE/exception)
- `testOverwriteResetsExpiry()` — put with 50ms TTL, sleep 30ms, put again (resets TTL), sleep 30ms, get still returns value (total ~60ms but second put was at 30ms so expiry is 80ms total)

#### 5b. `ClusterRoutingBenchmarkTest.java`
**File**: `lib/src/test/java/com/cajunsystems/cluster/ClusterRoutingBenchmarkTest.java`

Tag: `@Tag("performance")` (excluded from normal `./gradlew test`)

```java
@Tag("performance")
class ClusterRoutingBenchmarkTest {
    static final int N = 1000;
    
    @Test
    void benchmark_cacheHit_routing_throughput() {
        // Pre-populate cache with N actor→node assignments
        // Route N messages and measure time
        // Print [BENCH] cache-hit throughput in ops/sec
    }

    @Test
    void benchmark_batchRegistration_vs_sequential() {
        // Sequential: register 50 actors one by one (measuring total time)
        // Parallel: register 50 actors via batchRegisterActors() (measuring total time)
        // Print [BENCH] registration time comparison
        // Assert batch time < sequential time (with in-memory metadata store)
    }
}
```

Use an inline `InMemoryMetadataStore` (same pattern as `DegradedRoutingTest`) and `NoOpMessagingSystem`. For the cache hit benchmark:
- Call `actorAssignmentCache.put()` directly (or route one message to populate cache first)
- Then route N messages measuring throughput

For the batch vs sequential registration benchmark, use an `InMemoryMetadataStore` with an artificial 1ms delay per `put()` to make the difference measurable:
```java
@Override
public CompletableFuture<Void> put(String key, String value) {
    store.put(key, value);
    // simulate network latency
    return CompletableFuture.supplyAsync(() -> {
        try { Thread.sleep(1); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return (Void) null;
    });
}
```

Assert correctness only (no timing SLAs — timing is environment-dependent). Print `[BENCH]` results for human review.

Compile: `./gradlew :lib:compileTestJava`

Commit: `test(29-1): TtlCache unit tests and cluster routing benchmark`

---

## Checkpoint: Verify after Task 5
```bash
./gradlew test
```
Expected: BUILD SUCCESSFUL. All non-`requires-redis`/non-`requires-etcd`/non-`performance` tests pass.
Note: `ClusterRoutingBenchmarkTest` is `@Tag("performance")` and will NOT run in `./gradlew test` — it's excluded by default.

## Success Criteria
- [ ] `TtlCache<K, V>` with TTL expiry, invalidate, snapshot, cleanupExpired
- [ ] `actorAssignmentCache` in `ClusterActorSystem` upgraded to `TtlCache` with 60s TTL
- [ ] `routeMessage()` uses cache as primary path (cache hit → skip etcd), `routeToNode()` extracted as helper
- [ ] Actor assignment watcher in `start()` drives cache eviction/update
- [ ] Periodic `cleanupExpired()` scheduled every 30s via existing scheduler
- [ ] `ClusterMetrics` has `cacheHitCount` and `cacheMissCount`
- [ ] `EtcdMetadataStore` configures gRPC keepAlive settings in `connect()`
- [ ] `batchRegisterActors(List<String>)` in `ClusterActorSystem` uses `CompletableFuture.allOf()`
- [ ] `TtlCacheTest` (7 tests) + `ClusterRoutingBenchmarkTest` (2 perf tests)
- [ ] `./gradlew test` green

## Output
New files:
- `cajun-core/src/main/java/com/cajunsystems/cluster/TtlCache.java`
- `lib/src/test/java/com/cajunsystems/cluster/TtlCacheTest.java`
- `lib/src/test/java/com/cajunsystems/cluster/ClusterRoutingBenchmarkTest.java`

Modified files:
- `cajun-core/src/main/java/com/cajunsystems/metrics/ClusterMetrics.java` (cache hit/miss counters)
- `lib/src/main/java/com/cajunsystems/cluster/ClusterActorSystem.java` (TtlCache, routing restructure, watcher, batchRegisterActors)
- `cajun-cluster/src/main/java/com/cajunsystems/cluster/impl/EtcdMetadataStore.java` (gRPC config)
