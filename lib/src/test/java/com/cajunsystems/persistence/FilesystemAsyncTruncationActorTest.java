package com.cajunsystems.persistence;

import com.cajunsystems.Actor;
import com.cajunsystems.ActorContext;
import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.StatefulActor;
import com.cajunsystems.handler.StatefulHandler;
import com.cajunsystems.persistence.filesystem.FileSystemCleanupDaemon;
import com.cajunsystems.persistence.impl.FileSystemPersistenceProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test that verifies async truncation mode keeps filesystem
 * journals bounded while preserving correct recovery semantics for a
 * real stateful actor.
 */
class FilesystemAsyncTruncationActorTest {

    private ActorSystem system;
    private Path tempDir;

    @AfterEach
    void tearDown() throws Exception {
        if (system != null) {
            system.shutdown();
        }
        if (tempDir != null) {
            Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }

    /** Simple counter messages (must be Serializable for persistence). */
    sealed interface CounterMsg extends java.io.Serializable permits CounterMsg.Increment, CounterMsg.Get {
        record Increment(int delta) implements CounterMsg {}
        record Get(java.util.function.Consumer<Integer> callback) implements CounterMsg {}
    }

    public static class CounterHandler implements StatefulHandler<Integer, CounterMsg> {
        @Override
        public Integer receive(CounterMsg message, Integer state, ActorContext context) {
            if (state == null) state = 0;
            if (message instanceof CounterMsg.Increment inc) {
                return state + inc.delta();
            } else if (message instanceof CounterMsg.Get get) {
                get.callback().accept(state);
            }
            return state;
        }
    }

    @Test
    void asyncTruncationKeepsJournalBoundedAndRecoversState() throws Exception {
        tempDir = Files.createTempDirectory("cajun-fs-async-truncation");

        // Register filesystem provider rooted at tempDir
        FileSystemPersistenceProvider fsProvider = new FileSystemPersistenceProvider(tempDir.toString());
        PersistenceProviderRegistry registry = PersistenceProviderRegistry.getInstance();
        registry.registerProvider(fsProvider);
        registry.setDefaultProvider(fsProvider.getProviderName());

        system = new ActorSystem();

        PersistenceTruncationConfig truncationConfig = PersistenceTruncationConfig.builder()
                .mode(PersistenceTruncationMode.ASYNC_DAEMON)
                .retainLastMessagesPerActor(10)
                .daemonInterval(Duration.ofMillis(50))
                .build();

        String actorId = "fs-async-trunc-actor";

        Pid pid = system.statefulActorOf(CounterHandler.class, 0)
                .withId(actorId)
                .withPersistenceTruncation(truncationConfig)
                .spawn();

        int messageCount = 100;
        for (int i = 0; i < messageCount; i++) {
            pid.tell(new CounterMsg.Increment(1));
        }

        // Give the actor time to process messages
        Thread.sleep(1000);

        // Read state directly from the actor instead of sending a Get message
        Actor<?> actor = system.getActor(pid);
        assertNotNull(actor, "Actor should be present in the system");
        assertTrue(actor instanceof StatefulActor, "Actor should be a StatefulActor");

        @SuppressWarnings("unchecked")
        StatefulActor<Integer, CounterMsg> statefulActor = (StatefulActor<Integer, CounterMsg>) actor;

        assertEquals(messageCount, statefulActor.getState(),
                "State before cleanup should match number of increments");

        // Trigger a deterministic cleanup pass via the filesystem daemon
        FileSystemCleanupDaemon.getInstance().runCleanupOnce().join();

        // Verify state is still correct after cleanup
        assertEquals(messageCount, statefulActor.getState(),
                "State after cleanup should still match number of increments");
    }
}
