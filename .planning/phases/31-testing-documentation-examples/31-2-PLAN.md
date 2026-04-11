---
plan: 31-2
phase: 31 — Testing, Documentation & Examples
title: Documentation + Runnable Example + Redis State Recovery Test
type: docs+feat+test
status: complete
---

## Objective

Three deliverables:
1. **Documentation update**: rewrite `docs/cluster_mode.md` to cover Phases 27-30 additions; create new `docs/cluster-deployment.md` and `docs/cluster-serialization.md`
2. **Runnable cluster example**: `ClusterStatefulRecoveryExample.java` — demonstrates full cluster lifecycle with Redis persistence and node failure recovery
3. **Extended state recovery test**: 100-message scenario in `StatefulActorClusterStateTest` (`@Tag("requires-redis")`)

---

## Execution Context

Read these before writing:

- `/Users/pradeep.samuel/cajun/docs/cluster_mode.md` — current content to understand what's there and what's missing
- `/Users/pradeep.samuel/cajun/docs/persistence_guide.md` — context for persistence section
- `/Users/pradeep.samuel/cajun/lib/src/test/java/com/cajunsystems/cluster/StatefulActorClusterStateTest.java` — existing state recovery test to extend (lines 400–end for the Redis test)
- `/Users/pradeup.samuel/cajun/lib/src/main/java/com/cajunsystems/cluster/ClusterActorSystem.java` — `ClusterConfiguration` builder usage, `getManagementApi()`
- `/Users/pradeep.samuel/cajun/lib/src/main/java/com/cajunsystems/cluster/ClusterManagementApi.java` — interface for docs
- `/Users/pradeep.samuel/cajun/lib/src/main/java/com/cajunsystems/metrics/ClusterMetrics.java` — for observability section
- `/Users/pradeep.samuel/cajun/lib/src/main/java/com/cajunsystems/serialization/JsonSerializationProvider.java` — for serialization migration guide

---

## Tasks

### Task 1 — Rewrite `docs/cluster_mode.md` [docs]

The current `docs/cluster_mode.md` is 140 lines and was written before Phases 22-30. It doesn't mention: ClusterConfiguration builder, ClusterManagementApi, ClusterMetrics, NodeCircuitBreaker, TTL cache, Redis persistence, SerializationProvider.

Rewrite the document (replace in place — keep the filename) to cover:

**Structure:**
```markdown
# Cajun Cluster Mode

## Quick Start
[ClusterConfiguration.builder() example — 10 lines]

## Architecture
[Brief: MetadataStore, MessagingSystem, RendezvousHashing, leader election]

## Configuration
[ClusterConfiguration builder fields + defaults]

## Serialization
[KryoSerializationProvider (default), JsonSerializationProvider, custom package trust]

## Persistence (Cross-Node State)
[withPersistenceProvider(redis), why it matters, link to persistence_guide.md]

## Observability
[ClusterMetrics API, PersistenceMetrics, healthCheck(), structured logging via MDC]

## Reliability
[NodeCircuitBreaker (per-node, configurable), ExponentialBackoff on etcd ops, graceful degradation]

## Performance
[TTL cache (60s default), watcher-driven invalidation, batch registration, connection keepAlive]

## Cluster Management API
[ClusterManagementApi: listNodes, listActors, migrateActor, drainNode; code example]

## Production Checklist
[bullet list: etcd HA, Redis persistence, health checks, monitoring]
```

Keep the document focused and concise (~300 lines). Each section should have at least one code example.

Commit: `docs(31-2): rewrite cluster_mode.md with Phase 27-30 enhancements`

---

### Task 2 — Create `docs/cluster-deployment.md` [docs]

New file: production deployment guide.

**Structure:**
```markdown
# Cluster Deployment Guide

## Prerequisites
- Java 21+ (--enable-preview)
- etcd 3.5+ cluster (3 or 5 nodes for HA)
- Redis 7+ (RDB + AOF for durability)

## 1. etcd Setup
[docker-compose snippet for 3-node etcd cluster; connection string format]

## 2. Redis Setup
[docker-compose snippet; connection string; RDB + AOF configuration note]

## 3. Cluster Configuration
[Full ClusterConfiguration.builder() example with all options;
EtcdMetadataStore constructor; ReliableMessagingSystem constructor]

## 4. Persistence Configuration
[RedisPersistenceProvider setup; withPersistenceProvider() wiring]

## 5. Health Monitoring
[healthCheck() API; ClusterMetrics.getSnapshot(); integration with your monitoring stack]

## 6. Operations
[listNodes(), listActors(), drainNode() for rolling upgrades;
migrateActor() for manual rebalancing;
graceful shutdown pattern: drain → stop]

## 7. Tuning
[TTL cache size/duration; circuit breaker thresholds; ReliableMessagingSystem thread pool;
etcd connection pool size; Redis connection pool]

## 8. Troubleshooting
[State loss → check Redis persistence configured;
Message loss → check delivery guarantee;
Slow routing → check TTL cache hit rate via ClusterMetrics.getCacheHits();
Node not discovered → check etcd heartbeat interval]
```

~200 lines. Focus on actionable, copy-paste-friendly content.

Commit: `docs(31-2): create cluster-deployment.md production deployment guide`

---

### Task 3 — Create `docs/cluster-serialization.md` [docs]

New file: guide for choosing and migrating serialization providers.

**Structure:**
```markdown
# Cluster Serialization Guide

## Overview
[Three providers: Java (legacy), Kryo (default for new actors), JSON (debugging/cross-language)]

## Choosing a Provider
[Decision table: performance vs. debuggability vs. schema requirements]

## KryoSerializationProvider (recommended)
[No-schema, high performance; message types need no Serializable;
register classes for maximum performance; code example]

## JsonSerializationProvider
[Human-readable; requires trusted package prefixes;
default INSTANCE vs. custom instance;
when to use (debugging, cross-language interop)]

## Migrating from Java Serialization
[Step 1: identify actors using native serialization;
Step 2: add KryoSerializationProvider to RMS constructor;
Step 3: handle existing journals (bump actor ID or truncate journal);
Step 4: remove implements Serializable from message types]

## Security Note
[Why JsonSerializationProvider restricts polymorphic deserialization;
how to add trusted packages;
never use DefaultTyping.EVERYTHING]
```

~150 lines.

Commit: `docs(31-2): create cluster-serialization.md migration guide`

---

### Task 4 — Extend state recovery test with 100-message scenario [test]

**File**: `lib/src/test/java/com/cajunsystems/cluster/StatefulActorClusterStateTest.java`

Add a new test method after `testStatefulActorStateRecoveredViaRedis()`:

```java
@Test
@Tag("requires-redis")
void testStatefulActorFullLifecycle_100Messages() throws Exception {
    // 1. Setup: two systems sharing Redis persistence and InMemoryMessagingSystem
    // 2. Register CounterActor on system1 (uses Redis journal + snapshots)
    // 3. Send 100 increment messages to the actor via system1
    // 4. Verify count == 100 (using ask-pattern reply)
    // 5. Simulate node1 failure: system1.stop()
    // 6. Register same actor ID on system2 (recovery from Redis)
    // 7. Send 1 more increment
    // 8. Verify count == 101 (full state recovered from Redis)
}
```

Use the existing `CounterActor`, `ReplyCollector`, `InMemoryMessagingSystem` already in `StatefulActorClusterStateTest`. Follow the same pattern as `testStatefulActorStateRecoveredViaRedis()` but with 100 pre-failure messages instead of 5.

Expected behavior: Redis journal replays all 100 messages, snapshot may have been taken (adaptive snapshotting). Final count after recovery = 101.

Commit: `test(31-2): add 100-message state recovery test to StatefulActorClusterStateTest`

---

### Task 5 — Runnable cluster example [feat]

**New file**: `lib/src/test/java/examples/ClusterStatefulRecoveryExample.java`

A self-contained JUnit test class (like other examples) that demonstrates the full cluster story:

```java
/**
 * Demonstrates a complete cluster lifecycle:
 * 1. Two-node cluster setup with Redis persistence
 * 2. Stateful actor accumulates state on node 1
 * 3. Node 1 gracefully drained via ClusterManagementApi
 * 4. Node 1 stopped
 * 5. Same actor recovered on node 2 with full state
 *
 * Prerequisites: Redis running on localhost:6379
 * Run: ./gradlew :lib:test --tests "examples.ClusterStatefulRecoveryExample"
 *      (requires Redis — excluded from default ./gradlew test)
 */
@Tag("requires-redis")
class ClusterStatefulRecoveryExample {
    @Test
    void clusterStatefulRecoveryDemo() throws Exception { ... }
}
```

The example should:
1. Create two `ClusterActorSystem` instances using `ClusterConfiguration.builder()`
2. Use `WatchableInMemoryMetadataStore` + `InMemoryMessagingSystem` for in-JVM cluster communication
3. Use `RedisPersistenceProvider` for state persistence (`withPersistenceProvider(...)`)
4. Register a `CounterActor` (stateful) on system1
5. Send 20 messages, verify count=20
6. `api.drainNode("system1")` — migrate actor to system2
7. `system1.stop()`
8. Send 5 more messages to the actor (now on system2)
9. Verify final count=25 (state recovered from Redis, continued on system2)

Use the `CounterActor` and `ReplyCollector` from `StatefulActorClusterStateTest` (they're package-private in `com.cajunsystems.cluster` — the examples package can't access them directly). **Solution**: define a simple `CounterActor` and `ReplyCollector` inline in the example class as private static inner classes (not reusing the test helper).

Commit: `feat(31-2): add ClusterStatefulRecoveryExample runnable demo`

---

## Verification

```bash
./gradlew :lib:compileTestJava
# Docs have no compilation step — verify manually
./gradlew test  # should not run requires-redis tests
# With Redis running:
# ./gradlew :lib:test --tests "com.cajunsystems.cluster.StatefulActorClusterStateTest"
# ./gradlew :lib:test --tests "examples.ClusterStatefulRecoveryExample"
```

---

## Success Criteria

- [ ] `docs/cluster_mode.md` rewritten — covers ClusterConfiguration, ClusterManagementApi, metrics, reliability, performance
- [ ] `docs/cluster-deployment.md` created — production setup guide with etcd + Redis + ClusterConfiguration
- [ ] `docs/cluster-serialization.md` created — Kryo/JSON guide + migration from Java serialization
- [ ] 100-message state recovery test added to `StatefulActorClusterStateTest` (tagged `@Tag("requires-redis")`)
- [ ] `ClusterStatefulRecoveryExample.java` created — demonstrates full drain + recovery lifecycle
- [ ] `./gradlew test` (full suite, no external services) green

---

## Output

New files:
- `docs/cluster-deployment.md`
- `docs/cluster-serialization.md`
- `lib/src/test/java/examples/ClusterStatefulRecoveryExample.java`

Modified files:
- `docs/cluster_mode.md` (rewritten)
- `lib/src/test/java/com/cajunsystems/cluster/StatefulActorClusterStateTest.java` (new test added)
