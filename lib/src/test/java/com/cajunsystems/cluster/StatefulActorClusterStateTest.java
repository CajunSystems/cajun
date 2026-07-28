package com.cajunsystems.cluster;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.StatefulActor;
import com.cajunsystems.persistence.BatchedMessageJournal;
import com.cajunsystems.persistence.MessageJournal;
import com.cajunsystems.persistence.MockBatchedMessageJournal;
import com.cajunsystems.persistence.MockSnapshotStore;
import com.cajunsystems.persistence.SnapshotStore;
import com.cajunsystems.persistence.redis.RedisPersistenceProvider;
import com.cajunsystems.serialization.KryoSerializationProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Documents the state-loss-on-reassignment bug in the cluster module.
 *
 * <h2>Why This Test Fails</h2>
 * <p>
 * When a {@link StatefulActor} is running on {@code system1} and accumulates state,
 * that state is persisted in the node-local storage configured for {@code system1}
 * (either file-based or in-memory journal/snapshot store). When {@code system1} stops
 * (simulating a node failure), the cluster leader reassigns the actor to {@code system2}
 * by writing a new value to the metadata store key {@code cajun/actor/<actorId>}.
 * </p>
 * <p>
 * However, {@code system2} never receives or loads the journal/snapshot that was held by
 * {@code system1}. The persistence storage is node-local and not shared across nodes.
 * When the actor is instantiated on {@code system2}, it starts with the supplied
 * {@code initialState} (count = 0) rather than the last known state (count = 5).
 * </p>
 * <p>
 * The root cause is the absence of a shared persistence layer (e.g., Redis or another
 * networked store). Until Phase 26 (Cluster + Shared Persistence Integration) adds a
 * cross-node persistence provider, any StatefulActor reassignment will silently lose
 * all state that had not been flushed to a shared medium.
 * </p>
 *
 * <h2>Affected Code Paths</h2>
 * <ul>
 *   <li>{@code ClusterActorSystem#assignActorToNode()} — writes only the metadata key,
 *       never instantiates the actor on the target node or migrates its state
 *       (ClusterActorSystem.java, line ~508)</li>
 *   <li>{@code StatefulActor} constructors — always recover from the local
 *       {@code PersistenceProviderRegistry} default provider (node-local filesystem or
 *       test-local in-memory store)</li>
 *   <li>{@code FileMessageJournal} / {@code FileSnapshotStore} — write to local disk;
 *       no cross-node access possible</li>
 * </ul>
 */
@Tag("requires-redis")
public class StatefulActorClusterStateTest {

    // -------------------------------------------------------------------------
    // State type
    // -------------------------------------------------------------------------

    /** Immutable counter state held by the CounterActor. */
    public record CounterState(int count) implements Serializable {
        private static final long serialVersionUID = 1L;
    }

    // -------------------------------------------------------------------------
    // Message types
    // -------------------------------------------------------------------------

    /**
     * Sealed message interface for CounterActor.
     * Both variants implement {@code Serializable} so they can be persisted to
     * the message journal (required by {@link StatefulActor}).
     */
    public sealed interface CounterMessage extends Serializable
            permits CounterMessage.Increment, CounterMessage.GetCount {

        /** Increment the counter by 1. */
        record Increment() implements CounterMessage {
            private static final long serialVersionUID = 1L;
        }

        /**
         * Ask for the current count. The actor replies to {@code replyTo} with an
         * {@link Integer} equal to the current count.
         */
        record GetCount(Pid replyTo) implements CounterMessage {
            private static final long serialVersionUID = 1L;
        }
    }

    // -------------------------------------------------------------------------
    // Actor implementations
    // -------------------------------------------------------------------------

    /**
     * Simple stateful counter actor backed by a provided journal and snapshot store.
     *
     * <p>Accepts {@link CounterMessage.Increment} (updates state) and
     * {@link CounterMessage.GetCount} (replies without changing state).
     */
    public static class CounterActor
            extends StatefulActor<CounterState, CounterMessage> {

        private final CountDownLatch initLatch = new CountDownLatch(1);

        /**
         * Constructor used to inject node-local persistence stores.
         * Each node gets its own journal/snapshot store instance, which is the root
         * cause of state loss: there is no shared store between nodes.
         */
        public CounterActor(ActorSystem system,
                            String actorId,
                            CounterState initialState,
                            BatchedMessageJournal<CounterMessage> journal,
                            SnapshotStore<CounterState> snapshotStore) {
            super(system, actorId, initialState, journal, snapshotStore);
        }

        @Override
        protected CounterState processMessage(CounterState state, CounterMessage message) {
            if (state == null) {
                state = new CounterState(0);
            }
            return switch (message) {
                case CounterMessage.Increment ignored -> new CounterState(state.count() + 1);
                case CounterMessage.GetCount msg -> {
                    if (msg.replyTo() != null) {
                        msg.replyTo().tell(state.count());
                    }
                    yield state; // state unchanged
                }
            };
        }

        @Override
        protected CompletableFuture<Void> initializeState() {
            return super.initializeState().thenRun(initLatch::countDown);
        }

        /** Waits for the recovery/initialization phase to complete. */
        public boolean waitForInit(long timeoutMs) throws InterruptedException {
            return initLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * A minimal stateless actor used as the {@code replyTo} target for
     * {@link CounterMessage.GetCount} requests.
     */
    public static class ReplyCollector extends com.cajunsystems.Actor<Integer> {

        private final CountDownLatch latch;
        private final AtomicInteger result;

        public ReplyCollector(ActorSystem system, String actorId,
                              CountDownLatch latch, AtomicInteger result) {
            super(system, actorId);
            this.latch = latch;
            this.result = result;
        }

        @Override
        protected void receive(Integer message) {
            result.set(message);
            latch.countDown();
        }
    }

    // -------------------------------------------------------------------------
    // Shared in-memory cluster infrastructure for tests
    // -------------------------------------------------------------------------

    /** In-memory metadata store shared between both cluster nodes in a test. */
    static class InMemoryMetadataStore implements MetadataStore {

        private final ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Lock> locks = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, KeyWatcher> watchers = new ConcurrentHashMap<>();

        @Override
        public CompletableFuture<Void> put(String key, String value) {
            return CompletableFuture.runAsync(() -> {
                store.put(key, value);
                for (Map.Entry<String, KeyWatcher> e : watchers.entrySet()) {
                    if (key.startsWith(e.getKey())) {
                        e.getValue().onPut(key, value);
                    }
                }
            });
        }

        @Override
        public CompletableFuture<Optional<String>> get(String key) {
            return CompletableFuture.supplyAsync(() -> Optional.ofNullable(store.get(key)));
        }

        @Override
        public CompletableFuture<Void> delete(String key) {
            return CompletableFuture.runAsync(() -> {
                store.remove(key);
                for (Map.Entry<String, KeyWatcher> e : watchers.entrySet()) {
                    if (key.startsWith(e.getKey())) {
                        e.getValue().onDelete(key);
                    }
                }
            });
        }

        @Override
        public CompletableFuture<List<String>> listKeys(String prefix) {
            return CompletableFuture.supplyAsync(() ->
                    store.keySet().stream().filter(k -> k.startsWith(prefix)).toList());
        }

        @Override
        public CompletableFuture<Optional<Lock>> acquireLock(String lockName, long ttlSeconds) {
            return CompletableFuture.supplyAsync(() -> {
                if (locks.containsKey(lockName)) {
                    return Optional.empty();
                }
                InMemoryLock lock = new InMemoryLock(lockName);
                locks.put(lockName, lock);
                return Optional.of((Lock) lock);
            });
        }

        @Override
        public CompletableFuture<Long> watch(String key, KeyWatcher watcher) {
            return CompletableFuture.supplyAsync(() -> {
                long watchId = System.nanoTime();
                watchers.put(key, watcher);
                return watchId;
            });
        }

        @Override
        public CompletableFuture<Void> unwatch(long watchId) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> connect() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> close() {
            return CompletableFuture.completedFuture(null);
        }

        private class InMemoryLock implements Lock {
            private final String lockName;

            InMemoryLock(String lockName) {
                this.lockName = lockName;
            }

            @Override
            public CompletableFuture<Void> release() {
                return CompletableFuture.runAsync(() -> locks.remove(lockName));
            }

            @Override
            public CompletableFuture<Void> refresh() {
                return CompletableFuture.completedFuture(null);
            }
        }
    }

    /** In-memory messaging system that routes messages between actor systems in-process. */
    static class InMemoryMessagingSystem implements MessagingSystem {

        private final String systemId;
        private final ConcurrentHashMap<String, InMemoryMessagingSystem> peers =
                new ConcurrentHashMap<>();
        private volatile MessageHandler messageHandler;
        private volatile boolean running = false;

        InMemoryMessagingSystem(String systemId) {
            this.systemId = systemId;
        }

        void connectTo(InMemoryMessagingSystem other) {
            this.peers.put(other.systemId, other);
            other.peers.put(this.systemId, this);
        }

        @Override
        public <M> CompletableFuture<Void> sendMessage(String targetSystemId, String actorId,
                                                        M message) {
            return CompletableFuture.runAsync(() -> {
                InMemoryMessagingSystem target = peers.get(targetSystemId);
                if (target != null && target.messageHandler != null && target.running) {
                    target.messageHandler.onMessage(actorId, message);
                }
            });
        }

        @Override
        public CompletableFuture<Void> registerMessageHandler(MessageHandler handler) {
            return CompletableFuture.runAsync(() -> this.messageHandler = handler);
        }

        @Override
        public CompletableFuture<Void> start() {
            return CompletableFuture.runAsync(() -> this.running = true);
        }

        @Override
        public CompletableFuture<Void> stop() {
            return CompletableFuture.runAsync(() -> this.running = false);
        }
    }

    // -------------------------------------------------------------------------
    // Test lifecycle
    // -------------------------------------------------------------------------

    private static final String REDIS_URI = "redis://localhost:6379";
    private static final String KEY_PREFIX = "cajun-cluster-test";

    private ClusterActorSystem system1;
    private ClusterActorSystem system2;
    private RedisPersistenceProvider redisProvider;
    private String actorId;

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
            try {
                MessageJournal<CounterMessage> j = redisProvider.createMessageJournal(actorId);
                j.truncateBefore(actorId, Long.MAX_VALUE).get(5, TimeUnit.SECONDS);
                SnapshotStore<CounterState> s = redisProvider.createSnapshotStore(actorId);
                s.deleteSnapshots(actorId).get(5, TimeUnit.SECONDS);
                j.close();
                s.close();
            } catch (Exception ignored) {}
            redisProvider.close();
            redisProvider = null;
        }
    }

    // -------------------------------------------------------------------------
    // The Failing Test
    // -------------------------------------------------------------------------

    /**
     * Demonstrates state loss when a {@link StatefulActor} is reassigned to a new cluster node.
     *
     * <h3>Scenario</h3>
     * <ol>
     *   <li>Start {@code system1} backed by a shared {@link InMemoryMetadataStore}.</li>
     *   <li>Register {@code CounterActor} (id = {@code "counter-1"}) on {@code system1}
     *       with a <em>node-local</em> {@link MockBatchedMessageJournal} and
     *       {@link MockSnapshotStore}.</li>
     *   <li>Send 5 {@link CounterMessage.Increment} messages; verify count == 5.</li>
     *   <li>Stop {@code system1} (simulates node failure).</li>
     *   <li>Start {@code system2} with the <em>same</em> metadata store, so it will detect
     *       the orphaned actor assignment and may attempt to re-host the actor.</li>
     *   <li>Register a new {@code CounterActor} instance on {@code system2} with a
     *       <em>separate, empty</em> journal and snapshot store (node-local — no state).</li>
     *   <li>Send 1 more {@link CounterMessage.Increment} via {@code system2}.</li>
     *   <li>Assert count == 6.</li>
     * </ol>
     *
     * <h3>Actual Outcome (Bug)</h3>
     * <p>
     * The assertion fails: count is 1, not 6. The actor on {@code system2} starts fresh
     * because its journal and snapshot store are empty — {@code system1}'s node-local
     * storage was never shared or migrated.
     * </p>
     *
     * <h3>Fix</h3>
     * <p>Phase 26 (Cluster + Shared Persistence Integration) will add a Redis-backed
     * {@code PersistenceProvider} that is accessible to all nodes, ensuring that
     * reassigned actors recover their full state on the new node.</p>
     */
    @org.junit.jupiter.api.Disabled("Historical bug documentation")
    @Test
    void testStatefulActorStateLostOnClusterReassignment() throws Exception {

        // Shared metadata store — both nodes use this for actor placement and leader election
        InMemoryMetadataStore sharedMetadataStore = new InMemoryMetadataStore();

        // ---- Step 1: Start system1 ----
        InMemoryMessagingSystem ms1 = new InMemoryMessagingSystem("system1");
        system1 = new ClusterActorSystem("system1", sharedMetadataStore, ms1);
        system1.start().get(5, TimeUnit.SECONDS);
        Thread.sleep(500); // allow leader election to settle

        // ---- Step 2: Register CounterActor on system1 with node-local storage ----
        //
        // KEY OBSERVATION: These stores are in-memory and local to this JVM object.
        // system2 will create its own separate store instances — state is NOT shared.
        MockBatchedMessageJournal<CounterMessage> journal1 = new MockBatchedMessageJournal<>();
        MockSnapshotStore<CounterState> snapshot1 = new MockSnapshotStore<>();
        CounterActor counter1 = new CounterActor(
                system1, "counter-1", new CounterState(0), journal1, snapshot1);
        counter1.start();
        system1.registerActor(counter1);
        // Also register in the metadata store so the cluster knows this node owns the actor
        sharedMetadataStore.put("cajun/actor/counter-1", "system1").get(1, TimeUnit.SECONDS);
        assertTrue(counter1.waitForInit(2000), "Counter actor should initialize within 2 s");

        // ---- Step 3: Send 5 increments and verify count == 5 ----
        for (int i = 0; i < 5; i++) {
            counter1.tell(new CounterMessage.Increment());
        }
        Thread.sleep(300); // allow messages to be processed

        CountDownLatch latch1 = new CountDownLatch(1);
        AtomicInteger countFromSystem1 = new AtomicInteger(-1);
        ReplyCollector collector1 = new ReplyCollector(
                system1, "collector-1", latch1, countFromSystem1);
        collector1.start();
        system1.registerActor(collector1);
        counter1.tell(new CounterMessage.GetCount(collector1.self()));
        assertTrue(latch1.await(3, TimeUnit.SECONDS),
                "Should receive count reply from system1");
        assertEquals(5, countFromSystem1.get(),
                "Pre-condition: count should be 5 after 5 increments on system1");

        // ---- Step 4: Stop system1 (simulate node failure) ----
        system1.stop().get(5, TimeUnit.SECONDS);
        system1 = null;
        Thread.sleep(500); // allow node-departure events to propagate

        // ---- Step 5: Start system2 with the SAME metadata store ----
        InMemoryMessagingSystem ms2 = new InMemoryMessagingSystem("system2");
        system2 = new ClusterActorSystem("system2", sharedMetadataStore, ms2);
        system2.start().get(5, TimeUnit.SECONDS);
        Thread.sleep(2000); // allow leader election and actor reassignment

        // ---- Step 6: Register CounterActor on system2 with a fresh, empty storage ----
        //
        // BUG: This actor starts with count=0. There is no mechanism to migrate or
        // share the journal/snapshot from system1 to system2. The cluster only updates
        // the metadata key to say "system2 now owns counter-1", but does nothing to
        // move the persisted state.
        MockBatchedMessageJournal<CounterMessage> journal2 = new MockBatchedMessageJournal<>();
        MockSnapshotStore<CounterState> snapshot2 = new MockSnapshotStore<>();
        CounterActor counter2 = new CounterActor(
                system2, "counter-1", new CounterState(0), journal2, snapshot2);
        counter2.start();
        system2.registerActor(counter2);
        assertTrue(counter2.waitForInit(2000), "Counter actor on system2 should initialize");

        // ---- Step 7: Send 1 more increment via system2 ----
        counter2.tell(new CounterMessage.Increment());
        Thread.sleep(300);

        // ---- Step 8: Assert count == 6 — THIS ASSERTION FAILS ----
        //
        // Expected: 6  (5 accumulated on system1 + 1 new on system2)
        // Actual:   1  (actor started fresh on system2 with initialState count=0)
        CountDownLatch latch2 = new CountDownLatch(1);
        AtomicInteger countFromSystem2 = new AtomicInteger(-1);
        ReplyCollector collector2 = new ReplyCollector(
                system2, "collector-2", latch2, countFromSystem2);
        collector2.start();
        system2.registerActor(collector2);
        counter2.tell(new CounterMessage.GetCount(collector2.self()));
        assertTrue(latch2.await(3, TimeUnit.SECONDS),
                "Should receive count reply from system2");

        // BUG DOCUMENTED HERE:
        // This assertEquals will FAIL because the actor on system2 has count=1, not 6.
        // State was lost on reassignment because:
        //   1. journal1 / snapshot1 exist only in system1's memory
        //   2. journal2 / snapshot2 on system2 are empty (fresh start)
        //   3. ClusterActorSystem.assignActorToNode() only writes a metadata key;
        //      it does NOT migrate persisted state to the new node
        assertEquals(6, countFromSystem2.get(),
                "BUG (Phase 26): StatefulActor state lost on cluster reassignment. " +
                "Expected count=6 (5 from system1 + 1 from system2) but was " +
                countFromSystem2.get() + ". " +
                "Root cause: persistence is node-local; no shared store exists yet.");
    }

    // -------------------------------------------------------------------------
    // The Fixed Test (Phase 26)
    // -------------------------------------------------------------------------

    /**
     * Demonstrates state recovery when a {@link StatefulActor} is reassigned to a new cluster
     * node AND both nodes share a Redis-backed persistence provider.
     *
     * <h3>Scenario</h3>
     * <ol>
     *   <li>Start {@code system1} backed by a shared {@link InMemoryMetadataStore}.</li>
     *   <li>Register {@code CounterActor} (UUID id) on {@code system1} with Redis-backed
     *       journal and snapshot store from {@code redisProvider}.</li>
     *   <li>Send 5 {@link CounterMessage.Increment} messages; verify count == 5.</li>
     *   <li>Stop {@code system1} (simulates node failure).</li>
     *   <li>Start {@code system2} with the <em>same</em> metadata store.</li>
     *   <li>Register a new {@code CounterActor} on {@code system2} with the <em>same</em>
     *       Redis-backed journal and snapshot store — same Redis keys as system1.</li>
     *   <li>StatefulActor recovery replays the 5 journal messages from Redis → count=5.</li>
     *   <li>Send 1 more {@link CounterMessage.Increment} via {@code system2}.</li>
     *   <li>Assert count == 6.</li>
     * </ol>
     */
    @Test
    void testStatefulActorStateRecoveredViaRedis() throws Exception {

        // Shared metadata store — both nodes use this for actor placement and leader election
        InMemoryMetadataStore sharedMetadataStore = new InMemoryMetadataStore();

        // ---- Step 1: Start system1 ----
        InMemoryMessagingSystem ms1 = new InMemoryMessagingSystem("system1");
        system1 = new ClusterActorSystem("system1", sharedMetadataStore, ms1);
        system1.start().get(5, TimeUnit.SECONDS);
        Thread.sleep(500); // allow leader election to settle

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
        assertTrue(counter1.waitForInit(2000), "Counter actor should initialize within 2 s");

        // ---- Step 3: Send 5 increments and verify count == 5 ----
        for (int i = 0; i < 5; i++) {
            counter1.tell(new CounterMessage.Increment());
        }
        Thread.sleep(800); // allow messages to be processed and journaled to Redis

        CountDownLatch latch1 = new CountDownLatch(1);
        AtomicInteger countFromSystem1 = new AtomicInteger(-1);
        ReplyCollector collector1 = new ReplyCollector(
                system1, "collector-1-" + UUID.randomUUID(), latch1, countFromSystem1);
        collector1.start();
        system1.registerActor(collector1);
        counter1.tell(new CounterMessage.GetCount(collector1.self()));
        assertTrue(latch1.await(3, TimeUnit.SECONDS),
                "Should receive count reply from system1");
        assertEquals(5, countFromSystem1.get(),
                "Pre-condition: count should be 5 after 5 increments on system1");

        // ---- Step 4: Stop system1 (simulate node failure) ----
        system1.stop().get(5, TimeUnit.SECONDS);
        system1 = null;
        Thread.sleep(500); // allow node-departure events to propagate

        // ---- Step 5: Start system2 with the SAME metadata store ----
        InMemoryMessagingSystem ms2 = new InMemoryMessagingSystem("system2");
        system2 = new ClusterActorSystem("system2", sharedMetadataStore, ms2);
        system2.start().get(5, TimeUnit.SECONDS);
        Thread.sleep(2000); // allow leader election and actor reassignment

        // ---- Step 6: Register CounterActor on system2 with SAME Redis storage ----
        // KEY FIX: Using the same Redis journal/snapshot (same actorId, same Redis keys)
        // means StatefulActor.initializeState() will replay the 5 messages from Redis
        // and recover count=5 before accepting new messages.
        BatchedMessageJournal<CounterMessage> journal2 =
                redisProvider.createBatchedMessageJournal(actorId);
        SnapshotStore<CounterState> snapshot2 =
                redisProvider.createSnapshotStore(actorId);
        CounterActor counter2 = new CounterActor(
                system2, actorId, new CounterState(0), journal2, snapshot2);
        counter2.start();
        system2.registerActor(counter2);
        assertTrue(counter2.waitForInit(5000), "Counter actor on system2 should initialize");

        // ---- Step 7: Send 1 more increment via system2 ----
        counter2.tell(new CounterMessage.Increment());
        Thread.sleep(800);

        // ---- Step 8: Assert count == 6 ----
        // system2 recovered 5 messages from Redis, then incremented once more → 6
        CountDownLatch latch2 = new CountDownLatch(1);
        AtomicInteger countFromSystem2 = new AtomicInteger(-1);
        ReplyCollector collector2 = new ReplyCollector(
                system2, "collector-2-" + UUID.randomUUID(), latch2, countFromSystem2);
        collector2.start();
        system2.registerActor(collector2);
        counter2.tell(new CounterMessage.GetCount(collector2.self()));
        assertTrue(latch2.await(3, TimeUnit.SECONDS),
                "Should receive count reply from system2");

        assertEquals(6, countFromSystem2.get(),
                "State recovered from Redis: 5 from system1 + 1 from system2");
    }

    /**
     * Verifies that full state is preserved across a simulated node failure when 100 messages
     * have been journaled to Redis.
     *
     * <h3>Scenario</h3>
     * <ol>
     *   <li>Start {@code system1}; register {@code CounterActor} backed by Redis.</li>
     *   <li>Send 100 {@link CounterMessage.Increment} messages; verify count == 100.</li>
     *   <li>Stop {@code system1} (simulates node failure).</li>
     *   <li>Start {@code system2}; re-register the same actor ID with the same Redis store.</li>
     *   <li>{@code initializeState()} replays all 100 journal entries → count recovered to 100.</li>
     *   <li>Send 1 more increment; verify count == 101.</li>
     * </ol>
     */
    @Test
    @Tag("requires-redis")
    void testStatefulActorFullLifecycle_100Messages() throws Exception {

        InMemoryMetadataStore sharedMetadataStore = new InMemoryMetadataStore();

        // ---- Step 1: Start system1 ----
        InMemoryMessagingSystem ms1 = new InMemoryMessagingSystem("system1");
        system1 = new ClusterActorSystem("system1", sharedMetadataStore, ms1);
        system1.start().get(5, TimeUnit.SECONDS);
        Thread.sleep(500); // allow leader election to settle

        // ---- Step 2: Register CounterActor on system1 with Redis storage ----
        BatchedMessageJournal<CounterMessage> journal1 =
                redisProvider.createBatchedMessageJournal(actorId);
        SnapshotStore<CounterState> snapshot1 =
                redisProvider.createSnapshotStore(actorId);
        CounterActor counter1 = new CounterActor(
                system1, actorId, new CounterState(0), journal1, snapshot1);
        counter1.start();
        system1.registerActor(counter1);
        sharedMetadataStore.put("cajun/actor/" + actorId, "system1").get(1, TimeUnit.SECONDS);
        assertTrue(counter1.waitForInit(2000), "Counter actor should initialize within 2 s");

        // ---- Step 3: Send 100 increments ----
        for (int i = 0; i < 100; i++) {
            counter1.tell(new CounterMessage.Increment());
        }
        // Allow messages to be processed and batched journal entries flushed to Redis
        Thread.sleep(2000);

        // ---- Verify count == 100 on system1 ----
        CountDownLatch latch1 = new CountDownLatch(1);
        AtomicInteger countFromSystem1 = new AtomicInteger(-1);
        ReplyCollector collector1 = new ReplyCollector(
                system1, "collector-1-" + UUID.randomUUID(), latch1, countFromSystem1);
        collector1.start();
        system1.registerActor(collector1);
        counter1.tell(new CounterMessage.GetCount(collector1.self()));
        assertTrue(latch1.await(5, TimeUnit.SECONDS),
                "Should receive count reply from system1");
        assertEquals(100, countFromSystem1.get(),
                "Pre-condition: count should be 100 after 100 increments on system1");

        // ---- Step 4: Stop system1 (simulate node failure) ----
        system1.stop().get(5, TimeUnit.SECONDS);
        system1 = null;
        Thread.sleep(500);

        // ---- Step 5: Start system2 with the SAME metadata store ----
        InMemoryMessagingSystem ms2 = new InMemoryMessagingSystem("system2");
        system2 = new ClusterActorSystem("system2", sharedMetadataStore, ms2);
        system2.start().get(5, TimeUnit.SECONDS);
        Thread.sleep(2000); // allow leader election and actor reassignment

        // ---- Step 6: Re-register CounterActor on system2 with SAME Redis storage ----
        BatchedMessageJournal<CounterMessage> journal2 =
                redisProvider.createBatchedMessageJournal(actorId);
        SnapshotStore<CounterState> snapshot2 =
                redisProvider.createSnapshotStore(actorId);
        CounterActor counter2 = new CounterActor(
                system2, actorId, new CounterState(0), journal2, snapshot2);
        counter2.start();
        system2.registerActor(counter2);
        assertTrue(counter2.waitForInit(5000),
                "Counter actor on system2 should initialize and recover 100 messages from Redis");

        // ---- Step 7: Send 1 more increment via system2 ----
        counter2.tell(new CounterMessage.Increment());
        Thread.sleep(800);

        // ---- Step 8: Assert count == 101 ----
        CountDownLatch latch2 = new CountDownLatch(1);
        AtomicInteger countFromSystem2 = new AtomicInteger(-1);
        ReplyCollector collector2 = new ReplyCollector(
                system2, "collector-2-" + UUID.randomUUID(), latch2, countFromSystem2);
        collector2.start();
        system2.registerActor(collector2);
        counter2.tell(new CounterMessage.GetCount(collector2.self()));
        assertTrue(latch2.await(5, TimeUnit.SECONDS),
                "Should receive count reply from system2");
        assertEquals(101, countFromSystem2.get(),
                "Full lifecycle: 100 messages recovered from Redis + 1 new = 101");
    }
}
