package com.cajunsystems.mailbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for LinkedMailbox including edge cases:
 * - Null handling
 * - Capacity limits
 * - Thread interruption
 * - Concurrent drain
 * - Timeout behavior
 */
class LinkedMailboxTest {

    @Test
    void testOfferRejectsNull() {
        LinkedMailbox<String> mailbox = new LinkedMailbox<>();
        assertThrows(NullPointerException.class, () -> mailbox.offer(null));
    }

    @Test
    void testOfferWithTimeoutRejectsNull() {
        LinkedMailbox<String> mailbox = new LinkedMailbox<>();
        assertThrows(NullPointerException.class,
            () -> mailbox.offer(null, 1, TimeUnit.SECONDS));
    }

    @Test
    void testPutRejectsNull() {
        LinkedMailbox<String> mailbox = new LinkedMailbox<>();
        assertThrows(NullPointerException.class, () -> mailbox.put(null));
    }

    @Test
    void testBasicOfferAndPoll() {
        LinkedMailbox<String> mailbox = new LinkedMailbox<>();

        assertTrue(mailbox.offer("message1"));
        assertTrue(mailbox.offer("message2"));

        assertEquals("message1", mailbox.poll());
        assertEquals("message2", mailbox.poll());
        assertNull(mailbox.poll());
    }

    @Test
    void testBoundedCapacity() {
        LinkedMailbox<String> mailbox = new LinkedMailbox<>(2);

        assertTrue(mailbox.offer("msg1"));
        assertTrue(mailbox.offer("msg2"));
        assertFalse(mailbox.offer("msg3")); // Should fail - capacity reached

        assertEquals("msg1", mailbox.poll());
        assertTrue(mailbox.offer("msg3")); // Now should succeed
    }

    @Test
    void testPollWithTimeout() throws InterruptedException {
        LinkedMailbox<String> mailbox = new LinkedMailbox<>();

        // Poll from empty queue should timeout
        long start = System.nanoTime();
        String result = mailbox.poll(100, TimeUnit.MILLISECONDS);
        long elapsed = System.nanoTime() - start;

        assertNull(result);
        assertTrue(elapsed >= TimeUnit.MILLISECONDS.toNanos(100));
    }

    @Test
    @Timeout(5)
    void testTakeWithInterruption() throws Exception {
        LinkedMailbox<String> mailbox = new LinkedMailbox<>();
        CountDownLatch threadStarted = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean(false);

        Thread waiter = new Thread(() -> {
            threadStarted.countDown();
            try {
                mailbox.take();
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        });

        waiter.start();
        threadStarted.await();
        Thread.sleep(50); // Give thread time to enter take()
        waiter.interrupt();
        waiter.join(1000);

        assertTrue(interrupted.get(), "Thread should have been interrupted");
    }

    @Test
    @Timeout(5)
    void testPollWithTimeoutInterruption() throws Exception {
        LinkedMailbox<String> mailbox = new LinkedMailbox<>();
        CountDownLatch threadStarted = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean(false);

        Thread waiter = new Thread(() -> {
            threadStarted.countDown();
            try {
                mailbox.poll(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        });

        waiter.start();
        threadStarted.await();
        Thread.sleep(50);
        waiter.interrupt();
        waiter.join(1000);

        assertTrue(interrupted.get(), "Thread should have been interrupted");
    }

    @Test
    void testDrainTo() {
        LinkedMailbox<String> mailbox = new LinkedMailbox<>();
        mailbox.offer("msg1");
        mailbox.offer("msg2");
        mailbox.offer("msg3");

        List<String> drained = new ArrayList<>();
        int count = mailbox.drainTo(drained, 2);

        assertEquals(2, count);
        assertEquals(2, drained.size());
        assertEquals("msg1", drained.get(0));
        assertEquals("msg2", drained.get(1));
        assertEquals(1, mailbox.size());
    }

    @Test
    @Timeout(5)
    void testConcurrentDrain() throws Exception {
        LinkedMailbox<String> mailbox = new LinkedMailbox<>(1000);

        // Fill mailbox
        for (int i = 0; i < 100; i++) {
            mailbox.offer("msg" + i);
        }

        // Concurrent draining
        CyclicBarrier barrier = new CyclicBarrier(3);
        List<Future<Integer>> futures = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(3);

        for (int i = 0; i < 3; i++) {
            futures.add(executor.submit(() -> {
                List<String> local = new ArrayList<>();
                barrier.await();
                return mailbox.drainTo(local, 50);
            }));
        }

        int totalDrained = 0;
        for (Future<Integer> future : futures) {
            totalDrained += future.get();
        }

        assertEquals(100, totalDrained);
        assertEquals(0, mailbox.size());

        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.SECONDS);
    }

    @Test
    void testSizeAndIsEmpty() {
        LinkedMailbox<String> mailbox = new LinkedMailbox<>();

        assertTrue(mailbox.isEmpty());
        assertEquals(0, mailbox.size());

        mailbox.offer("msg1");
        assertFalse(mailbox.isEmpty());
        assertEquals(1, mailbox.size());

        mailbox.offer("msg2");
        assertEquals(2, mailbox.size());

        mailbox.poll();
        assertEquals(1, mailbox.size());

        mailbox.clear();
        assertTrue(mailbox.isEmpty());
        assertEquals(0, mailbox.size());
    }

    @Test
    void testRemainingCapacity() {
        LinkedMailbox<String> mailbox = new LinkedMailbox<>(10);

        assertEquals(10, mailbox.remainingCapacity());

        mailbox.offer("msg1");
        mailbox.offer("msg2");
        assertEquals(8, mailbox.remainingCapacity());

        mailbox.poll();
        assertEquals(9, mailbox.remainingCapacity());
    }

    @Test
    void testCapacity() {
        LinkedMailbox<String> unbounded = new LinkedMailbox<>();
        assertEquals(Integer.MAX_VALUE, unbounded.capacity());

        LinkedMailbox<String> bounded = new LinkedMailbox<>(100);
        assertEquals(100, bounded.capacity());
    }

    @Test
    @Timeout(5)
    void testOfferWithTimeoutSuccess() throws InterruptedException {
        LinkedMailbox<String> mailbox = new LinkedMailbox<>(1);

        // Fill to capacity
        assertTrue(mailbox.offer("msg1"));

        // Start thread that will consume after delay
        Thread consumer = new Thread(() -> {
            try {
                Thread.sleep(100);
                mailbox.poll();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        consumer.start();

        // Offer with timeout - should succeed when consumer frees space
        long start = System.nanoTime();
        boolean offered = mailbox.offer("msg2", 1, TimeUnit.SECONDS);
        long elapsed = System.nanoTime() - start;

        assertTrue(offered);
        assertTrue(elapsed >= TimeUnit.MILLISECONDS.toNanos(100));

        consumer.join();
    }
}
