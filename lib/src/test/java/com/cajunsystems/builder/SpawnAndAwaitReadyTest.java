package com.cajunsystems.builder;

import com.cajunsystems.ActorContext;
import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.handler.StatefulHandler;
import com.cajunsystems.persistence.MockBatchedMessageJournal;
import com.cajunsystems.persistence.MockSnapshotStore;
import com.cajunsystems.persistence.SnapshotEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link StatefulActorBuilder#spawnAndAwaitReady(Duration)} cleans up the spawned actor
 * when initialization never completes, rather than leaking a running actor whose PID the caller
 * never receives.
 */
class SpawnAndAwaitReadyTest {

    private ActorSystem system;

    @BeforeEach
    void setUp() {
        system = new ActorSystem();
    }

    @AfterEach
    void tearDown() {
        if (system != null) {
            system.shutdown();
        }
        system = null;
    }

    public static class SlowHandler implements StatefulHandler<Integer, String> {
        @Override
        public Integer receive(String message, Integer state, ActorContext context) {
            return state;
        }
    }

    /** A snapshot store whose load never completes, so state initialization hangs. */
    static class NeverCompletingSnapshotStore<S> extends MockSnapshotStore<S> {
        @Override
        public CompletableFuture<Optional<SnapshotEntry<S>>> getLatestSnapshot(String actorId) {
            return new CompletableFuture<>(); // never completes
        }
    }

    @Test
    void failedReadinessStopsTheSpawnedActor() {
        StatefulActorBuilder<Integer, String> builder = system.statefulActorOf(new SlowHandler(), 0)
                .withId("slow")
                .withPersistence(new MockBatchedMessageJournal<>(), new NeverCompletingSnapshotStore<>());

        assertThrows(IllegalStateException.class,
                () -> builder.spawnAndAwaitReady(Duration.ofMillis(200)),
                "spawnAndAwaitReady should throw when the actor never becomes ready");

        // The actor must not be left running/registered: the caller never got its PID.
        assertFalse(system.getActors().containsKey("slow"),
                "the un-ready actor should have been stopped and unregistered, not leaked");
        assertTrue(system.getActorOptional(new Pid("slow", system)).isEmpty(),
                "no actor should remain registered under the id");
    }
}
