package com.cajunsystems.persistence.runtime.persistence;

import com.cajunsystems.persistence.JournalEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.Serializable;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for FileMessageJournal with new hardening features.
 */
class FileMessageJournalTest {
    
    @TempDir
    Path tempDir;
    
    private FileMessageJournal<TestMessage> journal;
    
    @BeforeEach
    void setUp() {
        journal = new FileMessageJournal<>(tempDir, true); // Enable fsync
    }
    
    @AfterEach
    void tearDown() {
        if (journal != null) {
            journal.close();
        }
    }
    
    @Test
    void testAppendWithFsync() throws Exception {
        String actorId = "actor-1";
        TestMessage message = new TestMessage("test", 1);
        
        long sequence = journal.append(actorId, message).get();
        
        assertEquals(0L, sequence);
    }
    
    @Test
    void testAppendMultipleMessages() throws Exception {
        String actorId = "actor-1";
        
        long seq1 = journal.append(actorId, new TestMessage("msg1", 1)).get();
        long seq2 = journal.append(actorId, new TestMessage("msg2", 2)).get();
        long seq3 = journal.append(actorId, new TestMessage("msg3", 3)).get();
        
        assertEquals(0L, seq1);
        assertEquals(1L, seq2);
        assertEquals(2L, seq3);
    }
    
    @Test
    void testReadMessages() throws Exception {
        String actorId = "actor-1";
        
        // Append messages
        journal.append(actorId, new TestMessage("msg1", 1)).get();
        journal.append(actorId, new TestMessage("msg2", 2)).get();
        journal.append(actorId, new TestMessage("msg3", 3)).get();
        
        // Read from sequence 0
        List<JournalEntry<TestMessage>> entries = journal.readFrom(actorId, 0).get();
        
        assertEquals(3, entries.size());
        assertEquals("msg1", entries.get(0).getMessage().text);
        assertEquals("msg2", entries.get(1).getMessage().text);
        assertEquals("msg3", entries.get(2).getMessage().text);
    }
    
    @Test
    void testReadFromSpecificSequence() throws Exception {
        String actorId = "actor-1";
        
        // Append messages
        for (int i = 0; i < 10; i++) {
            journal.append(actorId, new TestMessage("msg-" + i, i)).get();
        }
        
        // Read from sequence 5
        List<JournalEntry<TestMessage>> entries = journal.readFrom(actorId, 5).get();
        
        assertEquals(5, entries.size()); // 5, 6, 7, 8, 9
        assertEquals(5L, entries.get(0).getSequenceNumber());
        assertEquals(9L, entries.get(4).getSequenceNumber());
    }
    
    @Test
    void testTruncate() throws Exception {
        String actorId = "actor-1";
        
        // Append messages
        for (int i = 0; i < 10; i++) {
            journal.append(actorId, new TestMessage("msg-" + i, i)).get();
        }
        
        // Truncate before sequence 5
        journal.truncateBefore(actorId, 5).get();
        
        // Read from beginning
        List<JournalEntry<TestMessage>> entries = journal.readFrom(actorId, 0).get();
        
        // Should only have messages from sequence 5 onwards
        assertEquals(5, entries.size());
        assertEquals(5L, entries.get(0).getSequenceNumber());
    }
    
    @Test
    void testGetHighestSequenceNumber() throws Exception {
        String actorId = "actor-1";
        
        // Initially should be -1 (no messages)
        long highest = journal.getHighestSequenceNumber(actorId).get();
        assertEquals(-1L, highest);
        
        // Append messages
        journal.append(actorId, new TestMessage("msg1", 1)).get();
        journal.append(actorId, new TestMessage("msg2", 2)).get();
        journal.append(actorId, new TestMessage("msg3", 3)).get();
        
        // Should return 2 (last sequence)
        highest = journal.getHighestSequenceNumber(actorId).get();
        assertEquals(2L, highest);
    }
    
    @Test
    void testDedicatedIOExecutor() throws Exception {
        String actorId = "actor-1";
        
        // Append many messages to test executor
        for (int i = 0; i < 100; i++) {
            journal.append(actorId, new TestMessage("msg-" + i, i));
        }
        
        // Wait for completion
        Thread.sleep(500);
        
        // Verify all messages were written
        List<JournalEntry<TestMessage>> entries = journal.readFrom(actorId, 0).get();
        assertEquals(100, entries.size());
    }
    
    @Test
    void testFsyncDisabled() throws Exception {
        // Create journal with fsync disabled
        FileMessageJournal<TestMessage> noFsyncJournal = new FileMessageJournal<>(tempDir.resolve("no-fsync"), false);
        
        try {
            String actorId = "actor-1";
            
            // Append should still work
            long sequence = noFsyncJournal.append(actorId, new TestMessage("test", 1)).get();
            assertEquals(0L, sequence);
            
            // Read should work
            List<JournalEntry<TestMessage>> entries = noFsyncJournal.readFrom(actorId, 0).get();
            assertEquals(1, entries.size());
        } finally {
            noFsyncJournal.close();
        }
    }
    
    @Test
    void testConcurrentWrites() throws Exception {
        String actorId = "actor-1";
        int numThreads = 5;
        int messagesPerThread = 20;
        CountDownLatch latch = new CountDownLatch(numThreads);
        
        // Concurrent writes
        Thread[] threads = new Thread[numThreads];
        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                try {
                    for (int j = 0; j < messagesPerThread; j++) {
                        journal.append(actorId, new TestMessage("thread-" + threadId + "-msg-" + j, j)).get();
                    }
                } catch (Exception e) {
                    fail("Concurrent write failed: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
            threads[i].start();
        }
        
        // Wait for all threads
        assertTrue(latch.await(30, TimeUnit.SECONDS));
        
        // Verify all messages were saved
        List<JournalEntry<TestMessage>> entries = journal.readFrom(actorId, 0).get();
        assertEquals(numThreads * messagesPerThread, entries.size());
    }
    
    @Test
    void testMultipleActors() throws Exception {
        // Append messages for different actors
        journal.append("actor-1", new TestMessage("msg1", 1)).get();
        journal.append("actor-2", new TestMessage("msg2", 2)).get();
        journal.append("actor-1", new TestMessage("msg3", 3)).get();
        
        // Read each actor's messages
        List<JournalEntry<TestMessage>> actor1Entries = journal.readFrom("actor-1", 0).get();
        List<JournalEntry<TestMessage>> actor2Entries = journal.readFrom("actor-2", 0).get();
        
        assertEquals(2, actor1Entries.size());
        assertEquals(1, actor2Entries.size());
    }
    
    @Test
    void testCloseReleasesResources() throws Exception {
        String actorId = "actor-1";
        
        // Append some messages
        journal.append(actorId, new TestMessage("msg1", 1)).get();
        
        // Close journal
        journal.close();
        
        // Further operations should fail
        assertThrows(Exception.class, () -> {
            journal.append(actorId, new TestMessage("msg2", 2)).get();
        });
    }
    
    @Test
    void testRecoveryAfterRestart() throws Exception {
        String actorId = "actor-1";
        
        // Append messages
        journal.append(actorId, new TestMessage("msg1", 1)).get();
        journal.append(actorId, new TestMessage("msg2", 2)).get();
        
        // Close journal
        journal.close();
        
        // Create new journal instance (simulating restart)
        FileMessageJournal<TestMessage> newJournal = new FileMessageJournal<>(tempDir, true);
        
        try {
            // Should be able to read old messages
            List<JournalEntry<TestMessage>> entries = newJournal.readFrom(actorId, 0).get();
            assertEquals(2, entries.size());
            assertEquals("msg1", entries.get(0).getMessage().text);
            assertEquals("msg2", entries.get(1).getMessage().text);
            
            // Should be able to append new messages with correct sequence
            long newSeq = newJournal.append(actorId, new TestMessage("msg3", 3)).get();
            assertEquals(2L, newSeq);
        } finally {
            newJournal.close();
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
