---
plan: 30-2
phase: 30 — Cluster Management API
title: migrateActor + drainNode + tests
status: complete
---

## What Was Done

### Task 1+2 — migrateActor + drainNode implemented (`a9b32f4`)

`DefaultClusterManagementApi` fully implemented:

**migrateActor(actorId, targetNodeId)**:
- Validates target against `listNodes()` (metadata store query, not in-memory cache) — testable without calling `start()`
- Updates `cajun/actor/{actorId}` in etcd to point to target
- Invalidates TTL cache entry immediately
- Calls `shutdownLocalOnly(actorId)` if actor is running on this node (see deviation below)

**drainNode(nodeId)**:
- Queries live node list from etcd; removes drained node to get `remainingNodes`
- Fails fast if no remaining nodes available
- Calls `listActors(nodeId)` for authoritative etcd snapshot
- Uses `RendezvousHashing.assignKey()` for consistent placement matching ClusterActorSystem's own logic
- Runs all migrations in parallel via `CompletableFuture.allOf()`
- Per-actor failures logged + swallowed (best-effort drain)

### Task 3 — Shared InMemoryMetadataStore extracted (`2a0ad24`)

`InMemoryMetadataStore` moved from inner class in `ClusterManagementApiReadTest` to a package-private top-level class used by all cluster test files.

### Task 4+5 — Tests (`24625cd`)

9 new tests (4 migrate + 5 drain), all using `InMemoryMetadataStore` + `NoopMessagingSystem`.
No etcd or external services required. Does not call `system.start()`.

## Deviation: shutdownLocalOnly needed (`3d91289`)

**Problem discovered during testing**: `ClusterActorSystem.shutdown(actorId)` deletes the etcd assignment as part of the cluster unregister path. Calling it inside `migrateActor` would:
1. Put new assignment `cajun/actor/X = target-node` ✅
2. Call `shutdown()` which deletes `cajun/actor/X` ❌ — undoes the migration

**Fix**: Added `shutdownLocalOnly(String actorId)` (package-private) to `ClusterActorSystem` that calls `super.shutdown(actorId)` only — stops the local mailbox without touching etcd. `migrateActor` uses this instead.

## Test Results

- Compile: BUILD SUCCESSFUL
- Migrate tests: 4/4 passing
- Drain tests: 5/5 passing  
- Full `./gradlew test`: BUILD SUCCESSFUL

## Commits

| Hash | Message |
|------|---------|
| `a9b32f4` | feat(30-2): implement migrateActor and drainNode in DefaultClusterManagementApi |
| `2a0ad24` | refactor(30-2): extract InMemoryMetadataStore to shared test helper |
| `3d91289` | fix(30-2): add shutdownLocalOnly to prevent migration from overwriting etcd assignment |
| `24625cd` | test(30-2): ClusterManagementApi migrate and drain tests |
