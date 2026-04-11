# Phase 26 — Cluster + Shared Persistence Integration

## Objective

Wire `RedisPersistenceProvider` into `ClusterActorSystem` so all `StatefulActor`s on a cluster node automatically use Redis as their shared backing store. Add a persistence health check at startup. Write a unit test verifying `PidRehydrator` correctness in cluster context. Fix the disabled regression test `StatefulActorClusterStateTest` — prove that a `StatefulActor` on node A recovers its full state on node B after node A disappears. Add a throughput benchmark comparing Redis, file, and LMDB persistence.

---

## Execution Context

- Branch: `feature/roux-effect-integration`
- Java 21, `--enable-preview`; run tests: `./gradlew test`
- Module layout:
  - `lib/src/main/java/com/cajunsystems/cluster/ClusterActorSystem.java` — extend with `withPersistenceProvider()`
  - `lib/src/test/java/com/cajunsystems/cluster/StatefulActorClusterStateTest.java` — rewrite (currently `@Disabled`)
  - `cajun-persistence/src/main/java/com/cajunsystems/persistence/redis/RedisPersistenceProvider.java` — exists from Phase 25
  - `lib/src/main/java/com/cajunsystems/serialization/KryoSerializationProvider.java` — exists from Phase 23

---

## Context — Architecture Decisions

**`StatefulActor` persistence wiring:**
- `StatefulActor` uses `PersistenceProviderRegistry.getInstance().getDefaultProvider()` when not given explicit constructor-injected stores
- Alternatively, the constructor `StatefulActor(system, actorId, initialState, journal, snapshotStore)` accepts explicit stores — used directly in `CounterActor`
- For the cross-node test (Task 3), the fix is to pass SHARED Redis-backed stores to BOTH system1 and system2's `CounterActor` constructors — no change to `ClusterActorSystem` required for the test to work

**`ClusterActorSystem.withPersistenceProvider()` (Task 1):**
- Fluent setter that stores a `PersistenceProvider` reference
- Called in `start()` to register provider in `PersistenceProviderRegistry` and set as default
- No-op if not set (backward compat)
- This allows `StatefulActors` registered via `system.register()` (not constructor injection) to automatically use the cluster-wide persistence provider

**`PidRehydrator` in cluster context (Task 2):**
- `PidRehydrator.rehydrate(state, system)` updates null `actorSystem` fields in `Pid` objects
- When state is recovered on node B, the rehydrated `Pid.system()` becomes node B's `ClusterActorSystem`
- Node B's `ClusterActorSystem.routeMessage()` routes messages to the correct node (local or remote via metadata store)
- Behavior is correct as-is — the test just verifies it explicitly

**Cross-node state recovery fix (Task 3):**
- Root cause of bug: `system1` uses `MockBatchedMessageJournal` + `MockSnapshotStore` (in-memory, node-local)
- Fix: both `system1` and `system2` pass stores from the SAME `RedisPersistenceProvider` instance (or two instances pointing to same Redis URI + same `actorId`)
- `CounterActor` on system2 calls `StatefulActor.initializeState()` which loads from Redis journal → replays 5 messages → count=5 → 1 more increment → count=6 ✓
- Snapshotting: with only 5 messages and a short test, no snapshot taken (threshold=100 messages or 15s). Recovery is via full journal replay.
- `CounterMessage` and `CounterState` already implement `Serializable` — use `KryoSerializationProvider.INSTANCE` (no requirement on Serializable for Kryo)
- Key prefix: use `"cajun-test-" + UUID.randomUUID()` so different test runs don't interfere
- Actor ID: use UUID (not "counter-1") to avoid cross-run journal accumulation

**Benchmark (Task 4):**
- Simple throughput measurement for journal append + state recovery
- Tags: `@Tag("performance")` — excluded from `./gradlew test` by default
- Append N=500 messages sequentially, record elapsed time → print throughput (msg/s)
- Recovery: replay N messages from seq=1, record elapsed time
- Redis benchmark additionally tagged `@Tag("requires-redis")`
- Print results via `System.out` — no pass/fail assertion on throughput (values are environment-dependent)

---

## Tasks

### Task 1 — Add withPersistenceProvider() to ClusterActorSystem

Edit `lib/src/main/java/com/cajunsystems/cluster/ClusterActorSystem.java`:

**1a. Add import and field:**
```java
import com.cajunsystems.persistence.PersistenceProvider;
import com.cajunsystems.persistence.PersistenceProviderRegistry;

// After the existing fields:
private PersistenceProvider persistenceProvider; // null = use existing registry default
```

**1b. Add fluent setter** (after `withDeliveryGuarantee()`):
```java
/**
 * Configures a shared persistence provider for this cluster node.
 * The provider is registered in {@link PersistenceProviderRegistry} and set as the
 * default when {@link #start()} is called. All {@link com.cajunsystems.StatefulActor}s
 * spawned on this node will use this provider for journaling and snapshots.
 *
 * @param provider The persistence provider to use (e.g. RedisPersistenceProvider)
 * @return This actor system instance for method chaining
 */
public ClusterActorSystem withPersistenceProvider(PersistenceProvider provider) {
    this.persistenceProvider = provider;
    return this;
}
```

**1c. Update `start()` — add persistence setup after `registerNode()`:**

Current `start()` ends with:
```java
return metadataStore.connect()
        .thenCompose(v -> messagingSystem.start())
        .thenCompose(v -> registerNode())
        .thenRun(() -> {
            // Start heartbeat ...
            // Start leader election ...
            // Watch for node changes ...
        });
```

Change the `thenRun` section to also call `setupPersistence()`:
```java
return metadataStore.connect()
        .thenCompose(v -> messagingSystem.start())
        .thenCompose(v -> registerNode())
        .thenRun(() -> {
            setupPersistence();

            // Start heartbeat
            scheduler.scheduleAtFixedRate(
                    this::sendHeartbeat, 0, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);

            // Start leader election
            scheduler.scheduleAtFixedRate(
                    this::tryBecomeLeader, 1, HEARTBEAT_INTERVAL_SECONDS * 3, TimeUnit.SECONDS);

            // Watch for node changes
            metadataStore.watch(NODE_PREFIX, new MetadataStore.KeyWatcher() {
                // ... existing watcher code unchanged ...
            });
        });
```

**1d. Add `setupPersistence()` private method:**
```java
/**
 * Registers and activates the configured persistence provider, if any.
 * Logs a warning if the provider reports unhealthy.
 */
private void setupPersistence() {
    if (persistenceProvider == null) {
        return;
    }
    PersistenceProviderRegistry registry = PersistenceProviderRegistry.getInstance();
    registry.registerProvider(persistenceProvider);
    registry.setDefaultProvider(persistenceProvider.getProviderName());
    if (!persistenceProvider.isHealthy()) {
        logger.warn(
            "Persistence provider '{}' reports unhealthy at cluster startup — " +
            "StatefulActors on this node may fail to recover state",
            persistenceProvider.getProviderName());
    } else {
        logger.info("Persistence provider '{}' configured and healthy", 
            persistenceProvider.getProviderName());
    }
}
```

Compile: `./gradlew :lib:compileJava 2>&1 | tail -30`

Fix any compile errors (likely just missing imports). Run tests to confirm nothing broken: `./gradlew test 2>&1 | tail -30`

Commit:
```bash
git add lib/src/main/java/com/cajunsystems/cluster/ClusterActorSystem.java
git commit -m "feat(26-1): add withPersistenceProvider() to ClusterActorSystem with startup health check

Registers the given provider in PersistenceProviderRegistry and sets it as
the default during start(), so all StatefulActors on the node use shared
persistence. Logs WARN if provider reports unhealthy at startup.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

### Task 2 — PidRehydrator Cross-Node Unit Test

Create `lib/src/test/java/com/cajunsystems/persistence/PidRehydratorClusterTest.java`.

This is a pure unit test — no Redis, no etcd, no cluster needed. Uses `InMemoryMetadataStore` and `InMemoryMessagingSystem` if needed, or just mocks.

```java
package com.cajunsystems.persistence;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.Serializable;

import static org.junit.jupiter.api.Assertions.*;

class PidRehydratorClusterTest {

    private ActorSystem system;

    @BeforeEach
    void setUp() {
        system = new ActorSystem();
    }

    @AfterEach
    void tearDown() {
        system.shutdown();
    }
}
```

**Tests to write:**

1. `rehydrate_pid_with_null_system_gets_new_system()`:
   ```java
   Pid pid = new Pid("actor-1", null); // null system (as after deserialization)
   Pid result = PidRehydrator.rehydrate(pid, system);
   assertSame(system, result.system());
   assertEquals("actor-1", result.actorId());
   ```

2. `rehydrate_pid_with_existing_system_unchanged()`:
   ```java
   ActorSystem other = new ActorSystem();
   Pid pid = new Pid("actor-1", other);
   Pid result = PidRehydrator.rehydrate(pid, system);
   assertSame(other, result.system()); // not replaced — system was not null
   other.shutdown();
   ```

3. `rehydrate_record_state_containing_pid()`:
   ```java
   // State record with a Pid field
   record ActorRef(String name, Pid pid) implements Serializable {}
   Pid nullPid = new Pid("target", null);
   ActorRef state = new ActorRef("test", nullPid);
   ActorRef result = PidRehydrator.rehydrate(state, system);
   assertSame(system, result.pid().system());
   assertEquals("target", result.pid().actorId());
   ```

4. `rehydrate_null_state_returns_null()`:
   ```java
   assertNull(PidRehydrator.rehydrate(null, system));
   ```

5. `rehydrate_state_with_no_pids_returned_unchanged()`:
   ```java
   record SimpleState(int count, String label) implements Serializable {}
   SimpleState state = new SimpleState(42, "hello");
   SimpleState result = PidRehydrator.rehydrate(state, system);
   assertEquals(42, result.count());
   assertEquals("hello", result.label());
   ```

6. `rehydrate_nested_records_with_multiple_pids()`:
   ```java
   record Inner(Pid pid) implements Serializable {}
   record Outer(Inner inner, Pid direct) implements Serializable {}
   Pid p1 = new Pid("a1", null);
   Pid p2 = new Pid("a2", null);
   Outer state = new Outer(new Inner(p1), p2);
   Outer result = PidRehydrator.rehydrate(state, system);
   assertSame(system, result.inner().pid().system());
   assertSame(system, result.direct().system());
   ```

Note: `Pid` constructor may need to be checked — look at `Pid.java` for available constructors. If `new Pid("actorId", null)` doesn't compile, check the actual constructor signature.

After writing tests, run: `./gradlew test --tests "*PidRehydratorCluster*" 2>&1 | tail -30`

Commit:
```bash
git add lib/src/test/java/com/cajunsystems/persistence/PidRehydratorClusterTest.java
git commit -m "test(26-1): unit tests for PidRehydrator in cluster/recovery context

Verifies null-system replacement, existing-system preservation, record
traversal, nested Pid rehydration, and null-state handling.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

### Task 3 — Fix StatefulActorClusterStateTest (requires-redis)

Rewrite `lib/src/test/java/com/cajunsystems/cluster/StatefulActorClusterStateTest.java`.

**Changes:**
1. Remove `@Disabled` from the test class
2. Add `@Tag("requires-redis")` to the test class
3. Add the `RedisPersistenceProvider` and `KryoSerializationProvider` imports
4. Rewrite the test `testStatefulActorStateLostOnClusterReassignment` → rename to `testStatefulActorStateRecoveredViaRedis`
5. Use UUID actor ID instead of `"counter-1"`
6. Use `RedisPersistenceProvider` for both `journal1`/`snapshot1` and `journal2`/`snapshot2` — pointing to the same Redis
7. Add `@AfterEach` cleanup: truncate journal + delete snapshot

**New test structure:**

```java
@Tag("requires-redis")
public class StatefulActorClusterStateTest {

    private static final String REDIS_URI = "redis://localhost:6379";
    private static final String KEY_PREFIX = "cajun-cluster-test";

    private ClusterActorSystem system1;
    private ClusterActorSystem system2;
    private RedisPersistenceProvider redisProvider;
    private String actorId;

    // ... (keep CounterState, CounterMessage, CounterActor, ReplyCollector,
    //      InMemoryMetadataStore, InMemoryMessagingSystem unchanged) ...

    @BeforeEach
    void setUpRedis() {
        actorId = "counter-" + UUID.randomUUID();
        redisProvider = new RedisPersistenceProvider(REDIS_URI, KEY_PREFIX,
            KryoSerializationProvider.INSTANCE);
    }

    @AfterEach
    void tearDown() {
        if (system1 != null) {
            try { system1.stop().get(5, TimeUnit.SECONDS); } catch (Exception ignored) {}
            system1 = null;
        }
        if (system2 != null) {
            try { system2.stop().get(5, TimeUnit.SECONDS); } catch (Exception ignored) {}
            system2 = null;
        }
        if (redisProvider != null) {
            // Clean up Redis keys for this test run
            try {
                MessageJournal<CounterMessage> j = redisProvider.createMessageJournal(actorId);
                j.truncateBefore(actorId, Long.MAX_VALUE).get(5, TimeUnit.SECONDS);
                SnapshotStore<CounterState> s = redisProvider.createSnapshotStore(actorId);
                s.deleteSnapshots(actorId).get(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
            redisProvider.close();
            redisProvider = null;
        }
    }

    @Test
    void testStatefulActorStateRecoveredViaRedis() throws Exception {
        // Shared metadata store
        InMemoryMetadataStore sharedMetadataStore = new InMemoryMetadataStore();

        // ---- Step 1: Start system1 ----
        InMemoryMessagingSystem ms1 = new InMemoryMessagingSystem("system1");
        system1 = new ClusterActorSystem("system1", sharedMetadataStore, ms1);
        system1.start().get(5, TimeUnit.SECONDS);
        Thread.sleep(500);

        // ---- Step 2: Register CounterActor on system1 with SHARED Redis storage ----
        BatchedMessageJournal<CounterMessage> journal1 =
            redisProvider.createBatchedMessageJournal(actorId);
        SnapshotStore<CounterState> snapshot1 =
            redisProvider.createSnapshotStore(actorId);
        CounterActor counter1 = new CounterActor(
            system1, actorId, new CounterState(0), journal1, snapshot1);
        counter1.start();
        system1.registerActor(counter1);
        sharedMetadataStore.put("cajun/actor/" + actorId, "system1").get(1, TimeUnit.SECONDS);
        assertTrue(counter1.waitForInit(3000), "Counter actor should initialize");

        // ---- Step 3: Send 5 increments and verify count == 5 ----
        for (int i = 0; i < 5; i++) {
            counter1.tell(new CounterMessage.Increment());
        }
        Thread.sleep(500);

        CountDownLatch latch1 = new CountDownLatch(1);
        AtomicInteger countFromSystem1 = new AtomicInteger(-1);
        ReplyCollector collector1 = new ReplyCollector(
            system1, "collector-" + UUID.randomUUID(), latch1, countFromSystem1);
        collector1.start();
        system1.registerActor(collector1);
        counter1.tell(new CounterMessage.GetCount(collector1.self()));
        assertTrue(latch1.await(3, TimeUnit.SECONDS));
        assertEquals(5, countFromSystem1.get(), "count should be 5 after 5 increments");

        // ---- Step 4: Stop system1 (simulate node failure) ----
        system1.stop().get(5, TimeUnit.SECONDS);
        system1 = null;
        Thread.sleep(500);

        // ---- Step 5: Start system2 ----
        InMemoryMessagingSystem ms2 = new InMemoryMessagingSystem("system2");
        system2 = new ClusterActorSystem("system2", sharedMetadataStore, ms2);
        system2.start().get(5, TimeUnit.SECONDS);
        Thread.sleep(1000);

        // ---- Step 6: Register same actor on system2 with SAME Redis backend ----
        // Both journal2 and snapshot2 connect to same Redis keys as journal1/snapshot1
        BatchedMessageJournal<CounterMessage> journal2 =
            redisProvider.createBatchedMessageJournal(actorId);
        SnapshotStore<CounterState> snapshot2 =
            redisProvider.createSnapshotStore(actorId);
        CounterActor counter2 = new CounterActor(
            system2, actorId, new CounterState(0), journal2, snapshot2);
        counter2.start();
        system2.registerActor(counter2);
        // Recovery: StatefulActor.initializeState() replays 5 messages from Redis → count=5
        assertTrue(counter2.waitForInit(5000), "Counter actor on system2 should initialize with recovered state");

        // ---- Step 7: Send 1 more increment ----
        counter2.tell(new CounterMessage.Increment());
        Thread.sleep(500);

        // ---- Step 8: Assert count == 6 (5 recovered + 1 new) ----
        CountDownLatch latch2 = new CountDownLatch(1);
        AtomicInteger countFromSystem2 = new AtomicInteger(-1);
        ReplyCollector collector2 = new ReplyCollector(
            system2, "collector-" + UUID.randomUUID(), latch2, countFromSystem2);
        collector2.start();
        system2.registerActor(collector2);
        counter2.tell(new CounterMessage.GetCount(collector2.self()));
        assertTrue(latch2.await(3, TimeUnit.SECONDS));
        assertEquals(6, countFromSystem2.get(),
            "State recovered from Redis: 5 from system1 + 1 from system2 = 6");
    }
}
```

**Important implementation notes:**
- Keep the existing `@Disabled` test as a separate test that remains disabled to document the bug — OR simply delete the old test and replace with the new one. Prefer replacement (one test per scenario).
- The `registerActor(counter)` call pattern is used — check that `ActorSystem` or `ClusterActorSystem` has this method; if not, look at the existing test to see which method is used.
- `redisProvider.createBatchedMessageJournal(actorId)` and `createSnapshotStore(actorId)` call `RedisMessageJournal` and `RedisSnapshotStore` constructors passing the same shared Lettuce connection → both system1 and system2 look at the same Redis hash keys for this actorId.
- `KryoSerializationProvider.INSTANCE` can serialize `CounterMessage` records (which implement `Serializable` — Kryo handles them fine)
- `MessageJournal<CounterMessage>` is used for cleanup in `@AfterEach` — import it properly

Compile test: `./gradlew :lib:compileTestJava 2>&1 | tail -30`

Fix any compile errors. The test will NOT run in `./gradlew test` (excluded by `requires-redis` tag). Run full suite: `./gradlew test 2>&1 | tail -30` — verify no regressions.

Commit:
```bash
git add lib/src/test/java/com/cajunsystems/cluster/StatefulActorClusterStateTest.java
git commit -m "fix(26-1): fix StatefulActorClusterStateTest — state recovered via Redis cross-node

Replaces MockBatchedMessageJournal/MockSnapshotStore with RedisPersistenceProvider
so both system1 and system2 share the same Redis-backed journal and snapshot.
StatefulActor on system2 replays 5 messages from Redis and recovers count=5;
1 more increment → count=6. Test tagged requires-redis, UUID actor IDs prevent
cross-run interference.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

### Task 4 — Persistence Throughput Benchmark

Create `lib/src/test/java/com/cajunsystems/persistence/PersistenceBenchmarkTest.java`.

Tag with `@Tag("performance")`. Tests with Redis also get `@Tag("requires-redis")` — but since `./gradlew test` excludes BOTH `performance` and `requires-redis`, a test with both tags is still excluded.

```java
@Tag("performance")
class PersistenceBenchmarkTest {

    private static final int N = 500;
    private Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("cajun-bench-");
    }

    @AfterEach
    void tearDown() throws IOException {
        // Clean up temp files
        Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }
}
```

**Tests to write:**

1. `benchmark_file_journal_append_N_messages()`:
   ```java
   @Test
   void benchmark_file_journal_append_N_messages() throws Exception {
       String actorId = "bench-" + UUID.randomUUID();
       FileMessageJournal<String> journal = new FileMessageJournal<>(
           tempDir.resolve("journal"), KryoSerializationProvider.INSTANCE);
       
       long start = System.nanoTime();
       for (int i = 0; i < N; i++) {
           journal.append(actorId, "message-" + i).get();
       }
       long elapsed = System.nanoTime() - start;
       
       double throughput = N * 1_000_000_000.0 / elapsed;
       System.out.printf("File journal: %d messages in %.1f ms (%.0f msg/s)%n",
           N, elapsed / 1_000_000.0, throughput);
       journal.close();
       
       // Verify correctness: all messages written
       assertEquals(N, journal.readFrom(actorId, 1).get().size());
   }
   ```

2. `benchmark_file_journal_recovery_N_messages()`:
   ```java
   @Test
   void benchmark_file_journal_recovery_N_messages() throws Exception {
       String actorId = "bench-" + UUID.randomUUID();
       FileMessageJournal<String> journal = new FileMessageJournal<>(
           tempDir.resolve("journal-recovery"), KryoSerializationProvider.INSTANCE);
       
       // Setup: append N messages
       for (int i = 0; i < N; i++) {
           journal.append(actorId, "message-" + i).get();
       }
       
       // Measure recovery (full replay from seq=1)
       long start = System.nanoTime();
       List<JournalEntry<String>> entries = journal.readFrom(actorId, 1).get();
       long elapsed = System.nanoTime() - start;
       
       System.out.printf("File journal recovery: %d messages in %.1f ms%n",
           entries.size(), elapsed / 1_000_000.0);
       assertEquals(N, entries.size());
       journal.close();
   }
   ```

3. `benchmark_redis_journal_append_N_messages()`:
   ```java
   @Test
   @Tag("requires-redis")
   void benchmark_redis_journal_append_N_messages() throws Exception {
       String actorId = "bench-" + UUID.randomUUID();
       RedisPersistenceProvider provider = new RedisPersistenceProvider(
           "redis://localhost:6379", "cajun-bench", KryoSerializationProvider.INSTANCE);
       MessageJournal<String> journal = provider.createMessageJournal(actorId);
       
       try {
           long start = System.nanoTime();
           for (int i = 0; i < N; i++) {
               journal.append(actorId, "message-" + i).get();
           }
           long elapsed = System.nanoTime() - start;
           
           double throughput = N * 1_000_000_000.0 / elapsed;
           System.out.printf("Redis journal: %d messages in %.1f ms (%.0f msg/s)%n",
               N, elapsed / 1_000_000.0, throughput);
           
           // Verify
           assertEquals(N, journal.readFrom(actorId, 1).get().size());
       } finally {
           journal.truncateBefore(actorId, Long.MAX_VALUE).get(5, TimeUnit.SECONDS);
           journal.close();
           provider.close();
       }
   }
   ```

4. `benchmark_redis_journal_recovery_N_messages()`:
   - Same pattern as file recovery test but with Redis
   - Setup: append N messages
   - Measure: `readFrom(actorId, 1)` elapsed time
   - Also tagged `@Tag("requires-redis")`

The imports needed: `FileMessageJournal`, `JournalEntry`, `MessageJournal`, `RedisPersistenceProvider`, `KryoSerializationProvider`, standard Java I/O and JUnit 5.

After writing, compile: `./gradlew :lib:compileTestJava 2>&1 | tail -30`

Verify the benchmark tests are NOT included in the default run: `./gradlew test 2>&1 | grep -i bench` — should produce no output.

Commit:
```bash
git add lib/src/test/java/com/cajunsystems/persistence/PersistenceBenchmarkTest.java
git commit -m "test(26-1): persistence throughput benchmark — file vs Redis journal append and recovery

Measures message throughput and recovery latency for FileMessageJournal
and RedisMessageJournal over N=500 messages. Results printed to stdout.
Tagged performance (+ requires-redis for Redis tests) — excluded from default runs.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Verification

1. **Build compiles**: `./gradlew :lib:compileJava :lib:compileTestJava` — no errors
2. **Default test suite green**: `./gradlew test` — no regressions; `requires-redis` and `performance` tests excluded
3. **ClusterActorSystem API**: `withPersistenceProvider()` chainable; `setupPersistence()` called in `start()`
4. **PidRehydrator tests pass**: `./gradlew test --tests "*PidRehydratorCluster*"` — 6 tests green
5. **Cross-node recovery test**: `./gradlew :lib:test -Ptags="requires-redis"` — `testStatefulActorStateRecoveredViaRedis` passes (count=6) when Redis is running

---

## Success Criteria

- `ClusterActorSystem.withPersistenceProvider(PersistenceProvider)` fluent setter implemented
- `ClusterActorSystem.start()` registers provider in `PersistenceProviderRegistry` and logs health check result
- `PidRehydratorClusterTest` with 6 unit tests, all passing without Redis or etcd
- `StatefulActorClusterStateTest` no longer `@Disabled`; tagged `@Tag("requires-redis")`; `testStatefulActorStateRecoveredViaRedis` passes count=6 against live Redis
- `PersistenceBenchmarkTest` with 4 throughput tests (`@Tag("performance")`), excluded from default run
- `./gradlew test` green — no new failures

---

## Output

**Modified files:**
- `lib/src/main/java/com/cajunsystems/cluster/ClusterActorSystem.java` — `withPersistenceProvider()` + `setupPersistence()`
- `lib/src/test/java/com/cajunsystems/cluster/StatefulActorClusterStateTest.java` — rewritten, `@Disabled` removed

**New files:**
- `lib/src/test/java/com/cajunsystems/persistence/PidRehydratorClusterTest.java`
- `lib/src/test/java/com/cajunsystems/persistence/PersistenceBenchmarkTest.java`

**Planning artifacts:**
- `.planning/phases/26-cluster-shared-persistence-integration/26-1-SUMMARY.md`
- `.planning/STATE.md` — Phase 26 ✅ Complete
- `.planning/ROADMAP.md` — Phase 26 struck through
