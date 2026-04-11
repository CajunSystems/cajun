---
plan: 31-1
phase: 31 — Testing, Documentation & Examples
title: Cluster Integration Tests (chaos + management API lifecycle)
type: test
status: complete
---

## Objective

Deliver two things:
1. Shared test helpers — extract `WatchableInMemoryMetadataStore` and `InMemoryMessagingSystem` from private inner classes in `ClusterModeTest` into package-private top-level classes, usable by all cluster tests.
2. New integration tests — chaos simulation (sequential node failures) and management API lifecycle (planned drain before shutdown).

Does NOT require etcd, Redis, or any external services.

---

## Execution Context

Read these files before writing:

- `/Users/pradeep.samuel/cajun/lib/src/test/java/com/cajunsystems/cluster/ClusterModeTest.java` — extract the `InMemoryMetadataStore` (lines ~322–432) and `InMemoryMessagingSystem` (lines ~437–end) inner class implementations
- `/Users/pradeep.samuel/cajun/lib/src/test/java/com/cajunsystems/cluster/InMemoryMetadataStore.java` — the simple no-watcher version (keep this, do NOT replace it)
- `/Users/pradeup.samuel/cajun/lib/src/main/java/com/cajunsystems/cluster/ClusterActorSystem.java` — `start()`, `stop()`, `getManagementApi()`, `getSystemId()`, actor registration

---

## Tasks

### Task 1 — Extract shared watcher-capable test helpers [refactor]

**New file 1**: `lib/src/test/java/com/cajunsystems/cluster/WatchableInMemoryMetadataStore.java`

Package-private class extracted from `ClusterModeTest.InMemoryMetadataStore`. Key differences from the simple `InMemoryMetadataStore`:
- `put()` and `delete()` use `CompletableFuture.runAsync()` (not `completedFuture`) — async so watcher callbacks don't block callers
- Watcher map: `ConcurrentHashMap<String, KeyWatcher>` — fires `onPut`/`onDelete` for all keys that start with the watched prefix
- Lock support: `ConcurrentHashMap<String, Lock>` — `acquireLock()` returns non-empty when not already locked; `InMemoryLock.release()` removes from map
- `unwatch()`: no-op is acceptable for tests

Make the `store` field and `locks` field `final` and the implementation **package-private** (no public modifier on the class).

**New file 2**: `lib/src/test/java/com/cajunsystems/cluster/InMemoryMessagingSystem.java`

Package-private class extracted from `ClusterModeTest.InMemoryMessagingSystem`. Behaviour:
- Constructor: `InMemoryMessagingSystem(String systemId)`
- `connectTo(InMemoryMessagingSystem other)`: adds `other` to peer map AND adds `this` to `other`'s peer map (bidirectional)
- `sendMessage(targetSystemId, actorId, message)`: looks up peer by `targetSystemId`, calls `peer.messageHandler.onMessage(actorId, message)` via `CompletableFuture.runAsync()` — returns `completedFuture(null)` if no peer found (silent drop, normal for disconnected nodes)
- `registerMessageHandler(handler)`: stores `this.messageHandler = handler`
- `start()` / `stop()`: `completedFuture(null)`

The `messageHandler` field is `volatile` (multiple threads access it).

**Also**: Update `ClusterModeTest` to remove its now-duplicate inner class definitions and use the shared helpers. Keep the existing test methods unchanged — just swap inner class references with the package-private top-level classes.

Commit: `refactor(31-1): extract WatchableInMemoryMetadataStore and InMemoryMessagingSystem as shared test helpers`

---

### Task 2 — Chaos test: sequential node failures [test]

**New file**: `lib/src/test/java/com/cajunsystems/cluster/ClusterChaosTest.java`

Uses `WatchableInMemoryMetadataStore` + `InMemoryMessagingSystem`. Calls `system.start()` and `system.stop()` — the tests rely on the cluster's heartbeat + reassignment scheduler. Use `Thread.sleep(3000)` between operations (same as `ClusterModeTest`).

```java
@Tag("slow")          // for CI filtering — no external services needed, just slow
class ClusterChaosTest {
    ...
}
```

Three tests:

**Test 1: `singleNodeFailure_actorsReassignedToSurvivingNode()`**
- Setup: 3 nodes (system1, system2, system3), all started; leader elected; register `actor1` on system1
- Action: `system1.stop()` → wait 4s (heartbeat expiry + reassignment)
- Assert: `api.listActors("system2")` or `api.listActors("system3")` contains `actor1` (actor reassigned off dead node)

**Test 2: `sequentialNodeFailures_actorsConcentrateOnSurvivorNode()`**
- Setup: 3 nodes; register actor1 on system1, actor2 on system2
- Action: stop system1 → wait 4s → stop system2 → wait 4s
- Assert: only system3 remains; both actor1 and actor2 reassigned to system3

**Test 3: `nodeRejoin_actorsRouteToNewNode()`**
- Setup: 2 nodes (system1 + system2); register actor1 on system1
- Action: stop system1 → wait 4s (actor1 reassigned to system2) → start system3 (new node)
- Assert: `api.listNodes()` contains system3; new actors can be registered on system3

Verification via `ClusterManagementApi.listActors(nodeId)` — queries live etcd state.

Commit: `test(31-1): chaos tests for sequential node failures and cluster recovery`

---

### Task 3 — Management API lifecycle test: planned drain before shutdown [test]

**New file**: `lib/src/test/java/com/cajunsystems/cluster/ClusterLifecycleTest.java`

These tests use `WatchableInMemoryMetadataStore` + `InMemoryMessagingSystem` with `start()` + `stop()`.

**Test 1: `plannedDrain_beforeShutdown_noOrphanedActors()`**
- Setup: 2 nodes; register 3 actors on system1
- Action: drain system1 via `api.drainNode("system1")` → wait for drain → `system1.stop()`
- Assert: `api.listActors("system1")` is empty; `api.listActors("system2")` contains all 3 actors; subsequent messages to those actors succeed

**Test 2: `plannedMigration_movesActorAndRoutesMessages()`**
- Setup: 2 nodes; register actorX on system1; wire messaging
- Action: `api.migrateActor("actorX", "system2")` → wait 1s → route message to actorX
- Assert: actorX now responds from system2 (verify via `listActors("system2")`)

**Test 3: `gracefulShutdown_undrained_actorsReassignedByLeader()`**
- Setup: 2 nodes, both started; register actor1 on system1
- Action: `system1.stop()` WITHOUT draining first → wait 4s for leader reassignment
- Assert: actor1 is now in system2's actor list (unplanned failure is still handled)

Commit: `test(31-1): cluster lifecycle tests for planned drain and graceful shutdown`

---

## Verification

```bash
./gradlew :lib:compileTestJava
./gradlew :lib:test --tests "com.cajunsystems.cluster.ClusterChaosTest"
./gradlew :lib:test --tests "com.cajunsystems.cluster.ClusterLifecycleTest"
./gradlew test
```

All tests green. No existing tests broken.

**Note on slow tests**: `ClusterChaosTest` uses `Thread.sleep(4000)` per operation — each test takes ~10–20s. Total suite time increase ≤ 60s. This is acceptable; do not add `@Disabled`.

---

## Success Criteria

- [ ] `WatchableInMemoryMetadataStore.java` extracted — fires watchers on put/delete, supports locks
- [ ] `InMemoryMessagingSystem.java` extracted — routes messages between in-JVM systems
- [ ] `ClusterModeTest` updated to use shared helpers (inner classes removed)
- [ ] 3 chaos tests in `ClusterChaosTest` — all green
- [ ] 3 lifecycle tests in `ClusterLifecycleTest` — all green
- [ ] `./gradlew test` green

---

## Output

New files:
- `lib/src/test/java/com/cajunsystems/cluster/WatchableInMemoryMetadataStore.java`
- `lib/src/test/java/com/cajunsystems/cluster/InMemoryMessagingSystem.java`
- `lib/src/test/java/com/cajunsystems/cluster/ClusterChaosTest.java`
- `lib/src/test/java/com/cajunsystems/cluster/ClusterLifecycleTest.java`

Modified files:
- `lib/src/test/java/com/cajunsystems/cluster/ClusterModeTest.java` (remove inner class duplicates)
