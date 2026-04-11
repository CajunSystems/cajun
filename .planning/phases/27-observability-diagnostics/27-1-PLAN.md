# Phase 27 Plan 1 — Observability & Diagnostics

## Objective
Add `ClusterMetrics` and `PersistenceMetrics` APIs, wire them into `ClusterActorSystem` and `ReliableMessagingSystem`, implement `ClusterActorSystem.healthCheck()`, and add MDC structured logging to cluster/persistence paths.

## Execution Context

```
lib/src/main/java/com/cajunsystems/cluster/ClusterActorSystem.java         — add metrics wiring + healthCheck()
cajun-core/src/main/java/com/cajunsystems/cluster/ReliableMessagingSystem.java — add counters + MDC
cajun-core/src/main/java/com/cajunsystems/persistence/redis/RedisMessageJournal.java — add latency recording (new file location TBD)
cajun-persistence/src/main/java/com/cajunsystems/persistence/redis/RedisMessageJournal.java — add latency recording
cajun-persistence/src/main/java/com/cajunsystems/persistence/redis/RedisSnapshotStore.java — add latency recording
lib/src/main/resources/logback.xml                                         — add MDC pattern fields
```

## Key Constraints
- No external metrics library (Micrometer/Prometheus) — follow AtomicLong pattern from `ActorMetrics.java`
- `PersistenceMetrics` must live in `cajun-core` so `cajun-persistence` classes can reference it
- `ClusterMetrics` lives in `lib/src/main/java/com/cajunsystems/metrics/` (consistent with `ActorMetrics`)
- `ReliableMessagingSystem` is in `cajun-core` — MDC imports use `org.slf4j.MDC`
- Logback is the logging implementation (`lib/src/main/resources/logback.xml`)
- All tests: `./gradlew test` must stay green (no new `requires-redis` or `requires-etcd` tags needed for metrics tests — use unit tests with fakes)

## Tasks

### Task 1 — Implement `ClusterMetrics`
**File**: `lib/src/main/java/com/cajunsystems/metrics/ClusterMetrics.java`

Create a new class following the `ActorMetrics` pattern (AtomicLong counters, no external library):

```java
package com.cajunsystems.metrics;

import java.util.concurrent.atomic.AtomicLong;

public class ClusterMetrics {
    private final String nodeId;
    private final AtomicLong localMessagesRouted = new AtomicLong(0);
    private final AtomicLong remoteMessagesRouted = new AtomicLong(0);
    private final AtomicLong remoteMessagesSent = new AtomicLong(0);
    private final AtomicLong remoteMessagesReceived = new AtomicLong(0);
    private final AtomicLong remoteMessageFailures = new AtomicLong(0);
    private final AtomicLong totalRoutingLatencyNs = new AtomicLong(0);
    private final AtomicLong routingLatencyCount = new AtomicLong(0);
    private final AtomicLong nodeJoinCount = new AtomicLong(0);
    private final AtomicLong nodeDepartureCount = new AtomicLong(0);

    public ClusterMetrics(String nodeId) { this.nodeId = nodeId; }

    // increment methods + getters for each field
    // recordRoutingLatency(long nanos) — updates total + count
    // getAverageRoutingLatencyNs() — total / count (0 if count == 0)
    // toString() — human-readable summary
}
```

### Task 2 — Implement `PersistenceMetrics`
**File**: `cajun-core/src/main/java/com/cajunsystems/metrics/PersistenceMetrics.java`

Create in `cajun-core` (not `lib`) so `cajun-persistence` classes can import it:

```java
package com.cajunsystems.metrics;

import java.util.concurrent.atomic.AtomicLong;

public class PersistenceMetrics {
    private final String actorId;
    private final AtomicLong journalAppendCount = new AtomicLong(0);
    private final AtomicLong journalReadCount = new AtomicLong(0);
    private final AtomicLong snapshotSaveCount = new AtomicLong(0);
    private final AtomicLong snapshotLoadCount = new AtomicLong(0);
    private final AtomicLong totalJournalAppendLatencyNs = new AtomicLong(0);
    private final AtomicLong totalJournalReadLatencyNs = new AtomicLong(0);
    private final AtomicLong totalSnapshotSaveLatencyNs = new AtomicLong(0);
    private final AtomicLong totalSnapshotLoadLatencyNs = new AtomicLong(0);
    private final AtomicLong journalAppendErrors = new AtomicLong(0);
    private final AtomicLong snapshotErrors = new AtomicLong(0);

    public PersistenceMetrics(String actorId) { this.actorId = actorId; }

    // increment/record methods for each counter + latency pair
    // getAverageJournalAppendLatencyNs(), getAverageSnapshotSaveLatencyNs()
    // toString() — human-readable summary
}
```

Also add a `PersistenceMetricsRegistry` in `cajun-core/src/main/java/com/cajunsystems/metrics/`:
```java
public class PersistenceMetricsRegistry {
    private static final ConcurrentHashMap<String, PersistenceMetrics> registry = new ConcurrentHashMap<>();
    public static PersistenceMetrics getOrCreate(String actorId) { ... }
    public static Optional<PersistenceMetrics> get(String actorId) { ... }
    public static Map<String, PersistenceMetrics> getAll() { ... }
    public static void unregister(String actorId) { ... }
}
```

### Task 3 — Wire `ClusterMetrics` into `ClusterActorSystem` and `ReliableMessagingSystem`

**`ClusterActorSystem`** (`lib/src/main/java/com/cajunsystems/cluster/ClusterActorSystem.java`):
- Add `private final ClusterMetrics clusterMetrics = new ClusterMetrics(systemId)` field (after `systemId` is assigned — init in constructor or lazily)
- In node watcher callback (lines ~112-125): call `clusterMetrics.incrementNodeJoinCount()` on join, `clusterMetrics.incrementNodeDepartureCount()` on departure
- In routing code (lines ~251-268): record `clusterMetrics.incrementLocalMessagesRouted()` for local delivery, `clusterMetrics.incrementRemoteMessagesRouted()` for remote dispatch; wrap remote send with `long t0 = System.nanoTime()` ... `clusterMetrics.recordRoutingLatency(System.nanoTime() - t0)`
- In routing failures (lines ~260,268): call `clusterMetrics.incrementRemoteMessageFailures()`
- Add `public ClusterMetrics getClusterMetrics()` accessor

**`ReliableMessagingSystem`** (`cajun-core/src/main/java/com/cajunsystems/cluster/ReliableMessagingSystem.java`):
- Add `private final ClusterMetrics clusterMetrics` field; add it to the primary constructor (all constructors delegate to primary already)
- In `doSendMessage()` (line 231): increment `clusterMetrics.incrementRemoteMessagesSent()` on entry; increment `clusterMetrics.incrementRemoteMessageFailures()` in catch
- In `handleClient()` (line 332): increment `clusterMetrics.incrementRemoteMessagesReceived()` after successful deserialization

**Problem**: `ReliableMessagingSystem` is in `cajun-core`, but `ClusterMetrics` is in `lib`. Fix: either move `ClusterMetrics` to `cajun-core/src/main/java/com/cajunsystems/metrics/` OR keep `ReliableMessagingSystem` unaware of `ClusterMetrics` and have `ClusterActorSystem` (in `lib`) instrument via a simple callback.

**Decision**: Move `ClusterMetrics` to `cajun-core/src/main/java/com/cajunsystems/metrics/` — same module as `PersistenceMetrics`. `lib` can still expose both through `MetricsRegistry`.

### Task 4 — Implement `ClusterActorSystem.healthCheck()`

**File**: `lib/src/main/java/com/cajunsystems/cluster/ClusterActorSystem.java`

Add a `ClusterHealthStatus` record in `lib/src/main/java/com/cajunsystems/cluster/ClusterHealthStatus.java`:
```java
package com.cajunsystems.cluster;

public record ClusterHealthStatus(
    boolean healthy,
    String nodeId,
    boolean isLeader,
    int knownNodeCount,
    boolean messagingSystemRunning,
    boolean persistenceHealthy,
    String persistenceProviderName   // null if no provider configured
) {
    public static ClusterHealthStatus healthy(String nodeId, boolean isLeader, int nodeCount,
            boolean persistenceHealthy, String persistenceProviderName) {
        return new ClusterHealthStatus(true, nodeId, isLeader, nodeCount, true,
                persistenceHealthy, persistenceProviderName);
    }
}
```

Add to `ClusterActorSystem`:
```java
public ClusterHealthStatus healthCheck() {
    boolean persistenceHealthy = persistenceProvider == null || persistenceProvider.isHealthy();
    String persistenceProviderName = persistenceProvider == null ? null : persistenceProvider.getProviderName();
    return new ClusterHealthStatus(
        true,
        systemId,
        isLeader.get(),
        knownNodes.size(),
        messagingSystem != null,  // running flag not directly accessible — use null check
        persistenceHealthy,
        persistenceProviderName
    );
}
```

### Task 5 — MDC Structured Logging

**`ReliableMessagingSystem`** (`cajun-core/src/main/java/com/cajunsystems/cluster/ReliableMessagingSystem.java`):
- Add `import org.slf4j.MDC;`
- In `doSendMessage()`: add try-finally MDC block:
  ```java
  MDC.put("targetSystem", targetSystemId);
  MDC.put("actorId", actorId);
  if (messageId != null) MDC.put("messageId", messageId);
  try { ... } finally { MDC.remove("targetSystem"); MDC.remove("actorId"); MDC.remove("messageId"); }
  ```
- In `handleClient()`: after deserializing `remoteMessage`, set MDC:
  ```java
  MDC.put("sourceSystem", remoteMessage.sourceSystemId);
  MDC.put("actorId", remoteMessage.actorId);
  if (remoteMessage.messageId != null) MDC.put("messageId", remoteMessage.messageId);
  ```
  Wrap entire body in try-finally to clear MDC on exit.

**`RedisMessageJournal`** (`cajun-persistence/src/main/java/com/cajunsystems/persistence/redis/RedisMessageJournal.java`):
- In `append()`: `MDC.put("actorId", actorId); try { ... } finally { MDC.remove("actorId"); }`

**`logback.xml`** (`lib/src/main/resources/logback.xml`):
- Update pattern to include MDC fields:
  ```
  %d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} [actorId=%X{actorId}] [msgId=%X{messageId}] - %msg%n
  ```
  Using `%X{key}` — outputs empty string when MDC key absent (no extra noise when not in cluster paths).

### Task 6 — Tests

**`lib/src/test/java/com/cajunsystems/metrics/ClusterMetricsTest.java`**:
- Test increment methods count correctly
- Test `recordRoutingLatency` accumulates and average is correct
- Test `toString()` contains key fields

**`lib/src/test/java/com/cajunsystems/metrics/PersistenceMetricsTest.java`**:
- Test journal append/read counts and latency averages
- Test snapshot save/load counts and latency averages
- Test `PersistenceMetricsRegistry.getOrCreate()` returns same instance for same actorId

**`lib/src/test/java/com/cajunsystems/cluster/ClusterHealthStatusTest.java`**:
- Test `healthCheck()` returns correct `nodeId`, `isLeader`, `knownNodeCount`, `persistenceHealthy`
- Test with null persistence provider: `persistenceHealthy=true`, `persistenceProviderName=null`
- Test with unhealthy provider: `persistenceHealthy=false`
- Use `InMemoryMetadataStore` + mock `MessagingSystem` (no etcd/Redis needed)

## Checkpoint: Verify after Task 6
```bash
./gradlew test
```
Expected: all non-`requires-redis`/non-`requires-etcd`/non-`performance` tests pass. BUILD SUCCESSFUL.

## Success Criteria
- [ ] `ClusterMetrics` implemented in `cajun-core/src/main/java/com/cajunsystems/metrics/`
- [ ] `PersistenceMetrics` + `PersistenceMetricsRegistry` implemented in `cajun-core/src/main/java/com/cajunsystems/metrics/`
- [ ] `ClusterMetrics` wired into `ClusterActorSystem` (routing counters, node join/depart counters, latency)
- [ ] `ClusterMetrics` wired into `ReliableMessagingSystem` (send/receive/failure counters)
- [ ] `ClusterActorSystem.healthCheck()` returns `ClusterHealthStatus` record
- [ ] MDC fields set/cleared in `ReliableMessagingSystem.doSendMessage()` and `handleClient()`
- [ ] `logback.xml` pattern includes `%X{actorId}` and `%X{messageId}`
- [ ] Unit tests for `ClusterMetrics`, `PersistenceMetrics`, and `ClusterHealthStatus`
- [ ] `./gradlew test` green

## Output
- New files:
  - `cajun-core/src/main/java/com/cajunsystems/metrics/ClusterMetrics.java`
  - `cajun-core/src/main/java/com/cajunsystems/metrics/PersistenceMetrics.java`
  - `cajun-core/src/main/java/com/cajunsystems/metrics/PersistenceMetricsRegistry.java`
  - `lib/src/main/java/com/cajunsystems/cluster/ClusterHealthStatus.java`
  - `lib/src/test/java/com/cajunsystems/metrics/ClusterMetricsTest.java`
  - `lib/src/test/java/com/cajunsystems/metrics/PersistenceMetricsTest.java`
  - `lib/src/test/java/com/cajunsystems/cluster/ClusterHealthStatusTest.java`
- Modified files:
  - `cajun-core/src/main/java/com/cajunsystems/cluster/ReliableMessagingSystem.java` (MDC + counters)
  - `lib/src/main/java/com/cajunsystems/cluster/ClusterActorSystem.java` (ClusterMetrics wiring + healthCheck)
  - `lib/src/main/resources/logback.xml` (MDC pattern)
