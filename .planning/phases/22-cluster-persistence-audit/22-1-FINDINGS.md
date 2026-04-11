# Phase 22 Findings — Cluster & Persistence Audit

## Summary

The cluster and persistence modules provide a solid conceptual foundation (rendezvous hashing,
delivery guarantees, message journaling, snapshot recovery) but have several production-blocking
gaps. The single most severe issue is that `StatefulActor` state is silently discarded when a
cluster node fails and the actor is reassigned — because persistence is node-local and there is
no shared store. Phases 23–31 of Milestone 5 directly address these gaps: serialization (23),
Redis shared persistence design (24–25), cluster + persistence integration (26), observability
(27), and reliability hardening (28).

---

## Critical Issues

### C1: StatefulActor State Lost on Cluster Reassignment
**File**: `lib/src/main/java/com/cajunsystems/cluster/ClusterActorSystem.java:496-513`
**Description**: `assignActorToNode()` writes a metadata key (`cajun/actor/<id> = <nodeId>`)
to indicate which node owns an actor, but it never migrates the actor's journal or snapshot data
to the target node. The persistence layer (`FileMessageJournal`, `FileSnapshotStore`) stores
data in node-local directories. When an actor is reassigned, the new node has no access to the
previous node's journal, so the actor restarts with `initialState` (count = 0 instead of the
last known state).
**Risk**: Silent data loss in production. Any StatefulActor that accumulates state and is
subsequently reassigned (due to rolling deploys, node failures, or rebalancing) will lose all
accumulated state.
**Addressed by**: Phase 26 (Cluster + Shared Persistence Integration — Redis-backed provider)

### C2: No Actor Instantiation on Target Node During Reassignment
**File**: `lib/src/main/java/com/cajunsystems/cluster/ClusterActorSystem.java:496-513`
**Description**: `assignActorToNode()` and `createActorOnNode()` only update the metadata store
key. There is no mechanism to remotely instantiate an actor on the target node. Messages routed
to a reassigned actor will hit the `routeMessage()` path that logs a warning
(`"Actor {} is registered to this node but not found locally"`, line ~275) and drops the
message silently.
**Risk**: After a node failure, reassigned actors become unreachable until manually re-registered
on the new node. This is a correctness violation in production.
**Addressed by**: Phase 26 (actor remote spawn protocol as part of persistence integration)

---

## High Priority Issues

### H1: Java Native Serialization for Inter-Node Messages
**File**: `lib/src/main/java/com/cajunsystems/cluster/ReliableMessagingSystem.java:370-406`  
**File**: `lib/src/main/java/com/cajunsystems/runtime/cluster/DirectMessagingSystem.java:190-235`
**Description**: Both `ReliableMessagingSystem` and `DirectMessagingSystem` use
`ObjectOutputStream` / `ObjectInputStream` to serialize messages for inter-node transport.
`RemoteMessage<T>` wraps the user message as a `Serializable` field. This means every message
type sent across nodes must implement `java.io.Serializable`, and the format is tied to Java's
native serialization (brittle across JVM versions, no forward/backward compatibility guarantees,
known security issues with untrusted input).
**Risk**: Inter-node messages fail with `NotSerializableException` if the user forgets to
implement `Serializable`. Remote exploitation risk if any endpoint receives untrusted data.
Poor cross-language compatibility.
**Addressed by**: Phase 23 (Serialization Framework — pluggable `SerializationProvider`)

### H2: MessageTracker ScheduledExecutorService Not Exposed for Shutdown
**File**: `lib/src/main/java/com/cajunsystems/cluster/MessageTracker.java:31`
**Description**: `MessageTracker` creates an internal `ScheduledExecutorService` (line 31,
`Executors.newScheduledThreadPool(1)`) with a `shutdown()` method defined (line 162). However,
when `MessageTracker` is used inside `ReliableMessagingSystem`, it is only created
once per `ReliableMessagingSystem` instance and its `shutdown()` is called in
`ReliableMessagingSystem.stop()` (line 282). If `ReliableMessagingSystem.stop()` is never
called (e.g., abnormal JVM exit, test leak), the `ScheduledExecutorService` keeps a non-daemon
thread alive and prevents JVM exit.
**Risk**: Thread leak in tests and production if `stop()` is not called. Under normal shutdown
this is handled, but any exception before `stop()` leaves the executor running.
**Addressed by**: Phase 28 (Reliability Hardening — graceful shutdown guarantees)

### H3: EtcdMetadataStore: No Retry Logic for Transient Failures
**File**: `lib/src/main/java/com/cajunsystems/runtime/cluster/EtcdMetadataStore.java:88-135`
**Description**: All etcd operations (`put`, `get`, `delete`, `listKeys`, `acquireLock`) are
single-attempt, delegating directly to the jetcd client with no retry or backoff. Transient
network blips or etcd leader elections cause immediate failures propagated as exceptions to
callers. The heartbeat in `ClusterActorSystem.sendHeartbeat()` (line ~362) logs an error and
continues, but a failed node registration (`put`) means the node does not appear in the cluster.
**Risk**: Single transient failure can cause a node to appear offline or an actor assignment to
fail, leading to dropped messages or false failover in a running cluster.
**Addressed by**: Phase 28 (Reliability Hardening — retry/backoff for metadata operations)

### H4: EtcdMetadataStore Uses a Single Client Instance (No Connection Pooling)
**File**: `lib/src/main/java/com/cajunsystems/runtime/cluster/EtcdMetadataStore.java:33,49`
**Description**: A single `Client` is created in `connect()` and shared across all concurrent
operations. jetcd's `Client` is thread-safe, but operations serialize through shared gRPC
channels without connection pooling. Under high-throughput writes (frequent heartbeats + actor
assignments), this creates a bottleneck.
**Risk**: Performance degradation under load; single point of failure for all cluster metadata
operations.
**Addressed by**: Phase 29 (Performance Optimization — etcd connection pool/tuning)

### H5: PersistenceProviderRegistry Is a JVM-Level Singleton
**File**: `lib/src/main/java/com/cajunsystems/persistence/PersistenceProviderRegistry.java:15-37`
**Description**: `PersistenceProviderRegistry.getInstance()` is a global `static synchronized`
singleton. All `StatefulActor` instances in the JVM share the same provider. Tests that register
custom providers or change the default will affect all other concurrently running tests. There
is no per-actor-system or per-test-scoped registry.
**Risk**: Test isolation failure — one test's provider registration leaks into another. In
production, there is no way to have different persistence strategies for different actor systems
running in the same JVM (e.g., local filesystem for lightweight actors, Redis for critical actors).
**Addressed by**: Phase 25 (Redis Persistence Provider — per-system provider configuration)

---

## Medium Priority Issues

### M1: File-Based Persistence Stores Data in Node-Local Filesystem
**File**: `lib/src/main/java/com/cajunsystems/runtime/persistence/FileMessageJournal.java:36-43`  
**File**: `lib/src/main/java/com/cajunsystems/runtime/persistence/FileSnapshotStore.java:33-40`
**Description**: Both `FileMessageJournal` and `FileSnapshotStore` write to paths on the local
filesystem (by default under a relative directory). In a containerized or cloud environment,
actor state stored here is ephemeral and lost when the container restarts. There is no option
to configure a shared network filesystem path or remote storage backend.
**Risk**: All persisted state is lost when the node/container restarts unless the deployment
mounts a persistent volume at exactly the right path.
**Addressed by**: Phase 24 (Redis Persistence Design) and Phase 25 (Redis Persistence Provider)

### M2: `InMemoryMetadataStore.unwatch()` Does Not Use the Watch ID
**File**: `lib/src/test/java/com/cajunsystems/cluster/ClusterModeTest.java:396-400`  
**File**: `lib/src/test/java/com/cajunsystems/cluster/ClusterLocalActorTest.java:276-278`
**Description**: Both test-local `InMemoryMetadataStore` implementations ignore the `watchId`
parameter in `unwatch()`. The `watchers` map uses the prefix string as key, not the numeric
watch ID. This means unwatch() is a no-op, and tests that register watchers never remove them.
In a long-running test suite, accumulated watchers could fire spuriously.
**Risk**: Test reliability issue; in production code, the `EtcdMetadataStore` correctly tracks
watchers by ID but the mismatch means integration tests do not verify unwatch behaviour.
**Addressed by**: Phase 31 (Testing — test mock improvements)

### M3: Thread.sleep() Used for Timing in Cluster Tests
**File**: `lib/src/test/java/com/cajunsystems/cluster/ClusterModeTest.java:62,123,186,194,215,228`
**Description**: Multiple `Thread.sleep()` calls are used to wait for "actor assignments to
propagate", "leader election", and "reassignment" (sleeps of 1000–3000 ms each). This makes
tests timing-dependent: too short on a slow CI machine causes flaky failures; too long wastes
CI time. There are no explicit assertions on intermediate states (e.g., "actor now registered
in metadata store") before proceeding.
**Risk**: Intermittent CI failures on overloaded machines; slow test suite.
**Addressed by**: Phase 31 (Testing — replace sleep-based waits with assertion-based polling)

### M4: `ClusterActorSystem.shutdown()` Override Does Not Return Future
**File**: `lib/src/main/java/com/cajunsystems/cluster/ClusterActorSystem.java:159-163`
**Description**: `ClusterActorSystem.shutdown()` overrides `ActorSystem.shutdown()` and calls
`stop()` (which returns a `CompletableFuture<Void>`), but discards the future without waiting.
A caller invoking the `void shutdown()` method has no way to know when cleanup is complete or
if it failed. The `CompletableFuture` from `stop()` is silently discarded.
**Risk**: Resources (scheduler, sockets, metadata store connections) may not be fully cleaned up
before the JVM or tests proceed; undetected cleanup failures.
**Addressed by**: Phase 28 (Reliability Hardening — shutdown contract improvement)

---

## Test Coverage Gaps

| Scenario | Currently Tested? | Gap |
|----------|------------------|-----|
| Local actor communication (same node) | ✅ `ClusterLocalActorTest` | — |
| Remote actor communication (cross-node) | ⚠️ `ClusterModeTest` (fragile) | `Thread.sleep` timing; no ordering guarantees verified |
| Node failure → actor reassignment (metadata only) | ⚠️ `ClusterModeTest.testActorReassignmentOnNodeFailure` | No state verification; actor manually re-registered |
| StatefulActor state after cluster reassignment | ❌ Missing | State is silently lost (C1 above); now documented in `StatefulActorClusterStateTest` |
| Split-brain (two leaders simultaneously) | ❌ Missing | `InMemoryMetadataStore.acquireLock` could race |
| Message ordering under concurrent sends | ❌ Missing | No ordering assertion across remote boundaries |
| Exactly-once deduplication correctness | ❌ Missing | `MessageTracker.isMessageProcessed` logic never tested end-to-end |
| MessageTracker cleanup after `stop()` | ❌ Missing | ScheduledExecutorService shutdown not tested in isolation |
| `routeMessage` with actor not local and not in metadata | ❌ Missing | Warning logged but message dropped silently |
| Backpressure under cluster load | ❌ Missing | No cluster + backpressure interaction tests |

---

## Phase Mapping

| Finding | Phase | Description |
|---------|-------|-------------|
| C1: State-loss on reassignment | 26 | Cluster + Shared Persistence Integration (Redis) |
| C2: No actor instantiation on target node | 26 | Remote spawn protocol |
| H1: Java native serialization | 23 | SerializationProvider framework (pluggable codecs) |
| H2: MessageTracker resource leak | 28 | Reliability Hardening — shutdown guarantees |
| H3: EtcdMetadataStore no retry | 28 | Reliability Hardening — retry/backoff |
| H4: EtcdMetadataStore single client | 29 | Performance Optimization |
| H5: PersistenceProviderRegistry singleton | 25 | Redis Persistence Provider — per-system config |
| M1: File-based storage is node-local | 24, 25 | Redis Persistence Design + Provider |
| M2: unwatch() mock is no-op | 31 | Testing — mock improvements |
| M3: Thread.sleep in tests | 31 | Testing — assertion-based polling |
| M4: shutdown() discards future | 28 | Reliability Hardening — shutdown contract |
