# Cajun Cluster Deployment Guide

This guide covers production deployment of a Cajun cluster: infrastructure setup, configuration
tuning, health monitoring, and rolling upgrade procedures.

---

## Infrastructure Overview

A production Cajun cluster requires three external services:

| Service | Role | Minimum HA setup |
|---------|------|-----------------|
| **etcd** | Metadata store: actor assignments, leader election, heartbeats | 3-node cluster |
| **Redis** | Shared persistence: actor journals and snapshots | 1 primary + 1 replica (Sentinel or Cluster) |
| **JVM nodes** | Cajun actor systems | 2+ nodes |

---

## etcd Setup

### Minimum Production Configuration

A 3-node etcd cluster tolerates 1 node failure. Use 5 nodes to tolerate 2 simultaneous failures.

```bash
# Node 1
etcd \
  --name etcd1 \
  --data-dir /var/lib/etcd \
  --listen-peer-urls http://10.0.0.1:2380 \
  --listen-client-urls http://10.0.0.1:2379 \
  --initial-advertise-peer-urls http://10.0.0.1:2380 \
  --advertise-client-urls http://10.0.0.1:2379 \
  --initial-cluster etcd1=http://10.0.0.1:2380,etcd2=http://10.0.0.2:2380,etcd3=http://10.0.0.3:2380 \
  --initial-cluster-state new

# Node 2 and 3: same flags with their respective addresses
```

### Cajun Connection

```java
import com.cajunsystems.runtime.cluster.ClusterFactory;

// Connect to one or more etcd endpoints
MetadataStore etcd = ClusterFactory.createEtcdMetadataStore("http://10.0.0.1:2379");
```

For multi-endpoint support (recommended), use a load balancer or etcd client-side balancing
in front of the cluster. The `MetadataStore` abstraction retries idempotent operations
automatically with exponential backoff and jitter.

### etcd Key Namespacing

Cajun writes the following key prefixes to etcd:

| Prefix | Content |
|--------|---------|
| `cajun/actor/<id>` | Actor-to-node assignment |
| `cajun/node/<id>` | Node heartbeat (refreshed every 5 s) |
| `cajun/leader` | Leader election lock |

If you share an etcd cluster across multiple Cajun deployments, ensure each deployment uses
a unique `systemId` prefix or separate etcd namespaces.

---

## Redis Setup

### Durability Configuration

Enable both RDB snapshots and AOF for maximum durability:

```
# redis.conf
save 900 1          # RDB: snapshot if at least 1 key changed in 900 seconds
save 300 10         # RDB: snapshot if at least 10 keys changed in 300 seconds
save 60 10000       # RDB: snapshot if at least 10000 keys changed in 60 seconds

appendonly yes                  # Enable AOF
appendfsync everysec            # fsync every second (balance between safety and throughput)
no-appendfsync-on-rewrite no    # Keep fsync during rewrites
auto-aof-rewrite-percentage 100
auto-aof-rewrite-min-size 64mb
```

### Redis Key Namespacing

The `keyPrefix` passed to `RedisPersistenceProvider` is prepended to all Redis keys:

```java
RedisPersistenceProvider redis = new RedisPersistenceProvider(
    "redis://redis-host:6379",
    "myapp",                         // keyPrefix
    KryoSerializationProvider.INSTANCE
);
```

Keys written by Cajun follow the pattern:

| Key pattern | Content |
|-------------|---------|
| `<prefix>:journal:<actorId>` | Batched message journal entries |
| `<prefix>:snapshot:<actorId>` | Latest state snapshot |

Use separate `keyPrefix` values per environment (dev / staging / production).

### High Availability

For a Redis Sentinel configuration:

```java
// Use the Redis URI with Sentinel support (Lettuce client underneath)
RedisPersistenceProvider redis = new RedisPersistenceProvider(
    "redis-sentinel://sentinel1:26379,sentinel2:26379,sentinel3:26379?sentinelMasterId=mymaster",
    "myapp",
    KryoSerializationProvider.INSTANCE
);
```

---

## JVM Node Configuration

### Startup Bootstrap

```java
import com.cajunsystems.cluster.*;
import com.cajunsystems.persistence.redis.RedisPersistenceProvider;
import com.cajunsystems.serialization.KryoSerializationProvider;
import com.cajunsystems.runtime.cluster.ClusterFactory;

public class ClusterNode {
    public static void main(String[] args) throws Exception {
        String nodeId = System.getenv("CAJUN_NODE_ID"); // e.g. "node-1"

        MetadataStore etcd = ClusterFactory.createEtcdMetadataStore(
            System.getenv("ETCD_ENDPOINT"));            // e.g. "http://etcd:2379"

        MessagingSystem rms = ClusterFactory.createDirectMessagingSystem(
            nodeId,
            Integer.parseInt(System.getenv("CAJUN_PORT"))); // e.g. 8080

        RedisPersistenceProvider redis = new RedisPersistenceProvider(
            System.getenv("REDIS_URI"),                  // e.g. "redis://redis:6379"
            "myapp",
            KryoSerializationProvider.INSTANCE);

        ClusterActorSystem system = ClusterConfiguration.builder()
            .systemId(nodeId)
            .metadataStore(etcd)
            .messagingSystem(rms)
            .deliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE)
            .persistenceProvider(redis)
            .build();

        system.start().get();

        // Register actors
        system.register(MyActor.class, "my-actor");

        // Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                system.getManagementApi().drainNode(nodeId).get(30, TimeUnit.SECONDS);
                system.stop().get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                // log and exit
            }
        }));
    }
}
```

### JVM Flags

```
--enable-preview                    # Required for Java 21 preview features
-Xms512m -Xmx2g                    # Heap sizing (tune to workload)
-XX:+UseG1GC                       # G1 GC for low-latency actor mailboxes
-XX:MaxGCPauseMillis=100
-Dcom.cajunsystems.heartbeat.interval=5   # Node heartbeat interval (seconds)
```

---

## Health Monitoring

### Health Check Endpoint

Expose `system.healthCheck()` as an HTTP endpoint for Kubernetes probes or load balancers:

```java
// Example: Javalin / embedded HTTP server
app.get("/health", ctx -> {
    ClusterHealthStatus health = system.healthCheck().get(5, TimeUnit.SECONDS);
    if (health.healthy()) {
        ctx.status(200).result("OK");
    } else {
        ctx.status(503).result("UNHEALTHY");
    }
});

// Kubernetes liveness probe
// livenessProbe:
//   httpGet:
//     path: /health
//     port: 8081
//   initialDelaySeconds: 30
//   periodSeconds: 10
```

### ClusterMetrics to Prometheus

```java
ClusterMetrics metrics = system.getClusterMetrics();

// Register gauges with Micrometer or Prometheus client
Gauge.builder("cajun.routing.cache_hit_ratio", metrics,
    m -> (double) m.getCacheHits() / Math.max(1, m.getCacheHits() + m.getCacheMisses()))
    .tag("node", nodeId)
    .register(meterRegistry);

Gauge.builder("cajun.routing.remote_messages", metrics, ClusterMetrics::getRemoteMessagesRouted)
    .tag("node", nodeId)
    .register(meterRegistry);

Gauge.builder("cajun.cluster.node_count", metrics, ClusterMetrics::getNodeJoinCount)
    .tag("node", nodeId)
    .register(meterRegistry);
```

Useful dashboards:

- **Cache hit ratio** — low ratio indicates frequent actor reassignments or short TTL.
- **Remote vs local routing ratio** — high remote ratio may indicate imbalanced actor placement.
- **Average routing latency** — baseline latency for inter-node message delivery.
- **Node join/departure count** — detects churn or instability.

---

## Rolling Upgrade Procedure

Rolling upgrades allow zero-downtime deployments by draining one node at a time.

### Step-by-Step

```bash
# For each node (one at a time):

# 1. Drain the node: migrate all actors to remaining nodes
curl -X POST http://node-1:8081/admin/drain

# 2. Wait for drain to complete (poll health or implement callback)
# actors are now running on node-2, node-3, ...

# 3. Stop the node gracefully
systemctl stop cajun-node-1

# 4. Deploy new artifact / upgrade JVM
# ...

# 5. Start the node with new version
systemctl start cajun-node-1

# 6. Actors will recover from Redis automatically as traffic resumes
```

### Admin Endpoint (implement on each node)

```java
app.post("/admin/drain", ctx -> {
    String nodeId = system.getSystemId();
    system.getManagementApi().drainNode(nodeId).get(60, TimeUnit.SECONDS);
    ctx.status(200).result("drained");
});
```

### Smoke Test After Upgrade

```java
ClusterManagementApi api = system.getManagementApi();

// Verify the node rejoined
Set<String> nodes = api.listNodes().get();
assert nodes.contains(nodeId) : "Node did not rejoin cluster after upgrade";

// Verify actors rebalanced back
ClusterHealthStatus health = system.healthCheck().get();
assert health.healthy() : "Cluster unhealthy after upgrade";
```

---

## Kubernetes Deployment

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: cajun-node
spec:
  replicas: 3
  selector:
    matchLabels:
      app: cajun-node
  template:
    metadata:
      labels:
        app: cajun-node
    spec:
      containers:
        - name: cajun-node
          image: myregistry/cajun-app:latest
          env:
            - name: CAJUN_NODE_ID
              valueFrom:
                fieldRef:
                  fieldPath: metadata.name     # e.g. cajun-node-0
            - name: CAJUN_PORT
              value: "8080"
            - name: ETCD_ENDPOINT
              value: "http://etcd-headless:2379"
            - name: REDIS_URI
              value: "redis://redis-headless:6379"
          ports:
            - containerPort: 8080   # messaging
            - containerPort: 8081   # admin / health
          livenessProbe:
            httpGet:
              path: /health
              port: 8081
            initialDelaySeconds: 30
            periodSeconds: 10
          lifecycle:
            preStop:
              exec:
                command: ["curl", "-X", "POST", "http://localhost:8081/admin/drain"]
```

The `preStop` hook drains the node before Kubernetes terminates the pod, ensuring no
message loss during rolling updates.

---

## Troubleshooting

### Split-Brain Detection

If two nodes both believe they are the cluster leader, it is usually caused by:

- etcd clock skew exceeding the heartbeat interval
- Network partition with asymmetric connectivity

Mitigation: etcd's Raft consensus prevents true split-brain. If you see dual-leader logs,
check etcd cluster health with `etcdctl endpoint health`.

### Actor Not Recovering After Reassignment

Checklist:
1. All nodes configured with `persistenceProvider(redis)` pointing to the **same** Redis instance.
2. Same `keyPrefix` on all nodes.
3. Same `SerializationProvider` (Kryo or JSON) on all nodes — mixed providers cause deserialization failures.
4. Actor ID is stable (not regenerated on restart).

### High Routing Latency

- Check `metrics.getAverageRoutingLatencyNs()` — compare to baseline.
- `NodeCircuitBreaker` may be open for a target node. Check logs for `OPEN` circuit entries.
- Network saturation: consider co-locating frequently communicating actors on the same node.

### Redis Journal Growing Unboundedly

Take periodic snapshots in your `StatefulActor`:

```java
@Override
protected CounterState processMessage(CounterState state, CounterMessage msg) {
    CounterState next = // ... process msg
    if (messageCount++ % 1000 == 0) {
        saveSnapshot(next);   // trims journal entries before the snapshot
    }
    return next;
}
```

See [persistence_guide.md](persistence_guide.md) for journal cleanup strategies.
