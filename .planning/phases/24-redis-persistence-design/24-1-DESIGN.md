# Phase 24 Design — Redis Persistence for Cajun Actor System

## 1. Executive Summary

Redis provides shared, network-accessible persistence that allows `StatefulActor` state to survive cluster node reassignment — the critical gap identified in Phase 22 (C1 finding). Unlike the node-local file and LMDB backends, a shared Redis instance is visible to every node in the cluster, so an actor that migrates from node A to node B can recover its full journal and latest snapshot from the same Redis store. Phase 25 implements `RedisMessageJournal`, `RedisSnapshotStore`, and `RedisPersistenceProvider` directly from this design.

---

## 2. Interface → Redis Command Mapping

### 2.1 RedisMessageJournal

Key structure:
- `cajun:journal:{actorId}` — Redis Hash (field = decimal seqNum string, value = Kryo-serialized `JournalEntry` bytes)
- `cajun:journal:{actorId}:seq` — Redis String counter (current highest sequence number; `INCR` returns next)

| Operation | Redis Command(s) | Complexity | Notes |
|-----------|-----------------|------------|-------|
| `append(actorId, message)` | Lua: `INCR cajun:journal:{actorId}:seq` → `seqNum`; `HSET cajun:journal:{actorId} {seqNum} <bytes>` | O(1) + O(1) | Lua script for atomicity (see §6.2) |
| `readFrom(actorId, fromSeq)` | `HGETALL cajun:journal:{actorId}` → Java-side filter (field >= fromSeq) and sort | O(n) | Acceptable; journals kept short by snapshot+truncate |
| `truncateBefore(actorId, upToSeq)` | `HDEL cajun:journal:{actorId} 0 1 2 … {upToSeq-1}` | O(k) deleted | Build vararg list 0..upToSeq-1 in a single HDEL call |
| `getHighestSequenceNumber(actorId)` | `GET cajun:journal:{actorId}:seq` | O(1) | Returns -1 if key absent (empty journal) |
| `close()` | No-op (connection managed by `RedisPersistenceProvider`) | — | — |

**Why Hash over Redis Streams:** Redis Streams use time-based auto-generated IDs (`ms-seq` format). Cajun controls explicit integer sequence numbers (0, 1, 2, …), so XRANGE filtering by Cajun seqNum would require scanning all stream entries. A Hash with decimal seqNum fields provides O(1) random access with no ID mismatch.

**Why Hash over Sorted Set:** Sorted Set members must be unique strings. Embedding payload bytes in the member value is fragile. The Hash approach keeps the score (seqNum) as the field name and the payload as the value — a natural mapping.

### 2.2 RedisSnapshotStore

Key structure:
- `cajun:snapshot:{actorId}` — Redis String (value = Kryo-serialized `SnapshotEntry` bytes)

`SnapshotStore` only exposes `getLatestSnapshot` (no historical snapshot retrieval), so a single String key per actor is sufficient — always overwritten with the latest snapshot.

| Operation | Redis Command | Complexity | Notes |
|-----------|--------------|------------|-------|
| `saveSnapshot(actorId, state, seqNum)` | `SET cajun:snapshot:{actorId} <bytes>` | O(1) | Overwrites previous snapshot atomically |
| `getLatestSnapshot(actorId)` | `GET cajun:snapshot:{actorId}` | O(1) | Returns `Optional.empty()` if nil |
| `deleteSnapshots(actorId)` | `DEL cajun:snapshot:{actorId}` | O(1) | — |
| `close()` | No-op | — | — |
| `isHealthy()` | `PING` (on demand) | O(1) | Returns `true` if PONG received |

---

## 3. Key Namespace

```
cajun:journal:{actorId}        # Hash  — journal entries: field=seqNum, value=JournalEntry bytes
cajun:journal:{actorId}:seq    # String — atomic INCR counter for next sequence number
cajun:snapshot:{actorId}       # String — latest SnapshotEntry bytes (always overwritten)
```

### 3.1 Namespacing Considerations

**Prefix isolation:** The `cajun:` prefix separates Cajun keys from other applications sharing the same Redis instance. Use a different prefix per environment (e.g., `cajun-test:`, `cajun-prod:`). The prefix is configurable via `RedisPersistenceProvider` constructor.

**Actor ID safety:** Actor IDs may contain characters that collide with the `:` key separator. Phase 25 implementation must sanitize actor IDs before use as key components. Replace `:` with `_` before embedding in key strings (actor IDs are also stored inside the serialized value, so reversibility is not required for key construction).

**Redis Cluster hash tags:** In Redis Cluster mode, keys are sharded by their hash slot. The three keys for one actor must land on the same cluster node to allow atomic multi-key operations (e.g., the Lua append script). Use hash tags `{actorId}` to force co-location:

```
cajun:journal:{actor-123}        # hash slot computed from "actor-123"
cajun:journal:{actor-123}:seq    # same hash slot ("actor-123" is the tag)
cajun:snapshot:{actor-123}       # same hash slot
```

The curly-brace notation tells Redis Cluster to hash only the part inside `{}`. All three keys hash identically, guaranteeing they reside on the same shard and are eligible for multi-key Lua scripts.

---

## 4. Redis Persistence Mode Guidance

The persistence mode determines actor state durability on Redis restart.

| Mode | Configuration | Max Data Loss | Write Throughput | Recommendation |
|------|--------------|---------------|-----------------|----------------|
| No persistence | `save ""` in redis.conf | 100% (all state lost on restart) | Highest | Development/testing only |
| RDB only | `save 900 1` (default) | Up to 15 minutes | High | Acceptable for cache-like actors |
| AOF `no` | `appendonly yes`, `appendfsync no` | OS buffer (~30s) | High | Not recommended for actor state |
| AOF `everysec` | `appendonly yes`, `appendfsync everysec` | ≤1 second | Medium-High | **Recommended default** |
| AOF `always` | `appendonly yes`, `appendfsync always` | Zero | Low (10–100× slower) | Critical actors requiring zero loss |
| RDB + AOF hybrid | Redis 7+ default | ≤1 second (AOF governs) | Medium | Redis 7+ production standard |

**Recommended configuration for Cajun production deployment:**
```
appendonly yes
appendfsync everysec
auto-aof-rewrite-percentage 100
auto-aof-rewrite-min-size 64mb
```

**Note on LMDB comparison:** LMDB provides ACID guarantees at the OS level (memory-mapped file, fsync on commit). `appendfsync everysec` provides comparable durability with at most 1 second of data loss — acceptable for most actor workloads and significantly simpler to operate than LMDB in a containerized cluster.

---

## 5. Tradeoff Comparison: Redis vs LMDB vs File

| Dimension | File (filesystem) | LMDB | Redis (AOF everysec) |
|-----------|------------------|----- |----------------------|
| Cross-node access | ❌ Node-local | ❌ Node-local | ✅ Shared network |
| Survives node reassignment | ❌ | ❌ | ✅ |
| Write throughput | 10K–50K msg/s | 500K–1M msg/s | 50K–200K msg/s |
| Read throughput | 10K–100K/s | 1M–2M/s | 100K–500K/s |
| Write latency (local) | 0.1–1ms | 0.01–0.1ms | 0.1–0.5ms local; 1–5ms remote |
| Max data size | Disk capacity | `mapSize` config (default 1MB — must be pre-configured) | Available RAM |
| Operational complexity | Low | Medium (`liblmdb0` native lib, mapSize) | High (external process, AOF, Sentinel/Cluster for HA) |
| Failure isolation | Node failure = data loss | Node failure = data loss | Redis failure = data unavailable (data preserved) |
| Backup / restore | `cp` | `cp` | `BGSAVE` or AOF rewrite |
| Native async API | No (wraps sync I/O) | No (wraps sync I/O) | ✅ Lettuce `CompletableFuture` |
| Test isolation | Temp directories | Temp directories | Shared instance; use UUID actor IDs or `FLUSHDB` |
| Java dependency | None | `lmdbjava:0.8.3` + native | `lettuce-core:6.3.2.RELEASE` |

**Summary:** Use Redis when deploying in cluster mode (actors must survive reassignment). Use LMDB for maximum single-node throughput. Use file-based persistence for simplicity in development.

---

## 6. Lettuce Client Design Notes

### 6.1 Connection Setup

```java
RedisClient client = RedisClient.create("redis://localhost:6379");
StatefulRedisConnection<String, byte[]> connection =
    client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
RedisAsyncCommands<String, byte[]> async = connection.async();
```

The codec `StringCodec.UTF8` for keys, `ByteArrayCodec.INSTANCE` for values enables:
- String keys (actor IDs, hash field names as decimal seqNum strings)
- Raw `byte[]` values (Kryo-serialized `JournalEntry` and `SnapshotEntry`)

A single `StatefulRedisConnection` is thread-safe in Lettuce — all concurrent actor operations can share one connection. Lettuce multiplexes commands over the underlying Netty channel.

### 6.2 Atomic append via Lua Script

The `append()` operation requires two Redis commands (`INCR` seq counter, then `HSET` entry). A network failure between the two would consume a sequence number without writing the entry, creating a gap. To guarantee atomicity, use a Lua script:

```lua
-- KEYS[1] = hash key, e.g. "cajun:journal:{actor-1}"
-- KEYS[2] = seq key,  e.g. "cajun:journal:{actor-1}:seq"
-- ARGV[1] = serialized JournalEntry bytes (as bulk string)
local seq = redis.call('INCR', KEYS[2])
redis.call('HSET', KEYS[1], tostring(seq), ARGV[1])
return seq
```

Lettuce supports Lua scripts via:
```java
RedisFuture<Long> future = async.eval(script,
    ScriptOutputType.INTEGER,
    new String[]{hashKey, seqKey},
    entryBytes);
```

### 6.3 Health Check

```java
public boolean isHealthy() {
    try {
        String result = connection.sync().ping();
        return "PONG".equals(result);
    } catch (Exception e) {
        return false;
    }
}
```

### 6.4 Shutdown

```java
connection.close();
client.shutdown();
```

The `RedisPersistenceProvider` must call both in its own `close()` method.

### 6.5 RedisPersistenceProvider Constructor Design

```java
// Minimal — connect to local Redis with default settings
new RedisPersistenceProvider("redis://localhost:6379")

// With key prefix (e.g. for test isolation or multi-tenant)
new RedisPersistenceProvider("redis://localhost:6379", "cajun-test")

// Full control — custom serialization provider
new RedisPersistenceProvider("redis://localhost:6379", "cajun", KryoSerializationProvider.INSTANCE)
```

Default `keyPrefix = "cajun"`, default serializer = `KryoSerializationProvider.INSTANCE`.

---

## 7. Known Limitations and Risks

### L1: Redis as Single Point of Failure
A standalone Redis instance is a SPoF. Phase 25 targets single-instance Redis (sufficient for evaluation). Production deployments should use Redis Sentinel (automatic failover) or Redis Cluster (sharding + replication). Phase 26 can address HA configuration.

### L2: Memory Pressure from Untruncated Journals
Redis is in-memory. If `truncateBefore` is never called (snapshots disabled), journal Hashes grow unboundedly. **Mitigation:** Document that `StatefulActor` must have snapshots enabled when using Redis persistence. `truncateBefore` is called automatically after each snapshot.

### L3: HGETALL Performance for Large Journals
`readFrom` calls `HGETALL` and filters in Java. For actors where snapshotting is disabled or infrequent, journals can grow large. **Mitigation:** Same as L2 — enforce snapshot configuration. A future optimization (Phase 29) could use `HSCAN` for incremental reads.

### L4: Actor ID Character Constraints
Colons in actor IDs corrupt the key namespace (`cajun:journal:actor:sub` is ambiguous). **Mitigation:** Phase 25 must sanitize actor IDs — replace `:` with `_` before constructing Redis keys.

### L5: Sequence Number Gap on Partial Write Failure
Without the Lua script (§6.2), if `INCR` succeeds but `HSET` fails, a sequence number is consumed with no entry written. `readFrom` must handle gaps gracefully — skip missing seqNums without erroring. **Mitigation:** Use the Lua script to eliminate gaps entirely.

### L6: Test Isolation
Tests sharing a Redis instance will interfere if using the same actor IDs. **Mitigation:** Phase 25 tests must use UUID-based actor IDs (matching the existing STATE.md guidance for `StatefulActor` tests) and clean up keys in `@AfterEach`.
