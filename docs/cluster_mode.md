# Cajun Cluster Mode

Cajun's cluster module distributes actors across multiple JVM processes (nodes) using a shared
metadata store for placement and a pluggable messaging layer for inter-node communication.
This document covers the features added in Phases 22–30.

---

## Quick Start

```java
import com.cajunsystems.cluster.*;
import com.cajunsystems.persistence.redis.RedisPersistenceProvider;
import com.cajunsystems.serialization.KryoSerializationProvider;
import com.cajunsystems.runtime.cluster.ClusterFactory;

// 1. Infrastructure
MetadataStore etcd = ClusterFactory.createEtcdMetadataStore("http://localhost:2379");
MessagingSystem rms  = ClusterFactory.createDirectMessagingSystem("node-1", 8080);

// 2. Shared persistence (all nodes point at the same Redis)
RedisPersistenceProvider redis = new RedisPersistenceProvider(
    "redis://localhost:6379", "myapp", KryoSerializationProvider.INSTANCE);

// 3. Build the system
ClusterActorSystem system = ClusterConfiguration.builder()
    .systemId("node-1")
    .metadataStore(etcd)
    .messagingSystem(rms)
    .deliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE)
    .persistenceProvider(redis)
    .build();

system.start().get();

// 4. Register actors and use them exactly as in single-node mode
Pid counter = system.register(CounterActor.class, "counter-1");
counter.tell(new Increment());

system.stop().get();
```

---

## Architecture

### Components

| Component | Role |
|-----------|------|
| `MetadataStore` | Distributed KV store: actor assignments, leader election, node heartbeats |
| `MessagingSystem` | Network transport between nodes; pluggable (TCP, gRPC, etc.) |
| `ClusterActorSystem` | Extends `ActorSystem`; routes `tell()` transparently to local or remote actors |
| Rendezvous hashing | Deterministic actor-to-node mapping; minimal reshuffling on topology change |
| Leader election | One node owns reassignment logic; based on a distributed lock in `MetadataStore` |

### Actor Placement Flow

1. Actor registered on any node calls `ClusterActorSystem.register(...)`.
2. Rendezvous hashing picks the owning node; result written to `MetadataStore`.
3. All nodes watch `cajun/actor/<id>` keys; local TTL cache updated on watch events.
4. `tell()` checks TTL cache first; only falls back to `MetadataStore` on a cache miss.
5. If the owning node is this node → direct local delivery; otherwise → `MessagingSystem.sendMessage()`.

### Leader Election

The leader acquires a distributed lock (`cajun/leader`) in `MetadataStore`. When a node
heartbeat disappears, the leader reassigns the departed node's actors using rendezvous hashing
(excluding the failed node). Leadership is refreshed on every heartbeat interval.

---

## Configuration

`ClusterConfiguration.builder()` is the recommended entry point:

```java
ClusterActorSystem system = ClusterConfiguration.builder()
    .systemId("node-1")                          // default: random UUID
    .metadataStore(etcd)                         // required
    .messagingSystem(rms)                        // required
    .deliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE) // default: EXACTLY_ONCE
    .persistenceProvider(redis)                  // optional; enables cross-node state recovery
    .build();
```

### Delivery Guarantees

| Level | Behaviour | Use Case |
|-------|-----------|----------|
| `EXACTLY_ONCE` | ACK + retry + deduplication | Financial transactions, idempotency required |
| `AT_LEAST_ONCE` | ACK + retry, no dedup | General stateful actors, Redis journals deduplicate |
| `AT_MOST_ONCE` | Fire-and-forget | Metrics, logs, notifications |

---

## Serialization

All cross-node messages pass through a `SerializationProvider`. The provider is configured on
`ReliableMessagingSystem` and `RedisPersistenceProvider`.

### Kryo (recommended)

```java
import com.cajunsystems.serialization.KryoSerializationProvider;

RedisPersistenceProvider redis = new RedisPersistenceProvider(
    "redis://localhost:6379", "myapp", KryoSerializationProvider.INSTANCE);
```

- No schema required — works with any class.
- Highest throughput; compact binary format.
- Message types do **not** need to implement `Serializable`.

### JSON (debugging / cross-language)

```java
import com.cajunsystems.serialization.JsonSerializationProvider;

// Default INSTANCE trusts com.cajunsystems.*, java.*, javax.*
RedisPersistenceProvider redis = new RedisPersistenceProvider(
    uri, prefix, JsonSerializationProvider.INSTANCE);

// Add custom package trust
JsonSerializationProvider custom = new JsonSerializationProvider("com.example.");
```

- Human-readable; useful for debugging Redis journals.
- Restricts polymorphic deserialization to trusted package prefixes (security hardening).
- Slower than Kryo; not recommended for high-throughput production paths.

For a full migration guide see [cluster-serialization.md](cluster-serialization.md).

---

## Persistence (Cross-Node State)

Without shared persistence, `StatefulActor` state is node-local and lost on reassignment.
Configuring a shared `PersistenceProvider` makes recovery transparent:

```java
RedisPersistenceProvider redis = new RedisPersistenceProvider(
    "redis://localhost:6379", "myapp", KryoSerializationProvider.INSTANCE);

// Option A: system-wide (all stateful actors use Redis by default)
ClusterActorSystem system = ClusterConfiguration.builder()
    .persistenceProvider(redis)
    ...
    .build();

// Option B: per-actor (inject journal/snapshot explicitly)
BatchedMessageJournal<MyMsg> journal = redis.createBatchedMessageJournal("actor-1");
SnapshotStore<MyState>   snapshots = redis.createSnapshotStore("actor-1");

MyStatefulActor actor = new MyStatefulActor(system, "actor-1", initialState, journal, snapshots);
actor.start();
system.registerActor(actor);
```

When the actor is reassigned to a new node, `StatefulActor.initializeState()` replays the
Redis journal automatically, recovering full state before accepting new messages.

For backend comparison and performance data see [persistence_guide.md](persistence_guide.md).

---

## Observability

### ClusterMetrics

```java
ClusterMetrics metrics = system.getClusterMetrics();

long localRouted   = metrics.getLocalMessagesRouted();
long remoteRouted  = metrics.getRemoteMessagesRouted();
long cacheHits     = metrics.getCacheHits();
long cacheMisses   = metrics.getCacheMisses();
double avgLatency  = metrics.getAverageRoutingLatencyNs();
long nodeJoins     = metrics.getNodeJoinCount();
long nodeDepartures = metrics.getNodeDepartureCount();
```

Use `cacheHits / (cacheHits + cacheMisses)` as a proxy for routing efficiency. A low hit rate
suggests actors are being reassigned frequently or the TTL is too short.

### Health Check

```java
ClusterHealthStatus health = system.healthCheck().get();

boolean isHealthy      = health.healthy();
boolean isLeader       = health.isLeader();
boolean persistOk      = health.persistenceHealthy();
boolean messagingOk    = health.messagingSystemRunning();
int     knownNodeCount = health.knownNodeCount();
```

Integrate `healthCheck()` with your monitoring stack (Kubernetes liveness probe, Prometheus
exporter, etc.) to surface cluster state in dashboards.

### Structured Logging (MDC)

All cluster code injects actor IDs and message IDs via SLF4J MDC:

```
[actor-id: counter-1][msg-id: 3f2a...] INFO  Routing message to node node-2
```

Configure your `logback.xml` to include `%X{actorId}` and `%X{messageId}` in the pattern for
end-to-end message tracing.

---

## Reliability

### Node Circuit Breaker

`NodeCircuitBreaker` tracks per-node send failures. Once the failure threshold is reached, all
messages to that node are rejected immediately (fast-fail) until the reset timeout elapses:

```
CLOSED → (5 failures) → OPEN → (30s timeout) → HALF-OPEN → (probe succeeds) → CLOSED
```

Defaults: `failureThreshold = 5`, `resetTimeoutMs = 30_000`. Configurable per node.

### Exponential Backoff on etcd Operations

Idempotent `MetadataStore` operations (`put`, `get`, `delete`, `listKeys`) automatically retry
with exponential backoff and jitter on transient failures. `acquireLock` is excluded to avoid
double-acquisition.

### Graceful Degradation

If etcd becomes unreachable, `ClusterActorSystem` falls back to its TTL cache for routing.
- Cache hit → `WARN` log; message delivered using last-known assignment.
- Cache miss → `ERROR` log; message dropped with an exception.

This keeps the cluster operational during brief metadata store outages at the cost of
potentially stale routing.

---

## Performance

### TTL Cache

Actor-to-node assignments are cached locally with a 60-second TTL. Watch events from
`MetadataStore` invalidate cache entries immediately on reassignment — no stale routing
during rebalancing.

The cache is transparent: `tell()` is identical whether the actor is cached or not.

### Batch Registration

On node startup, all locally registered actors are written to `MetadataStore` in a single
parallel batch (`CompletableFuture.allOf()` on individual puts). This avoids a waterfall of
sequential etcd writes at startup.

### Connection Keep-Alive

gRPC-based messaging systems use keep-alive (`keepAliveTime=5s`, `keepAliveTimeout=3s`,
`keepAliveWithoutCalls=true`) to reduce reconnect latency for bursty inter-node traffic.

---

## Cluster Management API

`ClusterManagementApi` provides programmatic control over cluster topology:

```java
ClusterManagementApi api = system.getManagementApi();
```

### List Nodes and Actors

```java
Set<String> nodes  = api.listNodes().get();
Set<String> actors = api.listActors("node-2").get();
System.out.println("Nodes: " + nodes);
System.out.println("Actors on node-2: " + actors);
```

### Migrate an Actor

```java
// Move actor to a specific node (e.g., for manual rebalancing)
api.migrateActor("counter-1", "node-3").get();
```

The actor is stopped on its current node; the next `tell()` triggers lazy recovery on `node-3`
via the shared `PersistenceProvider`.

### Drain a Node (Rolling Upgrade)

```java
// Before upgrading node-2: migrate all its actors away
api.drainNode("node-2").get();

// Now safe to stop and upgrade node-2
system2.stop().get();
```

`drainNode` uses rendezvous hashing to redistribute actors across remaining nodes. Per-actor
failures are best-effort (logged and skipped) so one stuck actor does not abort the drain.

---

## Extending the System

### Custom MetadataStore

Implement `MetadataStore` to use ZooKeeper, Consul, or any distributed KV store:

```java
public class ZooKeeperMetadataStore implements MetadataStore {
    // implement: put, get, delete, listKeys, acquireLock, watch, unwatch, connect, close
}
```

### Custom MessagingSystem

Implement `MessagingSystem` to use Kafka, RabbitMQ, or gRPC:

```java
public class KafkaMessagingSystem implements MessagingSystem {
    // implement: sendMessage, registerMessageHandler, start, stop
}
```

---

## Multiple Nodes Example

```java
// Shared infrastructure
MetadataStore etcd = ClusterFactory.createEtcdMetadataStore("http://etcd-host:2379");
RedisPersistenceProvider redis = new RedisPersistenceProvider(
    "redis://redis-host:6379", "myapp", KryoSerializationProvider.INSTANCE);

// Node 1
MessagingSystem rms1 = ClusterFactory.createDirectMessagingSystem("node-1", 8080);
ClusterActorSystem system1 = ClusterConfiguration.builder()
    .systemId("node-1")
    .metadataStore(etcd)
    .messagingSystem(rms1)
    .persistenceProvider(redis)
    .build();
system1.start().get();

// Node 2 (separate JVM / host)
MessagingSystem rms2 = ClusterFactory.createDirectMessagingSystem("node-2", 8080);
ClusterActorSystem system2 = ClusterConfiguration.builder()
    .systemId("node-2")
    .metadataStore(etcd)
    .messagingSystem(rms2)
    .persistenceProvider(redis)
    .build();
system2.start().get();

// Actors registered on either node are routable from both
Pid actor = system1.register(MyActor.class, "my-actor");
actor.tell("Hello from node-1");
```

---

## Production Checklist

- [ ] etcd cluster: 3 or 5 nodes for HA (odd count required for quorum).
- [ ] Redis: enable both RDB snapshots and AOF for durability. See [cluster-deployment.md](cluster-deployment.md).
- [ ] `withPersistenceProvider(redis)` configured on every node — prevents state loss on reassignment.
- [ ] `deliveryGuarantee` set explicitly — default is `EXACTLY_ONCE` (highest overhead).
- [ ] Health check endpoint wired to `system.healthCheck()`.
- [ ] `ClusterMetrics` exposed to Prometheus / Grafana.
- [ ] Rolling upgrades: use `api.drainNode(nodeId)` before stopping each node.
- [ ] Actor IDs are stable across restarts (use semantic IDs, not random UUIDs).
- [ ] `system.stop().get()` called on JVM shutdown (registers a shutdown hook).
