---
plan: 30-1
phase: 30 — Cluster Management API
title: ClusterConfiguration Builder + ClusterManagementApi (read ops)
type: feat
status: pending
---

## Objective

Deliver two things in one plan:
1. `ClusterConfiguration` — a fluent builder that replaces raw `ClusterActorSystem` constructor args
2. `ClusterManagementApi` with read-only cluster operations: `listNodes()` and `listActors(nodeId)`

Write-operations (migrate, drain) come in plan 30-2. Tests for all of 30-1 are included here.

---

## Execution Context

Key files to read before writing:
- `lib/src/main/java/com/cajunsystems/cluster/ClusterActorSystem.java` — constructors, fields, `getKnownNodes()`, `start()` signature
- `lib/src/main/java/com/cajunsystems/cluster/MetadataStore.java` — `listKeys()` signature
- `lib/src/main/java/com/cajunsystems/cluster/DeliveryGuarantee.java` — enum values
- `lib/src/main/java/com/cajunsystems/cluster/MessagingSystem.java` — interface for type reference

Constants (already confirmed in ClusterActorSystem):
```
ACTOR_ASSIGNMENT_PREFIX = "cajun/actor/"
NODE_PREFIX              = "cajun/node/"
```
These are `private static final` — Task 1 makes them package-private so `ClusterManagementApi` can reuse them.

---

## Tasks

### Task 1 — Expose key-prefix constants as package-private [refactor]

**File**: `lib/src/main/java/com/cajunsystems/cluster/ClusterActorSystem.java`

Change `private` to package-private (no modifier) on the two prefix constants:
```java
// Before
private static final String ACTOR_ASSIGNMENT_PREFIX = "cajun/actor/";
private static final String NODE_PREFIX = "cajun/node/";

// After
static final String ACTOR_ASSIGNMENT_PREFIX = "cajun/actor/";
static final String NODE_PREFIX = "cajun/node/";
```

No other changes to this file in this task.

---

### Task 2 — Implement `ClusterConfiguration` builder [feat]

**New file**: `lib/src/main/java/com/cajunsystems/cluster/ClusterConfiguration.java`

```java
package com.cajunsystems.cluster;

import java.util.UUID;

/**
 * Fluent builder for ClusterActorSystem.
 *
 * <pre>
 *   ClusterActorSystem system = ClusterConfiguration.builder()
 *       .systemId("node-1")
 *       .metadataStore(etcdStore)
 *       .messagingSystem(rms)
 *       .deliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE)
 *       .persistenceProvider(redisProvider)
 *       .build();
 * </pre>
 *
 * {@code metadataStore} and {@code messagingSystem} are required;
 * all other fields are optional.
 */
public final class ClusterConfiguration {

    private final String systemId;
    private final MetadataStore metadataStore;
    private final MessagingSystem messagingSystem;
    private final DeliveryGuarantee deliveryGuarantee;
    private final com.cajunsystems.persistence.PersistenceProvider persistenceProvider;

    private ClusterConfiguration(Builder b) {
        this.systemId = b.systemId;
        this.metadataStore = b.metadataStore;
        this.messagingSystem = b.messagingSystem;
        this.deliveryGuarantee = b.deliveryGuarantee;
        this.persistenceProvider = b.persistenceProvider;
    }

    /** Entry point for the builder. */
    public static Builder builder() { return new Builder(); }

    /** Builds and returns a fully configured but not-yet-started ClusterActorSystem. */
    public ClusterActorSystem build() {
        if (metadataStore == null) throw new IllegalStateException("metadataStore is required");
        if (messagingSystem == null) throw new IllegalStateException("messagingSystem is required");

        ClusterActorSystem system = new ClusterActorSystem(systemId, metadataStore, messagingSystem);
        if (deliveryGuarantee != null) system.withDeliveryGuarantee(deliveryGuarantee);
        if (persistenceProvider != null) system.withPersistenceProvider(persistenceProvider);
        return system;
    }

    // ── accessors ──────────────────────────────────────────────────────────────

    public String systemId()          { return systemId; }
    public MetadataStore metadataStore() { return metadataStore; }
    public MessagingSystem messagingSystem() { return messagingSystem; }
    public DeliveryGuarantee deliveryGuarantee() { return deliveryGuarantee; }
    public com.cajunsystems.persistence.PersistenceProvider persistenceProvider() {
        return persistenceProvider;
    }

    // ── Builder ────────────────────────────────────────────────────────────────

    public static final class Builder {
        private String systemId = "cajun-node-" + UUID.randomUUID().toString().substring(0, 8);
        private MetadataStore metadataStore;
        private MessagingSystem messagingSystem;
        private DeliveryGuarantee deliveryGuarantee;
        private com.cajunsystems.persistence.PersistenceProvider persistenceProvider;

        private Builder() {}

        public Builder systemId(String systemId) {
            this.systemId = systemId; return this;
        }
        public Builder metadataStore(MetadataStore metadataStore) {
            this.metadataStore = metadataStore; return this;
        }
        public Builder messagingSystem(MessagingSystem messagingSystem) {
            this.messagingSystem = messagingSystem; return this;
        }
        public Builder deliveryGuarantee(DeliveryGuarantee deliveryGuarantee) {
            this.deliveryGuarantee = deliveryGuarantee; return this;
        }
        public Builder persistenceProvider(com.cajunsystems.persistence.PersistenceProvider persistenceProvider) {
            this.persistenceProvider = persistenceProvider; return this;
        }

        public ClusterConfiguration build() { return new ClusterConfiguration(this); }
    }
}
```

Note: `build()` on `Builder` returns a `ClusterConfiguration`; call `.build().build()` to get the `ClusterActorSystem`, or use `ClusterConfiguration.builder()...build()` directly (the `ClusterConfiguration.build()` is the terminal step).

Actually: simplify — `Builder.build()` directly returns `ClusterActorSystem` (so callers don't call `.build().build()`):
```java
// Builder.build():
public ClusterActorSystem build() {
    return new ClusterConfiguration(this).build();
}
```
`ClusterConfiguration` itself is then a config value object (used internally or for introspection). This is the cleaner public API.

---

### Task 3 — Implement `ClusterManagementApi` interface + read-only impl [feat]

**New file 1**: `lib/src/main/java/com/cajunsystems/cluster/ClusterManagementApi.java`

```java
package com.cajunsystems.cluster;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Programmatic API for cluster operations.
 *
 * <p>Obtain via {@link ClusterActorSystem#getManagementApi()}.
 */
public interface ClusterManagementApi {

    /** Returns the IDs of all nodes currently registered in the cluster. */
    CompletableFuture<Set<String>> listNodes();

    /**
     * Returns the IDs of all actors currently assigned to the given node.
     * Queries the metadata store directly (not the local TTL cache).
     */
    CompletableFuture<Set<String>> listActors(String nodeId);

    /**
     * Migrates an actor to the specified target node.
     * Updates the metadata store assignment; if the actor is running locally,
     * it is stopped so it can be lazily recovered on the target.
     *
     * @param actorId      the actor to migrate
     * @param targetNodeId the destination node
     */
    CompletableFuture<Void> migrateActor(String actorId, String targetNodeId);

    /**
     * Drains a node: migrates all actors away from {@code nodeId} to other
     * available nodes using rendezvous hashing, excluding the drained node.
     * Returns when all actor assignments have been updated in the metadata store.
     *
     * @param nodeId the node to drain
     */
    CompletableFuture<Void> drainNode(String nodeId);
}
```

**New file 2**: `lib/src/main/java/com/cajunsystems/cluster/DefaultClusterManagementApi.java`

Implements `listNodes()` and `listActors()` in this plan. `migrateActor` and `drainNode` throw `UnsupportedOperationException` as stubs (implemented in plan 30-2).

```java
package com.cajunsystems.cluster;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

class DefaultClusterManagementApi implements ClusterManagementApi {

    private final ClusterActorSystem system;
    private final MetadataStore metadataStore;

    DefaultClusterManagementApi(ClusterActorSystem system) {
        this.system = system;
        this.metadataStore = system.getMetadataStore();
    }

    @Override
    public CompletableFuture<Set<String>> listNodes() {
        return metadataStore.listKeys(ClusterActorSystem.NODE_PREFIX)
                .thenApply(keys -> keys.stream()
                        .map(k -> k.substring(ClusterActorSystem.NODE_PREFIX.length()))
                        .collect(Collectors.toSet()));
    }

    @Override
    public CompletableFuture<Set<String>> listActors(String nodeId) {
        return metadataStore.listKeys(ClusterActorSystem.ACTOR_ASSIGNMENT_PREFIX)
                .thenCompose(keys -> {
                    // For each actor key, fetch its assigned node and filter by nodeId
                    List<CompletableFuture<Optional<String>>> futures = keys.stream()
                            .map(key -> metadataStore.get(key)
                                    .thenApply(optVal -> optVal
                                            .filter(nodeId::equals)
                                            .map(ignored -> key.substring(
                                                    ClusterActorSystem.ACTOR_ASSIGNMENT_PREFIX.length()))))
                            .collect(Collectors.toList());

                    return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                            .thenApply(v -> futures.stream()
                                    .map(CompletableFuture::join)
                                    .filter(Optional::isPresent)
                                    .map(Optional::get)
                                    .collect(Collectors.toSet()));
                });
    }

    @Override
    public CompletableFuture<Void> migrateActor(String actorId, String targetNodeId) {
        throw new UnsupportedOperationException("migrateActor implemented in plan 30-2");
    }

    @Override
    public CompletableFuture<Void> drainNode(String nodeId) {
        throw new UnsupportedOperationException("drainNode implemented in plan 30-2");
    }
}
```

---

### Task 4 — Wire `getManagementApi()` into ClusterActorSystem [feat]

**File**: `lib/src/main/java/com/cajunsystems/cluster/ClusterActorSystem.java`

Add a field and accessor:
```java
private final ClusterManagementApi managementApi = new DefaultClusterManagementApi(this);

/**
 * Returns the management API for programmatic cluster operations.
 */
public ClusterManagementApi getManagementApi() {
    return managementApi;
}
```

Place the field declaration alongside the other `private final` fields (~line 43).
Place `getManagementApi()` after `getClusterMetrics()`.

Also add `invalidateActorAssignmentCache(String actorId)` package-private method (needed by 30-2):
```java
void invalidateActorAssignmentCache(String actorId) {
    actorAssignmentCache.invalidate(actorId);
}
```

---

### Task 5 — Unit tests [test]

**New file**: `lib/src/test/java/com/cajunsystems/cluster/ClusterManagementApiReadTest.java`

Use an **in-memory** `MetadataStore` stub (a simple `ConcurrentHashMap`-backed implementation defined as a static inner class in the test). Do NOT use mocks — implementing the interface is cleaner and avoids Mockito strict-stubbing issues.

Tests to write:

**ClusterConfiguration tests:**
1. `builder_withAllFields_buildsSetsFields()` — verify systemId, deliveryGuarantee stored correctly
2. `builder_withoutOptionalFields_buildsSuccessfully()` — only metadataStore + messagingSystem required
3. `builder_missingMetadataStore_throwsIllegalState()` — validate required fields
4. `builder_missingMessagingSystem_throwsIllegalState()`
5. `builder_defaultSystemId_isNonEmpty()` — auto-generated ID is not blank

**ClusterManagementApi read tests:**
6. `listNodes_returnsRegisteredNodes()` — pre-populate `cajun/node/nodeA` + `cajun/node/nodeB`, verify both returned
7. `listNodes_emptyCluster_returnsEmptySet()` — no nodes registered
8. `listActors_returnsActorsForNode()` — register actorX→nodeA, actorY→nodeB, listActors("nodeA") returns {actorX}
9. `listActors_noActorsOnNode_returnsEmptySet()`
10. `listActors_multipleActorsOnNode_returnsAll()`

**In-memory MetadataStore stub** (defined as private static inner class in test):
```java
private static class InMemoryMetadataStore implements MetadataStore {
    private final ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();

    @Override
    public CompletableFuture<Void> put(String key, String value) {
        store.put(key, value);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Optional<String>> get(String key) {
        return CompletableFuture.completedFuture(Optional.ofNullable(store.get(key)));
    }

    @Override
    public CompletableFuture<Void> delete(String key) {
        store.remove(key);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<List<String>> listKeys(String prefix) {
        List<String> keys = store.keySet().stream()
                .filter(k -> k.startsWith(prefix))
                .collect(Collectors.toList());
        return CompletableFuture.completedFuture(keys);
    }

    @Override
    public CompletableFuture<Optional<Lock>> acquireLock(String lockName, long ttlSeconds) {
        return CompletableFuture.completedFuture(Optional.empty());
    }

    @Override
    public CompletableFuture<Long> watch(String key, KeyWatcher watcher) {
        return CompletableFuture.completedFuture(0L);
    }

    @Override
    public CompletableFuture<Void> unwatch(long watchId) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> connect() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> close() {
        return CompletableFuture.completedFuture(null);
    }
}
```

For `ClusterManagementApi` tests, instantiate `DefaultClusterManagementApi` directly — do NOT call `system.start()` (avoids needing a real etcd + messaging system). Pre-populate the `InMemoryMetadataStore` to simulate cluster state.

Note: `DefaultClusterManagementApi` constructor takes `ClusterActorSystem`. To avoid starting the system, instantiate `ClusterActorSystem` but do NOT call `start()`. The `metadataStore` field is accessed via `getMetadataStore()` which is already public.

---

## Verification

```bash
./gradlew :lib:compileJava
./gradlew :lib:compileTestJava
./gradlew test --tests "com.cajunsystems.cluster.ClusterManagementApiReadTest"
```

All 10 tests green. No existing tests broken.

---

## Success Criteria

- [ ] `ACTOR_ASSIGNMENT_PREFIX` and `NODE_PREFIX` are package-private in `ClusterActorSystem`
- [ ] `ClusterConfiguration.builder()...build()` produces a configured `ClusterActorSystem`
- [ ] `ClusterManagementApi` interface exists with all 4 method signatures
- [ ] `DefaultClusterManagementApi` implements `listNodes()` and `listActors()` correctly
- [ ] `ClusterActorSystem.getManagementApi()` returns the api
- [ ] `ClusterActorSystem.invalidateActorAssignmentCache()` added (package-private)
- [ ] 10 unit tests green
- [ ] `./gradlew test` (full suite) green

---

## Output

New files:
- `lib/src/main/java/com/cajunsystems/cluster/ClusterConfiguration.java`
- `lib/src/main/java/com/cajunsystems/cluster/ClusterManagementApi.java`
- `lib/src/main/java/com/cajunsystems/cluster/DefaultClusterManagementApi.java`
- `lib/src/test/java/com/cajunsystems/cluster/ClusterManagementApiReadTest.java`

Modified files:
- `lib/src/main/java/com/cajunsystems/cluster/ClusterActorSystem.java` (constants visibility, getManagementApi, invalidateActorAssignmentCache)
