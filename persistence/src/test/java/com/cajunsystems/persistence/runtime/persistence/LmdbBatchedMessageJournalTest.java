package com.cajunsystems.persistence.runtime.persistence;

import com.cajunsystems.persistence.JournalEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.io.Serializable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for LmdbBatchedMessageJournal implementation.
 * 
 * These tests require LMDB native libraries to be installed.
 * They will be skipped if LMDB is not available.
 */
@EnabledIf("isLmdbAvailable")
class LmdbBatchedMessageJournalTest {
    
    @TempDir
    Path tempDir;
    
    private LmdbEnvironmentManager envManager;
    private LmdbBatchedMessageJournal<TestMessage> journal;
    
    @BeforeEach
    void setUp() throws Exception {
        LmdbConfig config = LmdbConfig.builder()
            .dbPath(tempDir)
            .mapSize(10 * 1024 * 1024) // 10MB
            .build();
        
        envManager = new LmdbEnvironmentManager(config);
        journal = new LmdbBatchedMessageJournal<>("test-actor", envManager);
    }
    
    @AfterEach
    void tearDown() {
        if (journal != null) {
            journal.close();
        }
        if (envManager != null) {
            envManager.close();
        }
    }
    
    @Test
    void testAppendSingleMessage() throws Exception {
        String actorId = "actor-1";
        TestMessage message = new TestMessage("test", 1);
        
        long sequence = journal.append(actorId, message).get();
        
        assertEquals(1L, sequence);
    }
    
    @Test
    void testAppendMultipleMessages() throws Exception {
        String actorId = "actor-1";
        
        long seq1 = journal.append(actorId, new TestMessage("msg1", 1)).get();
        long seq2 = journal.append(actorId, new TestMessage("msg2", 2)).get();
        long seq3 = journal.append(actorId, new TestMessage("msg3", 3)).get();
        
        assertEquals(1L, seq1);
        assertEquals(2L, seq2);
        assertEquals(3L, seq3);
    }
    
    @Test
    void testAppendBatch() throws Exception {
        String actorId = "actor-1";
        List<TestMessage> messages = new ArrayList<>();
        
        for (int i = 1; i <= 10; i++) {
            messages.add(new TestMessage("msg-" + i, i));
        }
        
        List<Long> sequences = journal.appendBatch(actorId, messages).get();
        
        assertEquals(10, sequences.size());
        for (int i = 0; i < 10; i++) {
            assertEquals(i + 1L, sequences.get(i));
        }
    }
    
    @Test
    void testReadMessages() throws Exception {
        String actorId = "actor-1";
        
        // Append messages
        journal.append(actorId, new TestMessage("msg1", 1)).get();
        journal.append(actorId, new TestMessage("msg2", 2)).get();
        journal.append(actorId, new TestMessage("msg3", 3)).get();
        
        // Flush to ensure they're written
        journal.flush().get();
        
        // Read from sequence 1
        List<JournalEntry<TestMessage>> entries = journal.readFrom(actorId, 1).get();
        
        assertEquals(3, entries.size());
        assertEquals("msg1", entries.get(0).getMessage().text);
        assertEquals("msg2", entries.get(1).getMessage().text);
        assertEquals("msg3", entries.get(2).getMessage().text);
    }
    
    @Test
    void testReadFromSpecificSequence() throws Exception {
        String actorId = "actor-1";
        
        // Append messages
        for (int i = 1; i <= 10; i++) {
            journal.append(actorId, new TestMessage("msg-" + i, i)).get();
        }
        
        journal.flush().get();
        
        // Read from sequence 5
        List<JournalEntry<TestMessage>> entries = journal.readFrom(actorId, 5).get();
        
        assertEquals(6, entries.size()); // 5, 6, 7, 8, 9, 10
        assertEquals(5L, entries.get(0).getSequenceNumber());
        assertEquals(10L, entries.get(5).getSequenceNumber());
    }
    
    @Test
    void testBatchingBehavior() throws Exception {
        String actorId = "actor-1";
        
        // Set small batch size
        journal.setMaxBatchSize(5);
        
        // Append 10 messages (should trigger 2 batches)
        for (int i = 1; i <= 10; i++) {
            journal.append(actorId, new TestMessage("msg-" + i, i));
        }
        
        // Wait a bit for batching
        Thread.sleep(100);
        
        // Read all messages
        List<JournalEntry<TestMessage>> entries = journal.readFrom(actorId, 1).get();
        
        assertEquals(10, entries.size());
    }
    
    @Test
    void testManualFlush() throws Exception {
        String actorId = "actor-1";
        
        // Append messages without waiting for auto-flush
        journal.append(actorId, new TestMessage("msg1", 1));
        journal.append(actorId, new TestMessage("msg2", 2));
        
        // Manual flush
        journal.flush().get();
        
        // Read should return all messages
        List<JournalEntry<TestMessage>> entries = journal.readFrom(actorId, 1).get();
        
        assertEquals(2, entries.size());
    }
    
    @Test
    void testTimedFlush() throws Exception {
        String actorId = "actor-1";
        
        // Set short delay
        journal.setMaxBatchDelayMs(100);
        
        // Append a message
        journal.append(actorId, new TestMessage("msg1", 1));
        
        // Wait for timed flush
        Thread.sleep(200);
        
        // Read should return the message
        List<JournalEntry<TestMessage>> entries = journal.readFrom(actorId, 1).get();
        
        assertEquals(1, entries.size());
    }
    
    @Test
    void testTruncate() throws Exception {
        String actorId = "actor-1";
        
        // Append messages
        for (int i = 1; i <= 10; i++) {
            journal.append(actorId, new TestMessage("msg-" + i, i)).get();
        }
        
        journal.flush().get();
        
        // Truncate before sequence 5
        journal.truncateBefore(actorId, 5).get();
        
        // Read from beginning
        List<JournalEntry<TestMessage>> entries = journal.readFrom(actorId, 1).get();
        
        // Should only have messages from sequence 5 onwards
        assertEquals(6, entries.size());
        assertEquals(5L, entries.get(0).getSequenceNumber());
    }
    
    @Test
    void testGetHighestSequenceNumber() throws Exception {
        String actorId = "actor-1";
        
        // Initially should be 0
        long highest = journal.getHighestSequenceNumber(actorId).get();
        assertEquals(0L, highest);
        
        // Append messages
        journal.append(actorId, new TestMessage("msg1", 1)).get();
        journal.append(actorId, new TestMessage("msg2", 2)).get();
        journal.append(actorId, new TestMessage("msg3", 3)).get();
        
        // Should return 3
        highest = journal.getHighestSequenceNumber(actorId).get();
        assertEquals(3L, highest);
    }
    
    @Test
    void testConcurrentAppends() throws Exception {
        String actorId = "actor-1";
        int numThreads = 10;
        int messagesPerThread = 10;
        CountDownLatch latch = new CountDownLatch(numThreads);
        
        // Concurrent appends
        Thread[] threads = new Thread[numThreads];
        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                try {
                    for (int j = 0; j < messagesPerThread; j++) {
                        journal.append(actorId, new TestMessage("thread-" + threadId + "-msg-" + j, j));
                    }
                } finally {
                    latch.countDown();
                }
            });
            threads[i].start();
        }
        
        // Wait for all threads
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        
        // Flush to ensure all messages are written
        journal.flush().get();
        
        // Verify all messages were saved
        List<JournalEntry<TestMessage>> entries = journal.readFrom(actorId, 1).get();
        assertEquals(numThreads * messagesPerThread, entries.size());
    }
    
    @Test
    void testJournalEntryPreservesMetadata() throws Exception {
        String actorId = "actor-1";
        TestMessage message = new TestMessage("test", 42);
        
        // Append and flush
        long sequence = journal.append(actorId, message).get();
        journal.flush().get();
        
        // Read back
        List<JournalEntry<TestMessage>> entries = journal.readFrom(actorId, sequence).get();
        
        assertEquals(1, entries.size());
        JournalEntry<TestMessage> entry = entries.get(0);
        
        // Verify all metadata is preserved
        assertEquals(sequence, entry.getSequenceNumber());
        assertEquals(actorId, entry.getActorId());
        assertNotNull(entry.getTimestamp());
        assertEquals("test", entry.getMessage().text);
        assertEquals(42, entry.getMessage().value);
    }
    
    @Test
    void testMultipleActors() throws Exception {
        // Append messages for different actors
        journal.append("actor-1", new TestMessage("msg1", 1)).get();
        journal.append("actor-2", new TestMessage("msg2", 2)).get();
        journal.append("actor-1", new TestMessage("msg3", 3)).get();
        
        journal.flush().get();
        
        // Read each actor's messages
        List<JournalEntry<TestMessage>> actor1Entries = journal.readFrom("actor-1", 1).get();
        List<JournalEntry<TestMessage>> actor2Entries = journal.readFrom("actor-2", 1).get();
        
        assertEquals(2, actor1Entries.size());
        assertEquals(1, actor2Entries.size());
    }
    
    /**
     * Check if LMDB native libraries are available.
     */
    static boolean isLmdbAvailable() {
        try {
            Class.forName("org.lmdbjava.Env");
            Path testPath = Path.of(System.getProperty("java.io.tmpdir"), "lmdb-test-" + System.nanoTime());
            LmdbConfig config = LmdbConfig.builder()
                .dbPath(testPath)
                .mapSize(1024 * 1024)
                .build();
            try (LmdbEnvironmentManager testEnv = new LmdbEnvironmentManager(config)) {
                return true;
            } catch (UnsatisfiedLinkError e) {
                System.err.println("LMDB native libraries not available: " + e.getMessage());
                return false;
            }
        } catch (Throwable e) {
            System.err.println("LMDB not available: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Test message class for serialization.
     */
    static class TestMessage implements Serializable {
        private static final long serialVersionUID = 1L;
        final String text;
        final int value;
        
        TestMessage(String text, int value) {
            this.text = text;
            this.value = value;
        }
    }
}
