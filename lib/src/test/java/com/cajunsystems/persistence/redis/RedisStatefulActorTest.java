package com.cajunsystems.persistence.redis;

import com.cajunsystems.Actor;
import com.cajunsystems.ActorContext;
import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.StatefulActor;
import com.cajunsystems.handler.StatefulHandler;
import com.cajunsystems.persistence.BatchedMessageJournal;
import com.cajunsystems.persistence.SnapshotStore;
import com.cajunsystems.serialization.KryoSerializationProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test validating that a stateful actor can recover its state from Redis
 * after a system restart. This tests the Phase 22 C1 bug fix for proper state recovery.
 *
 * <p>Requires a running Redis instance at redis://localhost:6379.
 * Excluded from default test run via {@code requires-redis} tag.
 */
@Tag("requires-redis")
class RedisStatefulActorTest {

    private static final String REDIS_URI = "redis://localhost:6379";

    private ActorSystem system;
    private RedisPersistenceProvider provider;

    @AfterEach
    void tearDown() throws Exception {
        if (system != null) {
            system.shutdown();
        }
        if (provider != null) {
            provider.close();
        }
    }

    /**
     * Simple increment message for a counter actor.
     */
    record IncrementMsg(int delta) {}

    /**
     * Stateful handler that increments a counter for each message received.
     */
    public static class CounterHandler implements StatefulHandler<Integer, IncrementMsg> {
        @Override
        public Integer receive(IncrementMsg message, Integer state, ActorContext context) {
            if (state == null) state = 0;
            return state + message.delta();
        }
    }

    @Test
    void statefulActor_recoversStateFromRedis() throws Exception {
        String uniquePrefix = "cajun-test-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String actorId = "redis-counter-" + UUID.randomUUID().toString().substring(0, 8);

        // --- Phase 1: Create actor system, send 10 messages ---
        provider = new RedisPersistenceProvider(REDIS_URI, uniquePrefix, KryoSerializationProvider.INSTANCE);
        system = new ActorSystem();

        @SuppressWarnings({"unchecked", "rawtypes"})
        BatchedMessageJournal<IncrementMsg> journal1 =
                (BatchedMessageJournal) provider.createBatchedMessageJournal(actorId);
        @SuppressWarnings({"unchecked", "rawtypes"})
        SnapshotStore<Integer> snapshotStore1 =
                (SnapshotStore) provider.createSnapshotStore(actorId);

        Pid pid1 = system.statefulActorOf(CounterHandler.class, 0)
                .withId(actorId)
                .withPersistence(journal1, snapshotStore1)
                .spawn();

        // Send 10 increment messages
        for (int i = 0; i < 10; i++) {
            pid1.tell(new IncrementMsg(1));
        }

        // Wait for messages to be processed
        Thread.sleep(500);

        // Verify count is 10 in the running actor
        Actor<?> actor1 = system.getActor(pid1);
        assertNotNull(actor1, "Actor should be present after sending 10 messages");
        assertTrue(actor1 instanceof StatefulActor, "Actor should be a StatefulActor");

        @SuppressWarnings("unchecked")
        StatefulActor<Integer, IncrementMsg> statefulActor1 = (StatefulActor<Integer, IncrementMsg>) actor1;
        assertEquals(10, statefulActor1.getState(),
                "State should be 10 after 10 increments in first system");

        // --- Shutdown first system ---
        system.shutdown();
        system = null;
        provider.close();

        // Give time for clean shutdown
        Thread.sleep(200);

        // --- Phase 2: New actor system + same Redis — recover and send 1 more ---
        provider = new RedisPersistenceProvider(REDIS_URI, uniquePrefix, KryoSerializationProvider.INSTANCE);
        system = new ActorSystem();

        @SuppressWarnings({"unchecked", "rawtypes"})
        BatchedMessageJournal<IncrementMsg> journal2 =
                (BatchedMessageJournal) provider.createBatchedMessageJournal(actorId);
        @SuppressWarnings({"unchecked", "rawtypes"})
        SnapshotStore<Integer> snapshotStore2 =
                (SnapshotStore) provider.createSnapshotStore(actorId);

        Pid pid2 = system.statefulActorOf(CounterHandler.class, 0)
                .withId(actorId)
                .withPersistence(journal2, snapshotStore2)
                .spawn();

        // Wait for state recovery
        Thread.sleep(500);

        // Send 1 more message
        pid2.tell(new IncrementMsg(1));
        Thread.sleep(300);

        // Assert final state = 11 (10 from before + 1 new)
        Actor<?> actor2 = system.getActor(pid2);
        assertNotNull(actor2, "Actor should be present in second system");
        assertTrue(actor2 instanceof StatefulActor, "Actor should be a StatefulActor");

        @SuppressWarnings("unchecked")
        StatefulActor<Integer, IncrementMsg> statefulActor2 = (StatefulActor<Integer, IncrementMsg>) actor2;
        assertEquals(11, statefulActor2.getState(),
                "State should be 11 after recovery (10 from Redis) + 1 new increment");
    }
}
