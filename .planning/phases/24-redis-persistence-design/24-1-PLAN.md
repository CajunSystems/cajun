# Phase 24 — Redis Persistence Design

## Objective

Produce a documented schema design for Redis-backed journal and snapshot stores, evaluate tradeoffs vs LMDB and file, and add the Lettuce client dependency. The output is a `24-1-DESIGN.md` artifact that Phase 25 (Redis Persistence Provider) executes against.

No production code is written in this phase beyond the dependency addition.

---

## Execution Context

- Branch: `feature/roux-effect-integration`
- Java 21, `--enable-preview`; run tests: `./gradlew test`
- Key interfaces (already read):
  - `MessageJournal<M>`: `append(actorId, M)→Long`, `readFrom(actorId, fromSeq)→List<JournalEntry<M>>`, `truncateBefore(actorId, upToSeq)→Void`, `getHighestSequenceNumber(actorId)→Long`, `close()`
  - `SnapshotStore<S>`: `saveSnapshot(actorId, S, seqNum)→Void`, `getLatestSnapshot(actorId)→Optional<SnapshotEntry<S>>`, `deleteSnapshots(actorId)→Void`, `close()`
  - `JournalEntry<M>`: `sequenceNumber`, `actorId`, `message`, `timestamp`
  - `SnapshotEntry<S>`: `actorId`, `state`, `sequenceNumber`, `timestamp`
- Files to edit: `cajun-persistence/build.gradle`, `lib/build.gradle`, `gradle/libs.versions.toml`

---

## Context — Design Decisions Already Made

The following decisions are pre-researched. The plan documents rationale and records them formally.

**Journal data structure: Redis Hash**
- Key: `cajun:journal:{actorId}` — Hash where field = seqNum (decimal string), value = Kryo-serialized `JournalEntry` bytes
- Sequence tracking: `cajun:journal:{actorId}:seq` — Redis String counter (`INCR` for next seq)
- Rationale: Journals are kept short via snapshot+truncate (typical actor lifecycle). HSET/HGET are O(1). HDEL for truncation is one command. The `readFrom` HGETALL + Java-side sort is acceptable for short journals.

**Snapshot data structure: Redis String (one key per actor)**
- Key: `cajun:snapshot:{actorId}` — String value = Kryo-serialized `SnapshotEntry` bytes
- Rationale: `SnapshotStore` only needs the latest snapshot (`getLatestSnapshot`). A single SET/GET/DEL per actor is the simplest possible mapping. No historical snapshots needed.

**Key namespace:**
- `cajun:journal:{actorId}` — journal Hash
- `cajun:journal:{actorId}:seq` — sequence counter String
- `cajun:snapshot:{actorId}` — snapshot String

**Redis persistence mode: AOF with `appendfsync everysec`**
- `appendfsync always`: maximum durability (every write flushed to disk) but 10–100× slower throughput
- `appendfsync everysec`: at most 1 second of data loss on Redis crash; 10–50× faster than `always`
- Production recommendation: `appendfsync everysec` as the default; `appendfsync always` for critical stateful actors where data loss is unacceptable

**Client library: Lettuce 6.x**
- `io.lettuce:lettuce-core:6.3.2.RELEASE`
- Lettuce returns `CompletableFuture` natively (via `StatefulRedisConnection.async().xxx()`) — maps directly to our `CompletableFuture`-returning interfaces
- Single `StatefulRedisConnection` is thread-safe for concurrent use (Lettuce multiplexes commands over one connection)
- Jedis requires per-thread connections (via `JedisPool`) — awkward in the virtual-thread actor model and incompatible with our async interface signatures

---

## Tasks

### Task 1 — Write Design Document

Create `.planning/phases/24-redis-persistence-design/24-1-DESIGN.md`.

The document must be thorough enough that Phase 25 can implement `RedisMessageJournal`, `RedisSnapshotStore`, and `RedisPersistenceProvider` directly from it without additional research.

**Required sections:**

#### 1. Executive Summary
3 sentences: what Redis adds (shared persistence for cluster reassignment), what it replaces (node-local file/LMDB), and which phase implements it.

#### 2. Interface → Redis Command Mapping

**RedisMessageJournal — Full command mapping:**

| Operation | Redis Command(s) | Complexity | Notes |
|-----------|-----------------|------------|-------|
| `append(actorId, message)` | `HINCRBY cajun:journal:{actorId}:seq 1` → get `seqNum`; `HSET cajun:journal:{actorId} {seqNum} <bytes>` | O(1) | Two commands; use pipeline or Lua script for atomicity |
| `readFrom(actorId, fromSeq)` | `HGETALL cajun:journal:{actorId}` → Java-side filter/sort | O(n) | Acceptable for short journals after truncation |
| `truncateBefore(actorId, upToSeq)` | `HDEL cajun:journal:{actorId} {0} {1} ... {upToSeq-1}` | O(k) where k = deleted count | Build HDEL vararg from 0 to upToSeq-1 |
| `getHighestSequenceNumber(actorId)` | `GET cajun:journal:{actorId}:seq` | O(1) | Returns -1 if key not present |
| `close()` | No-op or return connection to pool | — | — |

Note on `HINCRBY` for sequence: Use key `cajun:journal:{actorId}:seq` as an atomic counter. `HINCRBY` returns the new value — this is the sequence number for the new entry.

Alternative considered: Using Redis Streams (`XADD`, `XRANGE`, `XTRIM`) — rejected because stream IDs are time-based (not monotonic integers), so mapping Cajun's explicit `seqNum` requires extra storage and XRANGE filtering is by stream ID not by Cajun seqNum.

**RedisSnapshotStore — Full command mapping:**

| Operation | Redis Command | Complexity | Notes |
|-----------|--------------|------------|-------|
| `saveSnapshot(actorId, state, seqNum)` | `SET cajun:snapshot:{actorId} <bytes>` | O(1) | Overwrites previous snapshot; only latest kept |
| `getLatestSnapshot(actorId)` | `GET cajun:snapshot:{actorId}` | O(1) | Returns empty Optional if nil |
| `deleteSnapshots(actorId)` | `DEL cajun:snapshot:{actorId}` | O(1) | — |
| `close()` | No-op | — | — |

#### 3. Key Namespace

```
cajun:journal:{actorId}        # Redis Hash — journal entries (field=seqNum, value=Kryo bytes)
cajun:journal:{actorId}:seq    # Redis String — atomic sequence counter
cajun:snapshot:{actorId}       # Redis String — latest SnapshotEntry bytes
```

**Namespacing considerations:**
- Prefix `cajun:` isolates Cajun keys from other applications sharing the same Redis instance
- Actor IDs may contain special characters — document that actor IDs must not contain `:` characters, or URL-encode them before use as key components
- In Redis Cluster mode, all keys for one actor must hash to the same slot — use hash tags `{actorId}` in the key to force co-location: `cajun:journal:{actorId}`, `cajun:journal:{actorId}:seq`, `cajun:snapshot:{actorId}` all share the same hash tag `{actorId}`, so they'll land on the same node

#### 4. Redis Persistence Mode Guidance

| Mode | Durability | Throughput | Use Case |
|------|-----------|------------|----------|
| No persistence | None (memory only) | Highest | Development/testing only |
| RDB only | Periodic dump (10s–15min gap) | High | Acceptable data loss window |
| AOF `everysec` | ≤1s data loss | Medium-High | **Recommended default** |
| AOF `always` | Zero data loss | Low | Critical actors where no loss acceptable |
| RDB + AOF | Best of both | Medium | Redis default (v7+) |

#### 5. Tradeoff Comparison: Redis vs LMDB vs File

| Dimension | File (filesystem) | LMDB | Redis |
|-----------|------------------|------|-------|
| Cross-node access | ❌ Node-local | ❌ Node-local | ✅ Shared |
| Throughput (writes) | 10K–50K/s sequential | 500K–1M/s | 50K–200K/s (AOF everysec) |
| Throughput (reads) | 10K–100K/s | 1M–2M/s | 100K–500K/s |
| Latency (write) | 0.1–1ms | 0.01–0.1ms | 0.1–1ms (local), 1–5ms (remote) |
| Operational complexity | Low | Medium (mapSize config, liblmdb0) | High (separate Redis process, AOF config) |
| Failure isolation | Isolated to node | Isolated to node | Redis is a SPoF unless Redis Sentinel/Cluster |
| Recovery from node failure | ❌ State lost | ❌ State lost | ✅ State preserved |
| Max data size | Limited by disk | Limited by mapSize config | Limited by Redis memory |
| Backup/restore | File copy | File copy | BGSAVE / AOF rewrite |

**Recommendation**: Redis for cluster deployments where actor state must survive node reassignment. File/LMDB for single-node or development use cases.

#### 6. Lettuce Client Design Notes

For Phase 25 implementation:

**Connection setup:**
```java
RedisClient client = RedisClient.create("redis://localhost:6379");
StatefulRedisConnection<String, byte[]> connection = 
    client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
RedisAsyncCommands<String, byte[]> async = connection.async();
```

The codec `StringCodec.UTF8` for keys and `ByteArrayCodec.INSTANCE` for values lets us use String keys (actor IDs, hash field names) while storing raw Kryo bytes as values.

**Pipelining for append atomicity:**
`append()` needs two commands (`HINCRBY` for seq, `HSET` for entry). To make this atomic without a Lua script, use a pipeline:
```java
async.setAutoFlushCommands(false);
RedisFuture<Long> seqFuture = async.hincrby(seqKey, "seq", 1L);
// Then use the result of seqFuture to build the HSET key
async.flushCommands();
```
OR use a Lua script (simpler atomicity guarantee). Document both approaches; the Lua script is preferred for correctness.

**Health check:**
```java
connection.sync().ping(); // returns "PONG"
```

**Shutdown:**
```java
connection.close();
client.shutdown();
```

#### 7. Known Limitations and Risks

1. **Redis is a Single Point of Failure** unless Redis Sentinel (HA failover) or Redis Cluster (sharding + replication) is configured. Phase 25 implementation uses a single Redis endpoint; Phase 26 can add Sentinel/Cluster support.

2. **Memory limit**: Redis is in-memory. Large journals with many untruncated entries will consume RAM. The `truncateBefore` operation (called after snapshot) is critical to keep memory bounded.

3. **HGETALL for large journals**: If `truncateBefore` is never called (e.g., snapshot disabled), `readFrom` will load the entire journal into memory. This is a pre-existing constraint from the file-based implementation; document the recommendation to enable snapshots.

4. **Actor ID characters**: Colons in actor IDs will corrupt the key namespace. Phase 25 implementation should sanitize actor IDs (replace `:` with `_` or URL-encode).

5. **Sequence counter gap on Lettuce disconnect**: If `HINCRBY` succeeds but `HSET` fails (network error between the two), a sequence number is consumed but no entry is written. This creates a gap in sequence numbers. `readFrom` must handle gaps gracefully (skip missing seqNums). OR use a Lua script to make the two-command sequence atomic.

---

### Task 2 — Add Lettuce Dependency

Edit `gradle/libs.versions.toml`:
```toml
# Under [versions]
lettuce = "6.3.2.RELEASE"
```

```toml
# Under [libraries]
lettuce-core = { module = "io.lettuce:lettuce-core", version.ref = "lettuce" }
```

Edit `cajun-persistence/build.gradle` — add under `dependencies {}`:
```groovy
// Redis client for RedisPersistenceProvider
implementation libs.lettuce.core
```

Edit `lib/build.gradle` — add under `dependencies {}`:
```groovy
// Redis client (for integration tests and RedisPersistenceProvider in lib module)
implementation libs.lettuce.core
```

Verify build compiles:
```bash
cd /Users/pradeep.samuel/cajun && ./gradlew :cajun-persistence:compileJava :lib:compileJava 2>&1 | tail -20
```

Run tests to confirm no regressions:
```bash
cd /Users/pradeep.samuel/cajun && ./gradlew test 2>&1 | tail -20
```

Commit:
```bash
git add gradle/libs.versions.toml cajun-persistence/build.gradle lib/build.gradle
git commit -m "chore(24-1): add Lettuce 6.3.2 dependency to cajun-persistence and lib modules

Adds io.lettuce:lettuce-core:6.3.2.RELEASE as the Redis client library.
Lettuce chosen over Jedis for async-first API (CompletableFuture) compatible
with our MessageJournal/SnapshotStore contract.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Verification

1. **Design document exists**: `.planning/phases/24-redis-persistence-design/24-1-DESIGN.md` — non-empty, all 7 sections present
2. **Build passes**: `./gradlew :cajun-persistence:compileJava :lib:compileJava` — no errors after Lettuce dependency addition
3. **Tests pass**: `./gradlew test` — no regressions
4. **Lettuce on classpath**: `./gradlew :cajun-persistence:dependencies --configuration runtimeClasspath 2>&1 | grep lettuce` — shows lettuce-core

---

## Success Criteria

- `24-1-DESIGN.md` present with all 7 sections
- Redis command mapping for every `MessageJournal` and `SnapshotStore` method
- Key namespace documented with Redis Cluster hash tag considerations
- Tradeoff table comparing Redis vs LMDB vs file
- Lettuce dependency added to both `cajun-persistence/build.gradle` and `lib/build.gradle`
- Build green

---

## Output

- **New file**: `.planning/phases/24-redis-persistence-design/24-1-DESIGN.md`
- **Modified files**:
  - `gradle/libs.versions.toml` — lettuce version entry
  - `cajun-persistence/build.gradle` — lettuce-core dep
  - `lib/build.gradle` — lettuce-core dep
- **Planning artifacts**:
  - `.planning/phases/24-redis-persistence-design/24-1-SUMMARY.md`
  - `.planning/STATE.md` — Phase 24 ✅ Complete
  - `.planning/ROADMAP.md` — Phase 24 struck through
