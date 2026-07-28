---
plan: 30-2
phase: 30 — Cluster Management API
title: migrateActor + drainNode + tests
type: feat
status: pending
---

## Objective

Complete `ClusterManagementApi` by implementing `migrateActor` and `drainNode` in `DefaultClusterManagementApi`. Add comprehensive tests including drain-and-rejoin and forced migration scenarios.

Prerequisite: plan 30-1 must be complete (interfaces exist, stubs in place, `invalidateActorAssignmentCache` wired).

---

## Execution Context

Key files to read before writing:
- `lib/src/main/java/com/cajunsystems/cluster/DefaultClusterManagementApi.java` — add implementations here
- `lib/src/main/java/com/cajunsystems/cluster/ClusterActorSystem.java` — `shutdown(actorId)`, `getActors()` (inherited), `getSystemId()`, `getKnownNodes()`, `invalidateActorAssignmentCache()`
- `lib/src/main/java/com/cajunsystems/cluster/RendezvousHashing.java` — `assignKey(String key, Collection<String> nodes)`
- `lib/src/test/java/com/cajunsystems/cluster/ClusterManagementApiReadTest.java` — reuse `InMemoryMetadataStore` stub

---

## Tasks

### Task 1 — Implement `migrateActor` [feat]

**File**: `lib/src/main/java/com/cajunsystems/cluster/DefaultClusterManagementApi.java`

Replace the `UnsupportedOperationException` stub with full implementation:

```java
@Override
public CompletableFuture<Void> migrateActor(String actorId, String targetNodeId) {
    Set<String> knownNodes = system.getKnownNodes();
    if (!knownNodes.contains(targetNodeId)) {
        return CompletableFuture.failedFuture(
                new IllegalArgumentException(
                        "Target node '" + targetNodeId + "' is not a known cluster node"));
    }

    return metadataStore.get(ClusterActorSystem.ACTOR_ASSIGNMENT_PREFIX + actorId)
            .thenCompose(currentAssignment -> {
                String sourceNodeId = currentAssignment.orElse(null);

                // 1. Update the metadata store assignment
                return metadataStore.put(
                        ClusterActorSystem.ACTOR_ASSIGNMENT_PREFIX + actorId, targetNodeId)
                        .thenRun(() -> {
                            // 2. Invalidate local TTL cache so next route hits etcd
                            system.invalidateActorAssignmentCache(actorId);

                            // 3. If the actor is running locally, stop it so it can
                            //    persist state and be lazily recovered on the target
                            if (system.getSystemId().equals(sourceNodeId)) {
                                system.shutdown(actorId);
                            }
                        });
            });
}
```

**Behavior contract**:
- Returns failed future if `targetNodeId` is unknown (not in `knownNodes`)
- Updates etcd atomically (single `put`)
- Invalidates TTL cache on this node immediately after etcd write
- If this node is the current source: calls `system.shutdown(actorId)` — for `StatefulActor` this triggers snapshot + stop; for stateless actor it just stops the mailbox
- The actor is lazily re-created on the target node when the next message arrives and routes there

---

### Task 2 — Implement `drainNode` [feat]

**File**: `lib/src/main/java/com/cajunsystems/cluster/DefaultClusterManagementApi.java`

```java
@Override
public CompletableFuture<Void> drainNode(String nodeId) {
    Set<String> remainingNodes = system.getKnownNodes();
    remainingNodes.remove(nodeId);  // getKnownNodes() returns a copy, safe to mutate

    if (remainingNodes.isEmpty()) {
        return CompletableFuture.failedFuture(
                new IllegalStateException(
                        "Cannot drain node '" + nodeId + "': no other nodes available to receive actors"));
    }

    return listActors(nodeId)
            .thenCompose(actorIds -> {
                if (actorIds.isEmpty()) {
                    return CompletableFuture.completedFuture(null);
                }

                List<CompletableFuture<Void>> migrations = actorIds.stream()
                        .map(actorId -> {
                            Optional<String> targetOpt = RendezvousHashing.assignKey(actorId, remainingNodes);
                            if (targetOpt.isEmpty()) {
                                // No target available — log and skip
                                return CompletableFuture.<Void>completedFuture(null);
                            }
                            return migrateActor(actorId, targetOpt.get())
                                    .exceptionally(ex -> {
                                        // Log individual migration failure but continue draining others
                                        org.slf4j.LoggerFactory.getLogger(DefaultClusterManagementApi.class)
                                                .warn("Failed to migrate actor '{}' during drain of node '{}': {}",
                                                        actorId, nodeId, ex.getMessage());
                                        return null;
                                    });
                        })
                        .collect(java.util.stream.Collectors.toList());

                return CompletableFuture.allOf(migrations.toArray(new CompletableFuture[0]));
            });
}
```

**Behavior contract**:
- Fails immediately (before querying actors) if draining would leave zero nodes
- Calls `listActors(nodeId)` to get current etcd snapshot (not TTL cache)
- Uses `RendezvousHashing.assignKey(actorId, remainingNodes)` — consistent with how ClusterActorSystem assigns actors
- Runs all migrations in parallel (`CompletableFuture.allOf`)
- Per-actor migration failures are logged + swallowed (best-effort drain); the overall future completes normally
- Returns when all etcd assignments are updated

---

### Task 3 — Tests: migrate scenarios [test]

**File**: `lib/src/test/java/com/cajunsystems/cluster/ClusterManagementApiMigrateTest.java`

Use same `InMemoryMetadataStore` pattern from 30-1 (copy the stub or move it to a shared test helper class `InMemoryMetadataStore.java` in the test directory).

Tests:

1. `migrateActor_updatesMetadataStore()` — pre-assign actorX to nodeA, migrate to nodeB, verify etcd now has `cajun/actor/actorX = nodeB`

2. `migrateActor_invalidatesUnknownTarget_throwsIllegalArgument()` — target nodeZ not in knownNodes, verify failed future with `IllegalArgumentException`

3. `migrateActor_actorNotCurrentlyAssigned_writesNewAssignment()` — no pre-existing assignment, migrate to nodeA, etcd should have the entry

4. `listActors_afterMigration_reflectsNewAssignment()` — migrate actorX from nodeA to nodeB, then call `listActors("nodeB")`, verify actorX is included

---

### Task 4 — Tests: drain scenarios [test]

**File**: `lib/src/test/java/com/cajunsystems/cluster/ClusterManagementApiDrainTest.java`

Tests:

1. `drainNode_migratesAllActorsToOtherNodes()` — 3 actors on nodeA, drain nodeA; verify all 3 are now assigned to nodeB or nodeC (none left on nodeA)

2. `drainNode_emptyNode_completesSuccessfully()` — no actors on the node, drain should complete normally with no errors

3. `drainNode_singleNodeCluster_failsWithIllegalState()` — only one node; drain should fail immediately (no remaining nodes)

4. `drainNode_usesConsistentHashingForPlacement()` — verify actors are distributed between remaining nodes (not all sent to same node) when cluster has 3+ nodes

5. `drainNode_rejoinAfterDrain()` — simulate drain-and-rejoin cycle:
   - Assign 5 actors to nodeA
   - Drain nodeA (verify actors moved to nodeB, nodeC)
   - "Rejoin" nodeA by registering a new actor on nodeA
   - Verify `listNodes()` still shows nodeA (metadata store still has nodeA entry)
   - Verify the new actor is on nodeA, old actors are on nodeB/nodeC

---

### Task 5 — Shared test helper [refactor]

**New file**: `lib/src/test/java/com/cajunsystems/cluster/InMemoryMetadataStore.java`

Extract the `InMemoryMetadataStore` inner class from `ClusterManagementApiReadTest` into a package-private top-level class to avoid duplication across test files.

```java
package com.cajunsystems.cluster;

// (move InMemoryMetadataStore from inner class to top-level package-private class)
// Exact copy of the implementation from 30-1 test
```

Also update `ClusterManagementApiReadTest` to use the extracted class (remove the inner class definition).

**Important**: Do NOT use `@Tag("requires-etcd")` — these tests use the in-memory stub and must run in CI without any external services.

---

## Verification

```bash
./gradlew :lib:compileJava
./gradlew :lib:compileTestJava
./gradlew test --tests "com.cajunsystems.cluster.ClusterManagementApiMigrateTest"
./gradlew test --tests "com.cajunsystems.cluster.ClusterManagementApiDrainTest"
./gradlew test
```

All new tests green. Full suite green.

---

## Success Criteria

- [ ] `migrateActor` updates etcd assignment + invalidates cache + stops local actor when source=this node
- [ ] `migrateActor` returns failed future for unknown targetNodeId
- [ ] `drainNode` migrates all actors off the node using rendezvous hashing
- [ ] `drainNode` fails fast when no other nodes available
- [ ] `drainNode` continues after per-actor migration failures (best-effort)
- [ ] `InMemoryMetadataStore` extracted to shared test helper
- [ ] 4 migrate tests + 5 drain tests = 9 new tests, all green
- [ ] `./gradlew test` (full suite) green

---

## Output

New files:
- `lib/src/test/java/com/cajunsystems/cluster/InMemoryMetadataStore.java` (extracted from 30-1 test)
- `lib/src/test/java/com/cajunsystems/cluster/ClusterManagementApiMigrateTest.java`
- `lib/src/test/java/com/cajunsystems/cluster/ClusterManagementApiDrainTest.java`

Modified files:
- `lib/src/main/java/com/cajunsystems/cluster/DefaultClusterManagementApi.java` (migrateActor + drainNode implemented)
- `lib/src/test/java/com/cajunsystems/cluster/ClusterManagementApiReadTest.java` (InMemoryMetadataStore inner class removed, use shared)
