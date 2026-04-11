# Phase 22 — Cluster & Persistence Audit

## Objective

Produce a written findings document (`22-1-FINDINGS.md`) that catalogues design gaps, risks, and test-coverage blind spots across the cluster module and persistence system. Also write one failing test that demonstrates the state-loss-on-reassignment bug so the problem is regression-proof for Phases 26 onward.

---

## Execution Context

- Branch: `feature/roux-effect-integration`
- Java 21, `--enable-preview` (configured in Gradle)
- Run tests: `./gradlew test`
- Cluster source: `lib/src/main/java/com/cajunsystems/cluster/`
- Cluster runtime: `lib/src/main/java/com/cajunsystems/runtime/cluster/`
- Persistence source: `lib/src/main/java/com/cajunsystems/persistence/`
- Persistence runtime: `lib/src/main/java/com/cajunsystems/runtime/persistence/`
- StatefulActor: `lib/src/main/java/com/cajunsystems/StatefulActor.java`
- Existing cluster tests: `lib/src/test/java/com/cajunsystems/cluster/`
- Existing persistence tests: search for `*Persistence*` and `*Stateful*` under `lib/src/test/java/`

---

## Context — What We Already Know

From prior research, these facts are confirmed:

**State-loss-on-reassignment bug** (the most critical gap):
- `ClusterActorSystem.assignActorToNode()` only writes a metadata key (`cajun/actor/{id} → nodeId`)
- When the actor is "reassigned" to a new node, **no actor instance is created on the target node** — the new node never gets a `register()` call with the actor's class and prior state
- `StatefulActor` persists state via `FileMessageJournal` / `FileSnapshotStore` (local filesystem by default)
- Local filesystem journals are **node-local** — not accessible to other nodes after reassignment
- Result: after reassignment, messages to the actor produce `"Actor {id} not found"` warnings on the new node; state is permanently lost

**Serialization risk**:
- `ReliableMessagingSystem` uses `java.io.ObjectOutputStream` / `ObjectInputStream` for inter-node messages
- Message types must `implements Serializable` or serialization fails at runtime with `NotSerializableException`
- No validation at actor-creation time; failure is deferred to first cross-node send

**Cluster test quality**:
- `ClusterModeTest` uses `Thread.sleep(3000)` for "wait for propagation" — timing-dependent and fragile
- `testRemoteActorCommunication` noted in STATE.md as intermittent (requires etcd in prod mode)
- No test for: split-brain, message ordering under reorder, exactly-once vs at-least-once semantics
- `ClusterLocalActorTest` only covers single-node happy path

**MessageTracker resource leak**:
- `MessageTracker` creates a `ScheduledExecutorService` in its constructor
- No `shutdown()` method exposed — the scheduler is never shut down when `ReliableMessagingSystem` stops
- `ReliableMessagingSystem.stop()` needs investigation

**EtcdMetadataStore**:
- No retry logic for transient etcd failures
- No connection pooling (`Client` is a single instance)
- `ScheduledThreadPoolExecutor` for keep-alive — needs shutdown verification

---

## Tasks

### Task 1 — Full Read: Cluster Source Files
Read each file in full (not just the sections already sampled). Record concrete line numbers for each finding.

Files to read completely:
- `lib/src/main/java/com/cajunsystems/cluster/ClusterActorSystem.java` (full)
- `lib/src/main/java/com/cajunsystems/cluster/ReliableMessagingSystem.java` (full)
- `lib/src/main/java/com/cajunsystems/cluster/MessageTracker.java` (full)
- `lib/src/main/java/com/cajunsystems/cluster/MessagingSystem.java`
- `lib/src/main/java/com/cajunsystems/cluster/MetadataStore.java`
- `lib/src/main/java/com/cajunsystems/cluster/DeliveryGuarantee.java`
- `lib/src/main/java/com/cajunsystems/runtime/cluster/EtcdMetadataStore.java` (full)
- `lib/src/main/java/com/cajunsystems/runtime/cluster/DirectMessagingSystem.java`
- `lib/src/main/java/com/cajunsystems/runtime/cluster/ClusterFactory.java`

For each file, extract: design gaps, missing error handling, resource leaks, missing thread-safety, missing shutdown paths.

### Task 2 — Full Read: Persistence Source Files
Read each file in full. Focus on how `StatefulActor` interacts with persistence, and how the persistence provider is scoped (global singleton registry — problematic for cluster where state must be shared).

Files to read completely:
- `lib/src/main/java/com/cajunsystems/StatefulActor.java` (full — large file, ~1200 LOC)
- `lib/src/main/java/com/cajunsystems/persistence/PersistenceProvider.java` (already read)
- `lib/src/main/java/com/cajunsystems/persistence/PersistenceProviderRegistry.java` (already read)
- `lib/src/main/java/com/cajunsystems/persistence/MessageJournal.java`
- `lib/src/main/java/com/cajunsystems/persistence/SnapshotStore.java`
- `lib/src/main/java/com/cajunsystems/persistence/PidRehydrator.java`
- `lib/src/main/java/com/cajunsystems/persistence/BatchedMessageJournal.java`
- `lib/src/main/java/com/cajunsystems/runtime/persistence/FileMessageJournal.java`
- `lib/src/main/java/com/cajunsystems/runtime/persistence/FileSnapshotStore.java`
- `lib/src/main/java/com/cajunsystems/runtime/persistence/PersistenceFactory.java`

For each file, extract: where `Serializable` is required, how recovery works, whether the storage path is node-local, whether cross-node access is possible.

### Task 3 — Full Read: Existing Cluster Tests
Read each cluster test file in full and note what is tested vs what is missing.

Files to read:
- `lib/src/test/java/com/cajunsystems/cluster/ClusterModeTest.java` (full)
- `lib/src/test/java/com/cajunsystems/cluster/ClusterLocalActorTest.java` (full)
- `lib/src/test/java/com/cajunsystems/ClusterPerformanceTest.java` (full)

Identify missing test scenarios:
- Node failure → actor reassignment → message delivery
- `StatefulActor` state after reassignment (the bug)
- Split-brain: two leaders simultaneously reassign the same actor
- Message ordering guarantees under concurrent sends
- Exactly-once vs at-least-once deduplication correctness
- `MessageTracker` cleanup after `ReliableMessagingSystem.stop()`

### Task 4 — Write Failing Test: State-Loss-on-Reassignment
Write a single JUnit 5 test that proves the state-loss bug. The test MUST fail on the current codebase and represent the correct desired behavior.

**Test location**: `lib/src/test/java/com/cajunsystems/cluster/StatefulActorClusterStateTest.java`

**Test scenario**:
1. Create a `ClusterActorSystem` with an `InMemoryMetadataStore` and `InMemoryMessagingSystem`
2. Register a `StatefulActor` (counter actor) on `system1`
3. Send 5 `Increment` messages — verify count = 5 via ask-pattern
4. Stop `system1` (simulate node failure)
5. Create `system2` with the same `InMemoryMetadataStore`
6. Wait for leader election and actor reassignment to `system2`
7. Send 1 more `Increment` message via `system2.routeMessage()`
8. Assert: count == 6 (full state recovery)
9. The test will FAIL because `system2` creates a fresh actor with count = 0, so count = 1

**Test class structure**:
```java
// Counter actor message types
sealed interface CounterMessage permits CounterMessage.Increment, CounterMessage.GetCount {
    record Increment() implements CounterMessage, Serializable {}
    record GetCount(Pid replyTo) implements CounterMessage, Serializable {}
}
// Counter state
record CounterState(int count) implements Serializable {}
// CounterActor extends StatefulActor<CounterState, CounterMessage>
```

Use `InMemoryMetadataStore` and `InMemoryMessagingSystem` (already in test classpath from `ClusterModeTest`).
Tag with `@Disabled("Expected to fail — demonstrates state-loss-on-reassignment bug fixed in Phase 26")` so the suite stays green, but leave a comment explaining it's intentionally failing pre-fix.

Actually — do NOT use `@Disabled`. Instead, annotate with `@Tag("cluster-audit")` and a comment explaining it will fail until Phase 26. Run via: `./gradlew test --tests "*StatefulActorClusterStateTest*"` to confirm it fails. Then add a note in the findings document.

Wait — re-read the plan description: "demonstrate the state-loss-on-reassignment bug with a failing test". The test should be a documented failing case. Use `@Disabled` with a descriptive message so the CI suite stays green. The important thing is the test EXISTS and documents the expected behavior.

**Corrected approach**: Use `@Disabled("BUG: StatefulActor state is not recovered after cluster reassignment — fix in Phase 26")`.

### Task 5 — Write Findings Document

Write `.planning/phases/22-cluster-persistence-audit/22-1-FINDINGS.md`.

Structure:
```markdown
# Phase 22 Findings — Cluster & Persistence Audit

## Summary
[2-3 sentence overview]

## Critical Issues
[Line-numbered findings, severity, impact on Phases 23–31]

## High Priority Issues
[...]

## Medium Priority Issues
[...]

## Test Coverage Gaps
[Table: what IS tested vs what is MISSING]

## Prioritised Work for Phases 23–31
[Map each finding to the phase that addresses it]
```

Severity definitions:
- **Critical**: Data loss, correctness violation, production outage risk
- **High**: Resource leak, intermittent failure, incorrect behavior under load
- **Medium**: Missing feature, poor ergonomics, missing observability

---

## Verification

After completing all tasks:

1. **Build passes**: `./gradlew build` — no compile errors in the new test file
2. **Failing test confirmed**: `./gradlew test --tests "*StatefulActorClusterStateTest*"` — test runs and fails (or is `@Disabled` and shows as skipped)
3. **Findings document exists**: `.planning/phases/22-cluster-persistence-audit/22-1-FINDINGS.md` is non-empty
4. **All existing tests pass**: `./gradlew test` — no regressions from the new test file

---

## Success Criteria

- `22-1-FINDINGS.md` created with findings across all 4 sub-tasks from the roadmap (22.1–22.4)
- Minimum 8 distinct findings documented with file + line number references
- State-loss-on-reassignment bug documented with a failing/skipped test that proves the regression
- Each finding mapped to the phase(s) in Milestone 5 that address it
- Zero new test failures introduced into the existing suite

---

## Output

- **New file**: `.planning/phases/22-cluster-persistence-audit/22-1-FINDINGS.md`
- **New file**: `lib/src/test/java/com/cajunsystems/cluster/StatefulActorClusterStateTest.java` (disabled failing test)
- **STATE.md update**: Phase 22 → ✅ Complete
