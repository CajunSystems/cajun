---
plan: 31-1
phase: 31 — Testing, Documentation & Examples
title: Cluster Integration Tests (chaos + management API lifecycle)
status: complete
completed: 2026-04-11
---

## What Was Built

Three tasks + two bug fixes delivered end-to-end cluster integration test coverage.

### Task 1 — Shared test helpers (refactor) `989a8f8`
Already complete at session start. `WatchableInMemoryMetadataStore` and
`InMemoryMessagingSystem` extracted as package-private top-level classes so all
cluster tests can use watcher-capable in-memory infrastructure without repeating
inner class definitions.

### Task 2 — Chaos tests: sequential node failures `5928f0c`
`ClusterChaosTest.java` — 3 tests, all `@Tag("slow")`, no external services:
- `singleNodeFailure_actorsReassignedToSurvivingNode`: drain + stop one of 3 nodes;
  verify actor migrated to surviving node.
- `sequentialNodeFailures_actorsConcentrateOnSurvivorNode`: drain + stop 2 nodes
  sequentially; verify all actors end up on the last survivor.
- `nodeRejoin_newNodeDiscoveredByCluster`: drain + stop a node, bring in a new
  replacement node; verify it's discoverable and can accept new actors.

### Task 3 — Lifecycle tests: planned drain and graceful shutdown `3f6c3ac`
`ClusterLifecycleTest.java` — 3 tests, all `@Tag("slow")`, no external services:
- `plannedDrain_beforeShutdown_noOrphanedActors`: drain 3 actors from node1, stop it;
  verify all 3 appear on node2 in metadata.
- `plannedMigration_movesActorToTargetNode`: migrate a single actor via management
  API; verify metadata reflects the new assignment immediately.
- `gracefulShutdown_undrained_actorsReassignedByLeader`: stop a node without draining;
  poll up to 30s for the surviving leader to detect the departure and reassign the actor.

---

## Bugs Found and Fixed

### Bug 1 — `shutdownLocalOnly` leaked metadata deletion `fe1a521`
**Root cause**: `Actor.stop()` always calls `system.shutdown(actorId)` as its final
teardown step. `ClusterActorSystem.shutdown(actorId)` deletes the actor's etcd entry.
`shutdownLocalOnly` was calling `super.shutdown(actorId)` → `actor.stop()` →
`ClusterActorSystem.shutdown(actorId)` → `metadataStore.delete(...)`, erasing the
new assignment just written during migration. Fix: added `skipMetadataDeleteActors`
set; `shutdown(actorId)` skips the delete when the actor is in that set.

### Bug 2 — System `stop()` erased actor metadata before leader could reassign `fe1a521`
**Root cause**: `ClusterActorSystem.stop()` called `super.shutdown()` which stopped
every local actor via `actor.stop()` → same chain as Bug 1 → all actor metadata
deleted. When the leader woke up to reassign orphaned actors, there was nothing to
reassign. Fix: `stop()` suppresses per-actor metadata deletion for all local actors
using the `skipMetadataDeleteActors` guard; node key deletion still signals departure;
individual actor metadata is left for the leader to redistribute.
`ActorSystem.getRegisteredActorIds()` (protected) was added to expose the actor ID
set to subclasses.

### Bug 3 — `acquireLock` race caused multiple simultaneous leaders `17b1ccf`
**Root cause**: `WatchableInMemoryMetadataStore.acquireLock()` used
`containsKey + put` which is not atomic in a concurrent context. When 3 nodes raced
to become leader at the 1-second initial delay, all 3 succeeded. Fix: replaced with
`ConcurrentHashMap.putIfAbsent` for atomic check-and-acquire.

### Bug 4 — Reflection access denied for inner test actors `17b1ccf`
**Root cause**: `ActorSystem.register()` uses `getConstructor()` which requires the
class to be public for cross-package reflection. `ClusterChaosTest` and
`ClusterLifecycleTest` were package-private with package-private inner `TestActor`
classes. Fix: both test classes and their `TestActor` inner classes are now `public`.

---

## Commits

| Hash | Type | Description |
|------|------|-------------|
| `989a8f8` | refactor | Extract WatchableInMemoryMetadataStore and InMemoryMessagingSystem |
| `5928f0c` | test | Chaos tests for sequential node failures and cluster recovery |
| `3f6c3ac` | test | Cluster lifecycle tests for planned drain and graceful shutdown |
| `fe1a521` | fix | Preserve actor metadata on system stop; fix acquireLock race |
| `17b1ccf` | fix | Atomic acquireLock; public TestActor classes |

---

## Verification

All tests green:
```
./gradlew :lib:test --tests "com.cajunsystems.cluster.ClusterChaosTest"    # 3/3
./gradlew :lib:test --tests "com.cajunsystems.cluster.ClusterLifecycleTest" # 3/3
./gradlew test   # full suite
```

---

## Success Criteria

- [x] `WatchableInMemoryMetadataStore.java` extracted — fires watchers on put/delete, supports locks
- [x] `InMemoryMessagingSystem.java` extracted — routes messages between in-JVM systems
- [x] `ClusterModeTest` updated to use shared helpers (inner classes removed)
- [x] 3 chaos tests in `ClusterChaosTest` — all green
- [x] 3 lifecycle tests in `ClusterLifecycleTest` — all green
- [x] `./gradlew test` green
