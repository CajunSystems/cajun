package com.cajunsystems.persistence.redis;

import com.cajunsystems.persistence.JournalEntry;
import com.cajunsystems.persistence.MessageJournal;
import com.cajunsystems.persistence.SnapshotEntry;
import com.cajunsystems.persistence.SnapshotStore;
import com.cajunsystems.serialization.KryoSerializationProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Redis-backed persistence.
 * Requires a running Redis instance at redis://localhost:6379.
 *
 * <p>These tests are excluded from the default test task via the {@code requires-redis} tag.
 * Run with: {@code ./gradlew test -Dtag=requires-redis}
 */
@Tag("requires-redis")
class RedisIntegrationTest {

    private static final String REDIS_URI = "redis://localhost:6379";

    private RedisPersistenceProvider provider;

    @AfterEach
    void tearDown() {
        if (provider != null) {
            provider.close();
        }
    }

    private RedisPersistenceProvider createProvider() {
        String prefix = "cajun-test-" + UUID.randomUUID().toString().substring(0, 8);
        provider = new RedisPersistenceProvider(REDIS_URI, prefix, KryoSerializationProvider.INSTANCE);
        return provider;
    }

    @Test
    void journal_appendAndReadFrom_roundTrip() {
        RedisPersistenceProvider p = createProvider();
        String actorId = "actor-" + UUID.randomUUID();
        MessageJournal<String> journal = p.createMessageJournal(actorId);

        // Append 5 messages
        for (int i = 1; i <= 5; i++) {
            journal.append(actorId, "msg-" + i).join();
        }

        // Read all from seq=1
        List<JournalEntry<String>> all = journal.readFrom(actorId, 1).join();
        assertEquals(5, all.size());
        for (int i = 0; i < 5; i++) {
            assertEquals("msg-" + (i + 1), all.get(i).getMessage());
            assertEquals(i + 1L, all.get(i).getSequenceNumber());
        }

        // Read from seq=3 — should return entries 3, 4, 5
        List<JournalEntry<String>> partial = journal.readFrom(actorId, 3).join();
        assertEquals(3, partial.size());
        assertEquals("msg-3", partial.get(0).getMessage());
        assertEquals("msg-4", partial.get(1).getMessage());
        assertEquals("msg-5", partial.get(2).getMessage());
    }

    @Test
    void journal_truncateBefore_removesOldEntries() {
        RedisPersistenceProvider p = createProvider();
        String actorId = "actor-" + UUID.randomUUID();
        MessageJournal<String> journal = p.createMessageJournal(actorId);

        for (int i = 1; i <= 5; i++) {
            journal.append(actorId, "msg-" + i).join();
        }

        // Truncate entries before seq=3 (removes 1, 2)
        journal.truncateBefore(actorId, 3).join();

        List<JournalEntry<String>> remaining = journal.readFrom(actorId, 1).join();
        assertEquals(3, remaining.size());
        // Remaining entries should be 3, 4, 5
        for (JournalEntry<String> entry : remaining) {
            assertTrue(entry.getSequenceNumber() >= 3,
                    "All entries should have seqNum >= 3 after truncation");
        }

        // Highest sequence number should still be 5
        assertEquals(5L, journal.getHighestSequenceNumber(actorId).join());
    }

    @Test
    void journal_getHighestSequenceNumber_returnsMinusOneForFreshActor() {
        RedisPersistenceProvider p = createProvider();
        String freshActorId = "fresh-" + UUID.randomUUID();
        MessageJournal<String> journal = p.createMessageJournal(freshActorId);

        assertEquals(-1L, journal.getHighestSequenceNumber(freshActorId).join());
    }

    @Test
    void snapshot_saveAndLoad_roundTrip() {
        RedisPersistenceProvider p = createProvider();
        String actorId = "actor-" + UUID.randomUUID();
        SnapshotStore<String> snapshotStore = p.createSnapshotStore(actorId);

        // Initially empty
        Optional<SnapshotEntry<String>> empty = snapshotStore.getLatestSnapshot(actorId).join();
        assertTrue(empty.isEmpty());

        // Save snapshot
        snapshotStore.saveSnapshot(actorId, "state-42", 42L).join();

        // Load snapshot
        Optional<SnapshotEntry<String>> loaded = snapshotStore.getLatestSnapshot(actorId).join();
        assertTrue(loaded.isPresent());
        assertEquals(42L, loaded.get().getSequenceNumber());
        assertEquals("state-42", loaded.get().getState());
        assertEquals(actorId, loaded.get().getActorId());
    }

    @Test
    void snapshot_delete_clearsEntry() {
        RedisPersistenceProvider p = createProvider();
        String actorId = "actor-" + UUID.randomUUID();
        SnapshotStore<String> snapshotStore = p.createSnapshotStore(actorId);

        snapshotStore.saveSnapshot(actorId, "some-state", 10L).join();
        Optional<SnapshotEntry<String>> present = snapshotStore.getLatestSnapshot(actorId).join();
        assertTrue(present.isPresent());

        snapshotStore.deleteSnapshots(actorId).join();

        Optional<SnapshotEntry<String>> afterDelete = snapshotStore.getLatestSnapshot(actorId).join();
        assertTrue(afterDelete.isEmpty());
    }

    @Test
    void snapshot_overwrite_returnsLatest() {
        RedisPersistenceProvider p = createProvider();
        String actorId = "actor-" + UUID.randomUUID();
        SnapshotStore<String> snapshotStore = p.createSnapshotStore(actorId);

        snapshotStore.saveSnapshot(actorId, "state-at-5", 5L).join();
        snapshotStore.saveSnapshot(actorId, "state-at-10", 10L).join();

        Optional<SnapshotEntry<String>> latest = snapshotStore.getLatestSnapshot(actorId).join();
        assertTrue(latest.isPresent());
        assertEquals(10L, latest.get().getSequenceNumber());
        assertEquals("state-at-10", latest.get().getState());
    }

    @Test
    void provider_isHealthy_returnsTrueWhenReachable() {
        RedisPersistenceProvider p = createProvider();
        assertTrue(p.isHealthy());
    }
}
