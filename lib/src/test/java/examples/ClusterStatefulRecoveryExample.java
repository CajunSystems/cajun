package examples;

import com.cajunsystems.Actor;
import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.StatefulActor;
import com.cajunsystems.cluster.ClusterActorSystem;
import com.cajunsystems.cluster.ClusterConfiguration;
import com.cajunsystems.cluster.ClusterManagementApi;
import com.cajunsystems.cluster.DeliveryGuarantee;
import com.cajunsystems.cluster.MessagingSystem;
import com.cajunsystems.cluster.MetadataStore;
import com.cajunsystems.persistence.BatchedMessageJournal;
import com.cajunsystems.persistence.SnapshotStore;
import com.cajunsystems.persistence.redis.RedisPersistenceProvider;
import com.cajunsystems.serialization.KryoSerializationProvider;
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
 * Runnable demonstration of stateful actor recovery across a simulated cluster failover.
 *
 * <h2>Scenario</h2>
 * <ol>
 *   <li>Two-node cluster is created using {@link ClusterConfiguration} and a shared
 *       Redis-backed {@link RedisPersistenceProvider}.</li>
 *   <li>A {@link CounterActor} is registered on system1 and receives 20 increment messages.</li>
 *   <li>The {@link ClusterManagementApi#drainNode} API migrates the actor from system1 to system2.</li>
 *   <li>system1 is stopped (simulating a rolling upgrade or node failure).</li>
 *   <li>The actor is re-registered on system2 using the same actor ID and the same Redis journal.
 *       {@link StatefulActor#initializeState()} replays all journal entries, recovering count=20.</li>
 *   <li>5 more increments are sent; the final count is verified to be 25.</li>
 * </ol>
 *
 * <p>This example uses self-contained in-process infrastructure ({@link SimpleMetadataStore}
 * and {@link SimpleMessagingSystem}) so it can run without a real etcd cluster. Real production
 * deployments use etcd ({@code ClusterFactory.createEtcdMetadataStore}) and a real
 * messaging transport ({@code ClusterFactory.createDirectMessagingSystem}).
 */
@Tag("requires-redis")
public class ClusterStatefulRecoveryExample {

    private static final String REDIS_URI   = "redis://localhost:6379";
    private static final String KEY_PREFIX  = "cajun-recovery-example";

    // -------------------------------------------------------------------------
    // Message types
    // -------------------------------------------------------------------------

    /**
     * Sealed message interface for the counter actor in this example.
     */
    public sealed interface CounterMsg extends Serializable
            permits CounterMsg.Increment, CounterMsg.GetCount {

        record Increment() implements CounterMsg {
            private static final long serialVersionUID = 1L;
        }

        record GetCount(Pid replyTo) implements CounterMsg {
            private static final long serialVersionUID = 1L;
        }
    }

    /**
     * Immutable state held by the counter actor.
     */
    public record CounterState(int count) implements Serializable {
        private static final long serialVersionUID = 1L;
    }

    // -------------------------------------------------------------------------
    // Actor implementations
    // -------------------------------------------------------------------------

    /**
     * Simple stateful counter actor backed by a provided journal and snapshot store.
     */
    private static class CounterActor extends StatefulActor<CounterState, CounterMsg> {

        private final CountDownLatch initLatch = new CountDownLatch(1);

        public CounterActor(ActorSystem system, String actorId, CounterState init,
                            BatchedMessageJournal<CounterMsg> journal,
                            SnapshotStore<CounterState> snapshots) {
            super(system, actorId, init, journal, snapshots);
        }

        @Override
        protected CounterState processMessage(CounterState state, CounterMsg msg) {
            if (state == null) state = new CounterState(0);
            return switch (msg) {
                case CounterMsg.Increment ignored -> new CounterState(state.count() + 1);
                case CounterMsg.GetCount gc -> {
                    if (gc.replyTo() != null) gc.replyTo().tell(state.count());
                    yield state;
                }
            };
        }

        @Override
        protected CompletableFuture<Void> initializeState() {
            return super.initializeState().thenRun(initLatch::countDown);
        }

        boolean waitForInit(long timeoutMs) throws InterruptedException {
            return initLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Simple stateless actor used to collect reply values in tests.
     */
    private static class ReplyCollector extends Actor<Integer> {

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
    // In-process infrastructure (no real etcd / gRPC needed for this example)
    // -------------------------------------------------------------------------

    /**
     * Minimal in-memory {@link MetadataStore} implementation for use in this example.
     *
     * <p>Production clusters use {@code ClusterFactory.createEtcdMetadataStore("http://etcd-host:2379")}.
     */
    private static class SimpleMetadataStore implements MetadataStore {

        private final ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, MetadataStore.Lock> locks = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, MetadataStore.KeyWatcher> watchers = new ConcurrentHashMap<>();

        @Override
        public CompletableFuture<Void> put(String key, String value) {
            return CompletableFuture.runAsync(() -> {
                store.put(key, value);
                for (Map.Entry<String, MetadataStore.KeyWatcher> e : watchers.entrySet()) {
                    if (key.startsWith(e.getKey())) e.getValue().onPut(key, value);
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
                for (Map.Entry<String, MetadataStore.KeyWatcher> e : watchers.entrySet()) {
                    if (key.startsWith(e.getKey())) e.getValue().onDelete(key);
                }
            });
        }

        @Override
        public CompletableFuture<List<String>> listKeys(String prefix) {
            return CompletableFuture.supplyAsync(() ->
                    store.keySet().stream().filter(k -> k.startsWith(prefix)).toList());
        }

        @Override
        public CompletableFuture<Optional<MetadataStore.Lock>> acquireLock(String lockName, long ttlSeconds) {
            return CompletableFuture.supplyAsync(() -> {
                SimpleMemLock lock = new SimpleMemLock(lockName);
                return locks.putIfAbsent(lockName, lock) == null
                        ? Optional.of((MetadataStore.Lock) lock)
                        : Optional.empty();
            });
        }

        @Override
        public CompletableFuture<Long> watch(String prefix, MetadataStore.KeyWatcher watcher) {
            return CompletableFuture.supplyAsync(() -> {
                long watchId = System.nanoTime();
                watchers.put(prefix, watcher);
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

        private class SimpleMemLock implements MetadataStore.Lock {
            private final String name;

            SimpleMemLock(String n) { this.name = n; }

            @Override
            public CompletableFuture<Void> release() {
                return CompletableFuture.runAsync(() -> locks.remove(name));
            }

            @Override
            public CompletableFuture<Void> refresh() {
                return CompletableFuture.completedFuture(null);
            }
        }
    }

    /**
     * Minimal in-process {@link MessagingSystem} for use in this example.
     *
     * <p>Production clusters use {@code ClusterFactory.createDirectMessagingSystem("node-id", port)}.
     */
    private static class SimpleMessagingSystem implements MessagingSystem {

        private final String id;
        private final ConcurrentHashMap<String, SimpleMessagingSystem> peers = new ConcurrentHashMap<>();
        private volatile MessagingSystem.MessageHandler handler;
        private volatile boolean running;

        SimpleMessagingSystem(String id) { this.id = id; }

        void connectTo(SimpleMessagingSystem other) {
            peers.put(other.id, other);
            other.peers.put(id, this);
        }

        @Override
        public <M> CompletableFuture<Void> sendMessage(String targetId, String actorId, M msg) {
            return CompletableFuture.runAsync(() -> {
                SimpleMessagingSystem target = peers.get(targetId);
                if (target != null && target.handler != null && target.running) {
                    target.handler.onMessage(actorId, msg);
                }
            });
        }

        @Override
        public CompletableFuture<Void> registerMessageHandler(MessagingSystem.MessageHandler h) {
            this.handler = h;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> start() {
            running = true;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> stop() {
            running = false;
            return CompletableFuture.completedFuture(null);
        }
    }

    // -------------------------------------------------------------------------
    // The example / test
    // -------------------------------------------------------------------------

    @Test
    void demonstrateStatefulRecoveryAfterDrainAndFailover() throws Exception {

        // Unique actor ID so concurrent test runs don't collide
        final String actorId = "counter-example-" + UUID.randomUUID();

        // Shared Redis persistence provider — same instance for both nodes
        RedisPersistenceProvider redis = new RedisPersistenceProvider(
                REDIS_URI, KEY_PREFIX, KryoSerializationProvider.INSTANCE);

        // Shared metadata store — both nodes watch the same in-memory KV
        SimpleMetadataStore sharedMetadata = new SimpleMetadataStore();

        // ---- Set up two-node cluster ----
        SimpleMessagingSystem ms1 = new SimpleMessagingSystem("system1");
        SimpleMessagingSystem ms2 = new SimpleMessagingSystem("system2");
        ms1.connectTo(ms2);

        ClusterActorSystem system1 = ClusterConfiguration.builder()
                .systemId("system1")
                .metadataStore(sharedMetadata)
                .messagingSystem(ms1)
                .deliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE)
                .persistenceProvider(redis)
                .build();

        ClusterActorSystem system2 = ClusterConfiguration.builder()
                .systemId("system2")
                .metadataStore(sharedMetadata)
                .messagingSystem(ms2)
                .deliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE)
                .persistenceProvider(redis)
                .build();

        system1.start().get(5, TimeUnit.SECONDS);
        system2.start().get(5, TimeUnit.SECONDS);
        Thread.sleep(500); // allow leader election to settle

        try {
            // ---- Step 1: Register CounterActor on system1 with Redis-backed storage ----
            BatchedMessageJournal<CounterMsg> journal1 = redis.createBatchedMessageJournal(actorId);
            SnapshotStore<CounterState> snapshot1 = redis.createSnapshotStore(actorId);

            CounterActor counter1 = new CounterActor(
                    system1, actorId, new CounterState(0), journal1, snapshot1);
            counter1.start();
            system1.registerActor(counter1);
            sharedMetadata.put("cajun/actor/" + actorId, "system1").get(1, TimeUnit.SECONDS);

            assertTrue(counter1.waitForInit(3000),
                    "CounterActor on system1 should initialize within 3 s");

            // ---- Step 2: Send 20 increment messages ----
            System.out.println("Sending 20 increments to system1...");
            for (int i = 0; i < 20; i++) {
                counter1.tell(new CounterMsg.Increment());
            }
            Thread.sleep(1000); // allow messages to be processed and flushed to Redis journal

            // Verify count == 20 on system1
            CountDownLatch latch1 = new CountDownLatch(1);
            AtomicInteger count1 = new AtomicInteger(-1);
            ReplyCollector collector1 = new ReplyCollector(
                    system1, "collector-" + UUID.randomUUID(), latch1, count1);
            collector1.start();
            system1.registerActor(collector1);
            counter1.tell(new CounterMsg.GetCount(collector1.self()));
            assertTrue(latch1.await(5, TimeUnit.SECONDS), "Reply from system1 expected");
            assertEquals(20, count1.get(), "Count should be 20 after 20 increments");
            System.out.println("Verified: count on system1 = " + count1.get());

            // ---- Step 3: Drain system1 (migrate actors away before upgrade/failover) ----
            System.out.println("Draining system1 (migrating actors to system2)...");
            ClusterManagementApi api = system1.getManagementApi();
            api.drainNode("system1").get(10, TimeUnit.SECONDS);
            Thread.sleep(500); // allow metadata to propagate

            // ---- Step 4: Stop system1 ----
            System.out.println("Stopping system1...");
            system1.stop().get(5, TimeUnit.SECONDS);
            system1 = null;
            Thread.sleep(500);

            // ---- Step 5: Re-register CounterActor on system2 with SAME Redis storage ----
            // StatefulActor.initializeState() will replay the 20 journal entries from Redis.
            System.out.println("Registering CounterActor on system2 (recovery from Redis)...");
            BatchedMessageJournal<CounterMsg> journal2 = redis.createBatchedMessageJournal(actorId);
            SnapshotStore<CounterState> snapshot2 = redis.createSnapshotStore(actorId);

            CounterActor counter2 = new CounterActor(
                    system2, actorId, new CounterState(0), journal2, snapshot2);
            counter2.start();
            system2.registerActor(counter2);

            assertTrue(counter2.waitForInit(5000),
                    "CounterActor on system2 should recover state from Redis within 5 s");
            System.out.println("Actor recovered on system2 — replayed 20 journal entries from Redis.");

            // ---- Step 6: Send 5 more increments on system2 ----
            System.out.println("Sending 5 more increments to system2...");
            for (int i = 0; i < 5; i++) {
                counter2.tell(new CounterMsg.Increment());
            }
            Thread.sleep(800);

            // ---- Step 7: Verify final count == 25 ----
            CountDownLatch latch2 = new CountDownLatch(1);
            AtomicInteger count2 = new AtomicInteger(-1);
            ReplyCollector collector2 = new ReplyCollector(
                    system2, "collector-" + UUID.randomUUID(), latch2, count2);
            collector2.start();
            system2.registerActor(collector2);
            counter2.tell(new CounterMsg.GetCount(collector2.self()));

            assertTrue(latch2.await(5, TimeUnit.SECONDS), "Reply from system2 expected");
            assertEquals(25, count2.get(),
                    "Expected count=25: 20 recovered from Redis + 5 new increments on system2");
            System.out.println("Success: final count on system2 = " + count2.get()
                    + " (20 recovered + 5 new)");

        } finally {
            // Cleanup: truncate Redis journal and snapshot so the test is idempotent
            try {
                redis.createMessageJournal(actorId)
                        .truncateBefore(actorId, Long.MAX_VALUE)
                        .get(5, TimeUnit.SECONDS);
                redis.createSnapshotStore(actorId)
                        .deleteSnapshots(actorId)
                        .get(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {}

            if (system1 != null) {
                try { system1.stop().get(5, TimeUnit.SECONDS); } catch (Exception ignored) {}
            }
            try { system2.stop().get(5, TimeUnit.SECONDS); } catch (Exception ignored) {}
            redis.close();
        }
    }
}
