# Phase 25 — Redis Persistence Provider

## Objective

Implement `RedisMessageJournal<M>`, `RedisSnapshotStore<S>`, and `RedisPersistenceProvider` in `cajun-persistence`. Wire atomic append via Lua script (INCR + HSET). Unit-test with mocked Lettuce commands. Integration-test against a real Redis instance (tagged `@Tag("requires-redis")`). Register `RedisPersistenceProvider` as `"redis"` in `PersistenceProviderRegistry` via explicit user call — not auto-registered.

---

## Execution Context

- Branch: `feature/roux-effect-integration`
- Java 21, `--enable-preview`; run tests: `./gradlew test`
- Module layout:
  - `cajun-core/` — `MessageJournal<M>`, `SnapshotStore<S>`, `PersistenceProvider`, `JournalEntry<M>`, `SnapshotEntry<S>`, `SerializationProvider`, `JavaSerializationProvider`
  - `cajun-persistence/` — implementation module; already has `implementation libs.lettuce.core`
  - `lib/` — all tests live here; has `KryoSerializationProvider`
- New package: `cajun-persistence/src/main/java/com/cajunsystems/persistence/redis/`
- Test isolation: `@Tag("requires-redis")` excluded from `./gradlew test`; tests use UUID-based actor IDs + `@AfterEach` cleanup

---

## Context — Architecture & Design Decisions

**Interface signatures (from Phase 24 discovery):**

`MessageJournal<M>`:
- `CompletableFuture<Long> append(String actorId, M message)`
- `CompletableFuture<List<JournalEntry<M>>> readFrom(String actorId, long fromSequenceNumber)`
- `CompletableFuture<Void> truncateBefore(String actorId, long upToSequenceNumber)`
- `CompletableFuture<Long> getHighestSequenceNumber(String actorId)`
- `void close()`

`SnapshotStore<S>`:
- `CompletableFuture<Void> saveSnapshot(String actorId, S state, long sequenceNumber)`
- `CompletableFuture<Optional<SnapshotEntry<S>>> getLatestSnapshot(String actorId)`
- `CompletableFuture<Void> deleteSnapshots(String actorId)`
- `void close()`
- `default boolean isHealthy()` (override with PING)

`PersistenceProvider`: factory interface; `createMessageJournal()` (no actorId) and `createSnapshotStore()` (no actorId) → throw `UnsupportedOperationException` (Redis requires actorId for key namespace).

**Redis key structure (Phase 24 design):**
```
{prefix}:journal:{actorId}      — Redis Hash: field=seqNum(decimal), value=serialized message bytes
{prefix}:journal:{actorId}:seq  — Redis String counter (INCR for next seqNum)
{prefix}:snapshot:{actorId}     — Redis String: serialized SnapshotEntry bytes
```
Hash tags `{actorId}` ensure co-location in Redis Cluster mode.

**Actor ID sanitization:** Replace `:` with `_` in actorId before constructing Redis keys (Phase 24, L4).

**Lua script for atomic append:**
```lua
-- KEYS[1] = hash key, KEYS[2] = seq key, ARGV[1] = serialized message bytes
local seq = redis.call('INCR', KEYS[2])
redis.call('HSET', KEYS[1], tostring(seq), ARGV[1])
return seq
```

**Journal storage format:** Store serialized message `M` bytes (not full JournalEntry) as hash field values. On `readFrom`, reconstruct `JournalEntry<M>` from: seqNum (hash field name), message (deserialized from bytes), actorId (known parameter), timestamp `Instant.now()`.

**Snapshot storage format:** Store full serialized `SnapshotEntry<S>` bytes (includes seqNum, state, timestamp) as a Redis String value.

**Connection sharing:** `RedisPersistenceProvider` holds ONE shared `StatefulRedisConnection<String, byte[]>` (Lettuce connections are thread-safe). Journals and stores receive a reference to this shared connection. `close()` on journal/store is a no-op; `close()` on provider shuts down connection + client.

**Lettuce codec setup:**
```java
RedisClient client = RedisClient.create(redisUri);
StatefulRedisConnection<String, byte[]> connection =
    client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
```

**Default SerializationProvider:** `JavaSerializationProvider.INSTANCE` (from cajun-core, no extra dep needed in cajun-persistence). Tests in `lib` explicitly pass `KryoSerializationProvider.INSTANCE`.

**Batched journal:** Wrap `RedisMessageJournal` in `com.cajunsystems.persistence.lmdb.SimpleBatchedMessageJournal` (already in cajun-persistence, same module).

**Test tag exclusion pattern:** `cajun-cluster/build.gradle` uses `excludeTags 'performance', 'requires-etcd'` — apply same pattern for `requires-redis` in `cajun-persistence/build.gradle` and `lib/build.gradle`.

---

## Tasks

### Task 1 — Implement RedisMessageJournal

Create `cajun-persistence/src/main/java/com/cajunsystems/persistence/redis/RedisMessageJournal.java`:

```java
package com.cajunsystems.persistence.redis;

import com.cajunsystems.persistence.JournalEntry;
import com.cajunsystems.persistence.MessageJournal;
import com.cajunsystems.serialization.SerializationProvider;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class RedisMessageJournal<M> implements MessageJournal<M> {

    private static final String APPEND_SCRIPT =
        "local seq = redis.call('INCR', KEYS[2])\n" +
        "redis.call('HSET', KEYS[1], tostring(seq), ARGV[1])\n" +
        "return seq";

    private final StatefulRedisConnection<String, byte[]> connection;
    private final RedisAsyncCommands<String, byte[]> async;
    private final SerializationProvider serializer;
    private final String keyPrefix;

    public RedisMessageJournal(StatefulRedisConnection<String, byte[]> connection,
                               String keyPrefix,
                               SerializationProvider serializer) {
        this.connection = connection;
        this.async = connection.async();
        this.keyPrefix = keyPrefix;
        this.serializer = serializer;
    }

    private String journalKey(String actorId) {
        return keyPrefix + ":journal:{" + sanitize(actorId) + "}";
    }

    private String seqKey(String actorId) {
        return keyPrefix + ":journal:{" + sanitize(actorId) + "}:seq";
    }

    private static String sanitize(String actorId) {
        return actorId.replace(':', '_');
    }

    @Override
    public CompletableFuture<Long> append(String actorId, M message) {
        byte[] bytes = serializer.serialize(message);
        String[] keys = {journalKey(actorId), seqKey(actorId)};
        RedisFuture<Long> future = async.eval(APPEND_SCRIPT, ScriptOutputType.INTEGER, keys, bytes);
        return future.toCompletableFuture();
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<List<JournalEntry<M>>> readFrom(String actorId, long fromSequenceNumber) {
        return async.hgetall(journalKey(actorId))
            .toCompletableFuture()
            .thenApply(map -> {
                return map.entrySet().stream()
                    .map(e -> {
                        long seq = Long.parseLong(e.getKey());
                        return Map.entry(seq, e.getValue());
                    })
                    .filter(e -> e.getKey() >= fromSequenceNumber)
                    .sorted(Map.Entry.comparingByKey())
                    .map(e -> {
                        M msg = (M) serializer.deserialize(e.getValue(), Object.class);
                        return new JournalEntry<>(e.getKey(), actorId, msg);
                    })
                    .collect(Collectors.toList());
            });
    }

    @Override
    public CompletableFuture<Void> truncateBefore(String actorId, long upToSequenceNumber) {
        return async.hkeys(journalKey(actorId))
            .toCompletableFuture()
            .thenCompose(fields -> {
                String[] toDelete = fields.stream()
                    .filter(f -> Long.parseLong(f) < upToSequenceNumber)
                    .toArray(String[]::new);
                if (toDelete.length == 0) {
                    return CompletableFuture.completedFuture(null);
                }
                return async.hdel(journalKey(actorId), toDelete)
                    .toCompletableFuture()
                    .thenApply(n -> null);
            });
    }

    @Override
    public CompletableFuture<Long> getHighestSequenceNumber(String actorId) {
        return async.get(seqKey(actorId))
            .toCompletableFuture()
            .thenApply(val -> val == null ? -1L : Long.parseLong(new String(val)));
    }

    @Override
    public void close() {
        // no-op: connection lifecycle managed by RedisPersistenceProvider
    }
}
```

**Important notes:**
- `seqKey` stores a long as bytes (via `ByteArrayCodec`). When reading with `async.get(seqKey)`, the result is `byte[]` — parse via `new String(val)`. OR use a separate `StringCodec` connection for the counter. **Simpler alternative**: use the same `byte[]` value connection and store the counter as UTF-8 decimal digits — Redis `INCR` works on any numeric string value regardless of how it was stored. Test with `redis-cli INCR key` to verify.
- Actually: With `RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE)`, `GET seqKey` returns `byte[]`. Parse: `Long.parseLong(new String(val, StandardCharsets.UTF_8))`.
- `async.eval(...)` signature: `eval(String script, ScriptOutputType type, K[] keys, V... values)` where `V = byte[]` — pass `bytes` as the vararg.

Compile after writing:
```bash
cd /Users/pradeep.samuel/cajun && ./gradlew :cajun-persistence:compileJava 2>&1 | tail -30
```

Fix any compile errors. Common issues:
- `RedisFuture.toCompletableFuture()` — available in Lettuce 6.x via `toCompletableFuture()`
- `async.hgetall` returns `RedisFuture<Map<K,V>>` — chain with `.toCompletableFuture()`
- `async.hkeys` returns `RedisFuture<List<K>>` — similarly

Commit:
```bash
git add cajun-persistence/src/main/java/com/cajunsystems/persistence/redis/RedisMessageJournal.java
git commit -m "feat(25-1): implement RedisMessageJournal with Lua atomic append

Stores serialized message bytes in Redis Hash (field=seqNum, value=bytes).
Counter in separate String key; atomic INCR+HSET via Lua script.
readFrom/truncateBefore use HGETALL/HKEYS with Java-side filtering.
Hash tags force key co-location in Redis Cluster mode.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

### Task 2 — Implement RedisSnapshotStore

Create `cajun-persistence/src/main/java/com/cajunsystems/persistence/redis/RedisSnapshotStore.java`:

```java
package com.cajunsystems.persistence.redis;

import com.cajunsystems.persistence.SnapshotEntry;
import com.cajunsystems.persistence.SnapshotStore;
import com.cajunsystems.serialization.SerializationProvider;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class RedisSnapshotStore<S> implements SnapshotStore<S> {

    private final StatefulRedisConnection<String, byte[]> connection;
    private final RedisAsyncCommands<String, byte[]> async;
    private final SerializationProvider serializer;
    private final String keyPrefix;

    public RedisSnapshotStore(StatefulRedisConnection<String, byte[]> connection,
                              String keyPrefix,
                              SerializationProvider serializer) {
        this.connection = connection;
        this.async = connection.async();
        this.keyPrefix = keyPrefix;
        this.serializer = serializer;
    }

    private String snapshotKey(String actorId) {
        return keyPrefix + ":snapshot:{" + sanitize(actorId) + "}";
    }

    private static String sanitize(String actorId) {
        return actorId.replace(':', '_');
    }

    @Override
    public CompletableFuture<Void> saveSnapshot(String actorId, S state, long sequenceNumber) {
        SnapshotEntry<S> entry = new SnapshotEntry<>(actorId, state, sequenceNumber);
        byte[] bytes = serializer.serialize(entry);
        return async.set(snapshotKey(actorId), bytes)
            .toCompletableFuture()
            .thenApply(r -> null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<Optional<SnapshotEntry<S>>> getLatestSnapshot(String actorId) {
        return async.get(snapshotKey(actorId))
            .toCompletableFuture()
            .thenApply(bytes -> {
                if (bytes == null) return Optional.empty();
                SnapshotEntry<S> entry = (SnapshotEntry<S>) serializer.deserialize(bytes, SnapshotEntry.class);
                return Optional.of(entry);
            });
    }

    @Override
    public CompletableFuture<Void> deleteSnapshots(String actorId) {
        return async.del(snapshotKey(actorId))
            .toCompletableFuture()
            .thenApply(n -> null);
    }

    @Override
    public void close() {
        // no-op: connection lifecycle managed by RedisPersistenceProvider
    }

    @Override
    public boolean isHealthy() {
        try {
            String result = connection.sync().ping();
            return "PONG".equals(result);
        } catch (Exception e) {
            return false;
        }
    }
}
```

Compile and fix errors, then commit:
```bash
git add cajun-persistence/src/main/java/com/cajunsystems/persistence/redis/RedisSnapshotStore.java
git commit -m "feat(25-1): implement RedisSnapshotStore with SET/GET/DEL and PING health check

Stores serialized SnapshotEntry (with seqNum) as a Redis String per actor.
isHealthy() uses synchronous PING — returns false on any exception.
Connection lifecycle owned by RedisPersistenceProvider.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

### Task 3 — Implement RedisPersistenceProvider

Create `cajun-persistence/src/main/java/com/cajunsystems/persistence/redis/RedisPersistenceProvider.java`:

```java
package com.cajunsystems.persistence.redis;

import com.cajunsystems.persistence.*;
import com.cajunsystems.persistence.lmdb.SimpleBatchedMessageJournal;
import com.cajunsystems.serialization.JavaSerializationProvider;
import com.cajunsystems.serialization.SerializationProvider;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;

public class RedisPersistenceProvider implements PersistenceProvider {

    private static final String DEFAULT_PREFIX = "cajun";
    private static final int DEFAULT_MAX_BATCH_SIZE = 100;
    private static final long DEFAULT_MAX_BATCH_DELAY_MS = 50;

    private final RedisClient client;
    private final StatefulRedisConnection<String, byte[]> connection;
    private final String keyPrefix;
    private final SerializationProvider serializer;

    public RedisPersistenceProvider(String redisUri) {
        this(redisUri, DEFAULT_PREFIX, JavaSerializationProvider.INSTANCE);
    }

    public RedisPersistenceProvider(String redisUri, String keyPrefix) {
        this(redisUri, keyPrefix, JavaSerializationProvider.INSTANCE);
    }

    public RedisPersistenceProvider(String redisUri, String keyPrefix, SerializationProvider serializer) {
        this.keyPrefix = keyPrefix;
        this.serializer = serializer;
        this.client = RedisClient.create(redisUri);
        this.connection = client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
    }

    @Override
    public <M> MessageJournal<M> createMessageJournal() {
        throw new UnsupportedOperationException(
            "Redis journal requires actorId for key namespace. Use createMessageJournal(String actorId).");
    }

    @Override
    public <M> MessageJournal<M> createMessageJournal(String actorId) {
        return new RedisMessageJournal<>(connection, keyPrefix, serializer);
    }

    @Override
    public <M> BatchedMessageJournal<M> createBatchedMessageJournal() {
        throw new UnsupportedOperationException(
            "Redis batched journal requires actorId. Use createBatchedMessageJournal(String actorId).");
    }

    @Override
    public <M> BatchedMessageJournal<M> createBatchedMessageJournal(String actorId) {
        return new SimpleBatchedMessageJournal<>(
            createMessageJournal(actorId), DEFAULT_MAX_BATCH_SIZE, DEFAULT_MAX_BATCH_DELAY_MS);
    }

    @Override
    public <M> BatchedMessageJournal<M> createBatchedMessageJournal(
            String actorId, int maxBatchSize, long maxBatchDelayMs) {
        return new SimpleBatchedMessageJournal<>(
            createMessageJournal(actorId), maxBatchSize, maxBatchDelayMs);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <M extends java.io.Serializable> BatchedMessageJournal<M> createBatchedMessageJournalSerializable(
            String actorId, int maxBatchSize, long maxBatchDelayMs) {
        // Redis serialization does not require Serializable; delegate to generic overload
        MessageJournal<M> journal = createMessageJournal(actorId);
        return new SimpleBatchedMessageJournal<>(journal, maxBatchSize, maxBatchDelayMs);
    }

    @Override
    public <S> SnapshotStore<S> createSnapshotStore() {
        throw new UnsupportedOperationException(
            "Redis snapshot store requires actorId for key namespace. Use createSnapshotStore(String actorId).");
    }

    @Override
    public <S> SnapshotStore<S> createSnapshotStore(String actorId) {
        return new RedisSnapshotStore<>(connection, keyPrefix, serializer);
    }

    @Override
    public String getProviderName() {
        return "redis";
    }

    @Override
    public boolean isHealthy() {
        try {
            String result = connection.sync().ping();
            return "PONG".equals(result);
        } catch (Exception e) {
            return false;
        }
    }

    public void close() {
        connection.close();
        client.shutdown();
    }
}
```

Also add `excludeTags 'requires-redis'` to `cajun-persistence/build.gradle` and `lib/build.gradle`.

**Edit `cajun-persistence/build.gradle`** — update `tasks.named('test')`:
```groovy
tasks.named('test') {
    jvmArgs(['--enable-preview'])
    useJUnitPlatform {
        excludeTags 'performance', 'requires-redis'
    }
}
```

**Edit `lib/build.gradle`** — update `tasks.named('test')`:
```groovy
tasks.named('test') {
    jvmArgs(['--enable-preview'])
    useJUnitPlatform {
        excludeTags 'performance', 'requires-redis'
    }
}
```

Compile and verify:
```bash
cd /Users/pradeep.samuel/cajun && ./gradlew :cajun-persistence:compileJava :lib:compileJava 2>&1 | tail -30
```

Commit:
```bash
git add cajun-persistence/src/main/java/com/cajunsystems/persistence/redis/RedisPersistenceProvider.java
git add cajun-persistence/build.gradle lib/build.gradle
git commit -m "feat(25-1): implement RedisPersistenceProvider; exclude requires-redis tag from default test runs

Factory creates RedisMessageJournal and RedisSnapshotStore from a shared Lettuce
connection. Batched journal wraps RedisMessageJournal in SimpleBatchedMessageJournal.
Excludes 'requires-redis' tag in cajun-persistence and lib test tasks.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

### Task 4 — Unit Tests (Mocked Lettuce)

Create `cajun-persistence/src/test/java/com/cajunsystems/persistence/redis/RedisMessageJournalTest.java`.

Use Mockito to mock `StatefulRedisConnection<String, byte[]>` and `RedisAsyncCommands<String, byte[]>`.

**Test setup:**
```java
@ExtendWith(MockitoExtension.class)
class RedisMessageJournalTest {
    @Mock StatefulRedisConnection<String, byte[]> connection;
    @Mock RedisAsyncCommands<String, byte[]> async;
    
    private RedisMessageJournal<String> journal;
    private final SerializationProvider serializer = JavaSerializationProvider.INSTANCE;

    @BeforeEach
    void setUp() {
        when(connection.async()).thenReturn(async);
        journal = new RedisMessageJournal<>(connection, "cajun-test", serializer);
    }
}
```

**Tests to write:**

1. `append_callsLuaScriptWithCorrectKeysAndBytes()`:
   - Mock `async.eval(...)` to return a `CompletedRedisFuture<Long>(1L)`
   - Call `journal.append("actor-1", "hello")`
   - Verify `async.eval(...)` called with correct journalKey, seqKey, and serialized bytes
   - Assert returned future completes with 1L

2. `append_sanitizesColonsInActorId()`:
   - Call `journal.append("actor:sub:id", "msg")`
   - Verify key contains `actor_sub_id` not `actor:sub:id`

3. `readFrom_filtersAndSortsEntries()`:
   - Mock `async.hgetall(journalKey)` to return a map with entries seq=1,2,3,4,5
   - Call `journal.readFrom("actor-1", 3)`
   - Assert result contains entries with seqNums 3, 4, 5 in order
   - Assert messages deserialized correctly

4. `readFrom_returnsEmptyListWhenNoEntriesMatchFilter()`:
   - Mock `HGETALL` with entries 1,2 only
   - Call `readFrom("actor-1", 5)` — assert empty list

5. `truncateBefore_deletesFieldsBelowThreshold()`:
   - Mock `async.hkeys(journalKey)` to return ["1","2","3","4","5"]
   - Mock `async.hdel(journalKey, "1", "2")` to return `CompletedRedisFuture<Long>(2L)`
   - Call `journal.truncateBefore("actor-1", 3)`
   - Verify `hdel` called with fields "1" and "2" (not "3","4","5")

6. `truncateBefore_noOpWhenNoFieldsBelowThreshold()`:
   - Mock `hkeys` returns ["3","4","5"]
   - Call `truncateBefore("actor-1", 3)`
   - Verify `hdel` never called

7. `getHighestSequenceNumber_returnsMinusOneWhenKeyAbsent()`:
   - Mock `async.get(seqKey)` returns `CompletedRedisFuture<byte[]>(null)`
   - Assert `getHighestSequenceNumber("actor-1")` returns -1L

8. `getHighestSequenceNumber_returnsParsedLong()`:
   - Mock `async.get(seqKey)` returns bytes of "42"
   - Assert returns 42L

Create `cajun-persistence/src/test/java/com/cajunsystems/persistence/redis/RedisSnapshotStoreTest.java`:

1. `saveSnapshot_serializesAndSetsEntry()`: verify `async.set(snapshotKey, bytes)` called with correct key and non-null bytes
2. `getLatestSnapshot_returnsEmptyWhenKeyAbsent()`: mock `async.get` returns null → assert `Optional.empty()`
3. `getLatestSnapshot_deserializesEntry()`: mock `async.get` returns serialized SnapshotEntry bytes → assert Optional contains entry with correct seqNum and state
4. `deleteSnapshots_callsDel()`: verify `async.del(snapshotKey)` called with correct key

**Helper for mocking Lettuce futures:** Use `io.lettuce.core.RedisFuture` mock or create a helper that returns a `CompletableFuture`-based `RedisFuture`. Lettuce's `RedisFuture` extends `CompletionStage`, so you can return `CompletableFuture.completedFuture(value)` cast to `RedisFuture` OR mock it directly:
```java
@SuppressWarnings("unchecked")
private <T> RedisFuture<T> completedFuture(T value) {
    RedisFuture<T> future = mock(RedisFuture.class);
    when(future.toCompletableFuture()).thenReturn(CompletableFuture.completedFuture(value));
    return future;
}
```

Run unit tests:
```bash
cd /Users/pradeep.samuel/cajun && ./gradlew :cajun-persistence:test 2>&1 | tail -30
```

Commit:
```bash
git add cajun-persistence/src/test/java/com/cajunsystems/persistence/redis/RedisMessageJournalTest.java
git add cajun-persistence/src/test/java/com/cajunsystems/persistence/redis/RedisSnapshotStoreTest.java
git commit -m "test(25-1): unit tests for RedisMessageJournal and RedisSnapshotStore with mocked Lettuce

Covers: Lua script invocation, actor ID sanitization, readFrom filtering,
truncateBefore selectivity, sequence counter absent/present, snapshot
round-trip, and DEL on deleteSnapshots.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

### Task 5 — Integration Tests

Integration tests require a running Redis instance. Tag all tests in this file with `@Tag("requires-redis")`.

Create `lib/src/test/java/com/cajunsystems/persistence/redis/RedisIntegrationTest.java`:

```java
@Tag("requires-redis")
class RedisIntegrationTest {
    private static final String REDIS_URI = "redis://localhost:6379";
    private RedisPersistenceProvider provider;
    private String actorId;

    @BeforeEach
    void setUp() {
        provider = new RedisPersistenceProvider(REDIS_URI, "cajun-test",
            KryoSerializationProvider.INSTANCE);
        actorId = "test-actor-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        // clean up journal and snapshot keys for this test actor
        provider.close();
    }
}
```

**Tests to write:**

1. `journal_appendAndReadFrom_roundTrip()`:
   - Create journal for `actorId`
   - Append 5 String messages ("msg-1" through "msg-5")
   - `readFrom(actorId, 1)` → assert 5 entries with seqNums 1–5
   - `readFrom(actorId, 3)` → assert 3 entries with seqNums 3–5
   - Assert messages match original strings

2. `journal_truncateBefore_removesOldEntries()`:
   - Append 5 messages, `truncateBefore(actorId, 3)` (removes seq 1,2)
   - `readFrom(actorId, 1)` → assert only entries with seqNums 3, 4, 5 remain
   - `getHighestSequenceNumber(actorId)` → still returns 5

3. `journal_getHighestSequenceNumber_returnMinusOneForEmpty()`:
   - Fresh actor ID with no messages
   - Assert `getHighestSequenceNumber(actorId)` returns -1

4. `snapshot_saveAndLoad_roundTrip()`:
   - Create snapshot store for `actorId`
   - `getLatestSnapshot(actorId)` → assert `Optional.empty()`
   - `saveSnapshot(actorId, "state-42", 42L)`
   - `getLatestSnapshot(actorId)` → assert present, state="state-42", seqNum=42

5. `snapshot_deleteSnapshots_clearsEntry()`:
   - Save a snapshot, verify present
   - `deleteSnapshots(actorId)` → `getLatestSnapshot` returns `Optional.empty()`

6. `snapshot_saveOverwritesPreviousSnapshot()`:
   - Save snapshot at seqNum=5, then save snapshot at seqNum=10
   - `getLatestSnapshot` returns seqNum=10 entry (not seqNum=5)

7. `provider_isHealthy_returnsTrueWhenRedisReachable()`:
   - Assert `provider.isHealthy()` returns true

Create `lib/src/test/java/com/cajunsystems/persistence/redis/RedisStatefulActorTest.java`:

```java
@Tag("requires-redis")
class RedisStatefulActorTest {
```

8. `statefulActor_recoversStateFromRedis()`:
   - Create `RedisPersistenceProvider` with unique key prefix + UUID actor ID
   - Spawn a `StatefulActor` (or `StatefulHandler`) with the Redis journal + snapshot store
   - Send 10 increment messages, wait for processing
   - Stop the actor system
   - Spawn a new actor system + new actor with SAME actorId, same Redis provider
   - Send 1 more message
   - Assert final count = 11 (proves recovery from Redis)

This test validates the core Phase 22 C1 bug is fixed — actor state survives "node" restart.

Run integration tests manually (requires Redis):
```bash
cd /Users/pradeep.samuel/cajun && ./gradlew :lib:test -Ptags="requires-redis" 2>&1 | tail -40
```
(This overrides the `excludeTags` filter — only for manual validation when Redis is available.)

Verify normal test suite still excludes them:
```bash
cd /Users/pradeep.samuel/cajun && ./gradlew test 2>&1 | tail -20
# Redis tests should NOT appear in output
```

Commit:
```bash
git add lib/src/test/java/com/cajunsystems/persistence/redis/RedisIntegrationTest.java
git add lib/src/test/java/com/cajunsystems/persistence/redis/RedisStatefulActorTest.java
git commit -m "test(25-1): integration tests for Redis journal, snapshot, and StatefulActor recovery

7 journal/snapshot round-trip tests + 1 StatefulActor state recovery test.
All tagged @Tag('requires-redis'), excluded from ./gradlew test by default.
Tests use UUID-based actor IDs for isolation; provider.close() cleans up.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Verification

1. **Build compiles**: `./gradlew :cajun-persistence:compileJava :lib:compileJava` — no errors
2. **Unit tests green**: `./gradlew :cajun-persistence:test` — all mocked tests pass (no Redis needed)
3. **Full suite unaffected**: `./gradlew test` — no new failures; requires-redis tests are excluded
4. **Integration tests** (optional, requires Redis): `./gradlew :lib:test -Ptags="requires-redis"` — all 8 tests pass

---

## Success Criteria

- `RedisMessageJournal<M>` in `cajun-persistence/src/main/java/com/cajunsystems/persistence/redis/`
- `RedisSnapshotStore<S>` in same package
- `RedisPersistenceProvider` in same package; `getProviderName() = "redis"`
- `createMessageJournal()` (no actorId) and `createSnapshotStore()` (no actorId) throw `UnsupportedOperationException`
- `cajun-persistence/build.gradle` and `lib/build.gradle` exclude `requires-redis` from default test run
- `./gradlew test` green (no regressions)
- At least 12 unit + integration test methods covering: append, readFrom, truncateBefore, getHighestSeqNum, saveSnapshot, getLatestSnapshot, deleteSnapshots, StatefulActor recovery

---

## Output

**New files:**
- `cajun-persistence/src/main/java/com/cajunsystems/persistence/redis/RedisMessageJournal.java`
- `cajun-persistence/src/main/java/com/cajunsystems/persistence/redis/RedisSnapshotStore.java`
- `cajun-persistence/src/main/java/com/cajunsystems/persistence/redis/RedisPersistenceProvider.java`
- `cajun-persistence/src/test/java/com/cajunsystems/persistence/redis/RedisMessageJournalTest.java`
- `cajun-persistence/src/test/java/com/cajunsystems/persistence/redis/RedisSnapshotStoreTest.java`
- `lib/src/test/java/com/cajunsystems/persistence/redis/RedisIntegrationTest.java`
- `lib/src/test/java/com/cajunsystems/persistence/redis/RedisStatefulActorTest.java`

**Modified files:**
- `cajun-persistence/build.gradle` — add `requires-redis` to excludeTags
- `lib/build.gradle` — add `requires-redis` to excludeTags

**Planning artifacts:**
- `.planning/phases/25-redis-persistence-provider/25-1-SUMMARY.md`
- `.planning/STATE.md` — Phase 25 ✅ Complete
- `.planning/ROADMAP.md` — Phase 25 struck through
