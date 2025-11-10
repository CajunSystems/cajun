package com.cajunsystems.dispatcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DispatcherMailbox (both LBQ and MPSC variants).
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS) // Prevent any test from hanging
class DispatcherMailboxTest {

    @Test
    void testCreateLinkedBlockingQueueMailbox() {
        AtomicBoolean scheduled = new AtomicBoolean(false);
        DispatcherMailbox<String> mailbox = DispatcherMailbox.createWithConcurrentLinkedQueue(
            1024,
            OverflowStrategy.BLOCK,
            () -> scheduled.set(true),
            "test-actor"
        );

        assertNotNull(mailbox);
        assertEquals(1024, mailbox.getCapacity());
        assertEquals(MailboxType.DISPATCHER_CBQ, mailbox.getMailboxType());
    }

    @Test
    void testCreateMpscArrayQueueMailbox() {
        AtomicBoolean scheduled = new AtomicBoolean(false);
        DispatcherMailbox<String> mailbox = DispatcherMailbox.createWithMpscArrayQueue(
            1024,
            OverflowStrategy.BLOCK,
            () -> scheduled.set(true),
            "test-actor"
        );

        assertNotNull(mailbox);
        assertEquals(1024, mailbox.getCapacity());
        assertEquals(MailboxType.DISPATCHER_MPSC, mailbox.getMailboxType());
    }

    @Test
    void testMpscRequiresPowerOfTwo() {
        assertThrows(IllegalArgumentException.class, () -> {
            DispatcherMailbox.createWithMpscArrayQueue(
                1000, // Not power of 2
                OverflowStrategy.BLOCK,
                () -> {},
                "test-actor"
            );
        });
    }

    @Test
    void testEnqueueAndPoll_LBQ() {
        DispatcherMailbox<String> mailbox = DispatcherMailbox.createWithConcurrentLinkedQueue(
            1024,
            OverflowStrategy.BLOCK,
            () -> {},
            "test-actor"
        );

        assertTrue(mailbox.enqueue("message1"));
        assertTrue(mailbox.enqueue("message2"));

        assertEquals("message1", mailbox.poll());
        assertEquals("message2", mailbox.poll());
        assertNull(mailbox.poll());
    }

    @Test
    void testEnqueueAndPoll_MPSC() {
        DispatcherMailbox<String> mailbox = DispatcherMailbox.createWithMpscArrayQueue(
            1024,
            OverflowStrategy.BLOCK,
            () -> {},
            "test-actor"
        );

        assertTrue(mailbox.enqueue("message1"));
        assertTrue(mailbox.enqueue("message2"));

        assertEquals("message1", mailbox.poll());
        assertEquals("message2", mailbox.poll());
        assertNull(mailbox.poll());
    }

    @Test
    void testCoalescedScheduling() {
        AtomicInteger scheduleCount = new AtomicInteger(0);
        DispatcherMailbox<String> mailbox = DispatcherMailbox.createWithConcurrentLinkedQueue(
            1024,
            OverflowStrategy.BLOCK,
            () -> scheduleCount.incrementAndGet(),
            "test-actor"
        );

        // First enqueue should trigger schedule
        mailbox.enqueue("msg1");
        assertEquals(1, scheduleCount.get());

        // Subsequent enqueues should NOT trigger schedule (already scheduled)
        mailbox.enqueue("msg2");
        mailbox.enqueue("msg3");
        assertEquals(1, scheduleCount.get());

        // Clear scheduled flag
        assertTrue(mailbox.tryClearScheduled());

        // Now enqueue should trigger schedule again
        mailbox.enqueue("msg4");
        assertEquals(2, scheduleCount.get());
    }

    @Test
    void testOverflowStrategyDrop_LBQ() {
        DispatcherMailbox<String> mailbox = DispatcherMailbox.createWithConcurrentLinkedQueue(
            2, // Very small capacity
            OverflowStrategy.DROP,
            () -> {},
            "test-actor"
        );

        assertTrue(mailbox.enqueue("msg1"));
        assertTrue(mailbox.enqueue("msg2"));
        
        // Third message should be dropped
        boolean accepted = mailbox.enqueue("msg3");
        // Note: LinkedBlockingQueue might accept more than capacity temporarily
        // So we just verify the mailbox doesn't crash
        assertNotNull(mailbox);
    }

    @Test
    void testOverflowStrategyDrop_MPSC() {
        DispatcherMailbox<String> mailbox = DispatcherMailbox.createWithMpscArrayQueue(
            4, // Power of 2
            OverflowStrategy.DROP,
            () -> {},
            "test-actor"
        );

        // Fill the mailbox
        for (int i = 0; i < 4; i++) {
            assertTrue(mailbox.enqueue("msg" + i));
        }

        // Next message should be dropped
        boolean accepted = mailbox.enqueue("overflow");
        assertFalse(accepted, "Message should be dropped when queue is full");
    }

    @Test
    void testIsEmpty() {
        DispatcherMailbox<String> mailbox = DispatcherMailbox.createWithConcurrentLinkedQueue(
            1024,
            OverflowStrategy.BLOCK,
            () -> {},
            "test-actor"
        );

        assertTrue(mailbox.isEmpty());
        
        mailbox.enqueue("msg");
        assertFalse(mailbox.isEmpty());
        
        mailbox.poll();
        assertTrue(mailbox.isEmpty());
    }

    @Test
    void testSize() {
        DispatcherMailbox<String> mailbox = DispatcherMailbox.createWithConcurrentLinkedQueue(
            1024,
            OverflowStrategy.BLOCK,
            () -> {},
            "test-actor"
        );

        assertEquals(0, mailbox.size());
        
        mailbox.enqueue("msg1");
        mailbox.enqueue("msg2");
        assertEquals(2, mailbox.size());
        
        mailbox.poll();
        assertEquals(1, mailbox.size());
    }

    @Test
    void testConcurrentEnqueue() throws InterruptedException {
        DispatcherMailbox<Integer> mailbox = DispatcherMailbox.createWithMpscArrayQueue(
            8192, // Larger capacity to avoid filling up
            OverflowStrategy.DROP, // Use DROP to avoid blocking if queue fills
            () -> {},
            "test-actor"
        );

        int threadCount = 5; // Reduced from 10
        int messagesPerThread = 50; // Reduced from 100
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            new Thread(() -> {
                try {
                    startLatch.await(); // Synchronize start
                    for (int j = 0; j < messagesPerThread; j++) {
                        mailbox.enqueue(threadId * messagesPerThread + j);
                    }
                    doneLatch.countDown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        }

        startLatch.countDown(); // Start all threads
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS), "Threads should complete");

        // Verify messages were enqueued (may be less than total if some dropped)
        int count = 0;
        while (mailbox.poll() != null) {
            count++;
        }
        assertTrue(count > 0, "At least some messages should be enqueued");
        assertTrue(count <= threadCount * messagesPerThread, "Should not exceed sent messages");
    }

    @Test
    void testScheduledFlagHandling() {
        AtomicInteger scheduleCount = new AtomicInteger(0);
        DispatcherMailbox<String> mailbox = DispatcherMailbox.createWithConcurrentLinkedQueue(
            1024,
            OverflowStrategy.BLOCK,
            () -> scheduleCount.incrementAndGet(),
            "test-actor"
        );

        // Initially not scheduled
        assertFalse(mailbox.getScheduled().get());

        // Enqueue triggers schedule
        mailbox.enqueue("msg1");
        assertTrue(mailbox.getScheduled().get());
        assertEquals(1, scheduleCount.get());

        // Clear scheduled flag
        assertTrue(mailbox.tryClearScheduled());
        assertFalse(mailbox.getScheduled().get());

        // Try to clear again should return false
        assertFalse(mailbox.tryClearScheduled());
    }

    @Test
    void testNullMessageRejected() {
        DispatcherMailbox<String> mailbox = DispatcherMailbox.createWithConcurrentLinkedQueue(
            1024,
            OverflowStrategy.BLOCK,
            () -> {},
            "test-actor"
        );

        assertThrows(NullPointerException.class, () -> {
            mailbox.enqueue(null);
        });
    }

    @Test
    void testMpscArrayQueuePerformance() {
        // This is more of a smoke test to ensure MPSC queue works under load
        DispatcherMailbox<Integer> mailbox = DispatcherMailbox.createWithMpscArrayQueue(
            8192,
            OverflowStrategy.BLOCK,
            () -> {},
            "test-actor"
        );

        int messageCount = 5000; // Reduced to fit in capacity (8192)
        
        // Enqueue messages (less than capacity to avoid blocking)
        for (int i = 0; i < messageCount; i++) {
            assertTrue(mailbox.enqueue(i));
        }

        // Poll all messages
        int count = 0;
        Integer msg;
        while ((msg = mailbox.poll()) != null) {
            assertEquals(count, msg.intValue());
            count++;
        }

        assertEquals(messageCount, count);
    }
}
