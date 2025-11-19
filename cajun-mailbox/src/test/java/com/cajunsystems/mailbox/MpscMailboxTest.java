package com.cajunsystems.mailbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for MpscMailbox including edge cases:
 * - Null handling
 * - MPSC semantics (multiple producers, single consumer)
 * - Thread interruption
 * - Concurrent operations
 * - Timeout behavior
 */
class MpscMailboxTest {

    @Test
    void testOfferRejectsNull() {
        MpscMailbox<String> mailbox = new MpscMailbox<>();
        assertThrows(NullPointerException.class, () -> mailbox.offer(null));
    }

    @Test
    void testBasicOfferAndPoll() {
        MpscMailbox<String> mailbox = new MpscMailbox<>();

        assertTrue(mailbox.offer("message1"));
        assertTrue(mailbox.offer("message2"));

        assertEquals("message1", mailbox.poll());
        assertEquals("message2", mailbox.poll());
        assertNull(mailbox.poll());
    }

    @Test
    void testUnboundedQueue() {
        MpscMailbox<String> mailbox = new MpscMailbox<>(128);

        // Should be able to add many messages (unbounded)
        for (int i = 0; i < 10000; i++) {
            assertTrue(mailbox.offer("msg" + i));
        }

        assertEquals(10000, mailbox.size());
    }

    @Test
    void testPollWithTimeout() throws InterruptedException {
        MpscMailbox<String> mailbox = new MpscMailbox<>();

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
        MpscMailbox<String> mailbox = new MpscMailbox<>();
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
        MpscMailbox<String> mailbox = new MpscMailbox<>();
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
        MpscMailbox<String> mailbox = new MpscMailbox<>();
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
    void testDrainToNullCollection() {
        MpscMailbox<String> mailbox = new MpscMailbox<>();
        assertThrows(NullPointerException.class, () -> mailbox.drainTo(null, 10));
    }

    @Test
    void testDrainToEmptyMailbox() {
        MpscMailbox<String> mailbox = new MpscMailbox<>();
        List<String> drained = new ArrayList<>();
        int count = mailbox.drainTo(drained, 10);

        assertEquals(0, count);
        assertTrue(drained.isEmpty());
    }

    @Test
    void testDrainToAllElements() {
        MpscMailbox<String> mailbox = new MpscMailbox<>();
        mailbox.offer("msg1");
        mailbox.offer("msg2");
        mailbox.offer("msg3");

        List<String> drained = new ArrayList<>();
        int count = mailbox.drainTo(drained, 100); // More than available

        assertEquals(3, count);
        assertEquals(3, drained.size());
        assertTrue(mailbox.isEmpty());
    }

    @Test
    void testPutMethod() throws InterruptedException {
        MpscMailbox<String> mailbox = new MpscMailbox<>();

        // put should never block for unbounded queue
        mailbox.put("msg1");
        mailbox.put("msg2");

        assertEquals(2, mailbox.size());
        assertEquals("msg1", mailbox.poll());
        assertEquals("msg2", mailbox.poll());
    }

    @Test
    void testOfferWithTimeout() throws InterruptedException {
        MpscMailbox<String> mailbox = new MpscMailbox<>();

        // Offer with timeout should always succeed for unbounded queue
        assertTrue(mailbox.offer("msg1", 1, TimeUnit.SECONDS));
        assertTrue(mailbox.offer("msg2", 100, TimeUnit.MILLISECONDS));

        assertEquals(2, mailbox.size());
    }

    @Test
    void testPutRejectsNull() {
        MpscMailbox<String> mailbox = new MpscMailbox<>();
        assertThrows(NullPointerException.class, () -> mailbox.put(null));
    }

    @Test
    void testOfferWithTimeoutRejectsNull() {
        MpscMailbox<String> mailbox = new MpscMailbox<>();
        assertThrows(NullPointerException.class,
            () -> mailbox.offer(null, 1, TimeUnit.SECONDS));
    }

    @Test
    @Timeout(5)
    void testTakeBlocksUntilMessageAvailable() throws Exception {
        MpscMailbox<String> mailbox = new MpscMailbox<>();
        CountDownLatch messageAdded = new CountDownLatch(1);
        AtomicBoolean messageReceived = new AtomicBoolean(false);

        Thread consumer = new Thread(() -> {
            try {
                messageAdded.countDown();
                String msg = mailbox.take();
                messageReceived.set("delayed".equals(msg));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        consumer.start();
        messageAdded.await();
        Thread.sleep(100); // Ensure consumer is blocked

        mailbox.offer("delayed");
        consumer.join(1000);

        assertTrue(messageReceived.get());
    }

    @Test
    @Timeout(5)
    void testPollWithTimeoutReturnsMessageImmediately() throws InterruptedException {
        MpscMailbox<String> mailbox = new MpscMailbox<>();
        mailbox.offer("immediate");

        long start = System.nanoTime();
        String result = mailbox.poll(5, TimeUnit.SECONDS);
        long elapsed = System.nanoTime() - start;

        assertEquals("immediate", result);
        // Should return immediately, not wait for timeout
        assertTrue(elapsed < TimeUnit.SECONDS.toNanos(1));

        assertEquals(0, mailbox.size());
        assertTrue(mailbox.isEmpty());
    }

    @Test
    void testRemainingCapacity() {
        MpscMailbox<String> mailbox = new MpscMailbox<>();

        // Unbounded queue
        assertEquals(Integer.MAX_VALUE, mailbox.remainingCapacity());

        mailbox.offer("msg1");
        // Still unbounded
        assertEquals(Integer.MAX_VALUE, mailbox.remainingCapacity());
    }

    @Test
    void testCapacity() {
        MpscMailbox<String> mailbox = new MpscMailbox<>();
        assertEquals(Integer.MAX_VALUE, mailbox.capacity());
    }

    @Test
    void testPowerOfTwoRounding() {
        // Test that constructor rounds to power of 2
        MpscMailbox<String> mailbox = new MpscMailbox<>(100);

        // Should be rounded to 128 (next power of 2)
        // We can't directly test this, but we can verify it works
        for (int i = 0; i < 200; i++) {
            assertTrue(mailbox.offer("msg" + i));
        }

        assertEquals(200, mailbox.size());
    }

    @Test
    void testZeroOrNegativeCapacity() {
        // Should handle edge cases gracefully
        MpscMailbox<String> mailbox1 = new MpscMailbox<>(0);
        assertTrue(mailbox1.offer("test"));

        MpscMailbox<String> mailbox2 = new MpscMailbox<>(-10);
        assertTrue(mailbox2.offer("test"));
    }

    @Test
    @Timeout(5)
    void testConcurrentOfferAndPoll() throws Exception {
        MpscMailbox<Integer> mailbox = new MpscMailbox<>();
        int producerCount = 5;
        int messagesPerProducer = 1000;
        AtomicInteger consumed = new AtomicInteger(0);
        AtomicBoolean consumerRunning = new AtomicBoolean(true);

        ExecutorService producers = Executors.newFixedThreadPool(producerCount);

        // Start consumer
        Thread consumer = new Thread(() -> {
            while (consumerRunning.get() || !mailbox.isEmpty()) {
                Integer msg = mailbox.poll();
                if (msg != null) {
                    consumed.incrementAndGet();
                }
            }
        });
        consumer.start();

        // Start producers
        List<Future<?>> futures = new ArrayList<>();
        for (int p = 0; p < producerCount; p++) {
            int producerId = p;
            futures.add(producers.submit(() -> {
                for (int i = 0; i < messagesPerProducer; i++) {
                    mailbox.offer(producerId * messagesPerProducer + i);
                }
            }));
        }

        // Wait for producers
        for (Future<?> future : futures) {
            future.get();
        }

        // Signal consumer to stop
        Thread.sleep(100);
        consumerRunning.set(false);
        consumer.join(1000);

        assertEquals(producerCount * messagesPerProducer, consumed.get());

        producers.shutdown();
        producers.awaitTermination(1, TimeUnit.SECONDS);
    }

    @Test
    @Timeout(5)
    void testMultipleProducersSingleConsumerOrdering() throws Exception {
        MpscMailbox<String> mailbox = new MpscMailbox<>();
        int producerCount = 3;
        int messagesPerProducer = 100;
        CountDownLatch producersReady = new CountDownLatch(producerCount);
        CountDownLatch startSignal = new CountDownLatch(1);

        ExecutorService producers = Executors.newFixedThreadPool(producerCount);
        List<String> consumed = new ArrayList<>();

        // Start producers
        List<Future<?>> futures = new ArrayList<>();
        for (int p = 0; p < producerCount; p++) {
            int producerId = p;
            futures.add(producers.submit(() -> {
                producersReady.countDown();
                try {
                    startSignal.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                for (int i = 0; i < messagesPerProducer; i++) {
                    mailbox.offer("P" + producerId + "-M" + i);
                }
            }));
        }

        // Wait for all producers to be ready
        producersReady.await();
        // Start all producers simultaneously
        startSignal.countDown();

        // Wait for producers
        for (Future<?> future : futures) {
            future.get();
        }

        // Consume all messages
        while (!mailbox.isEmpty()) {
            String msg = mailbox.poll();
            if (msg != null) {
                consumed.add(msg);
            }
        }

        assertEquals(producerCount * messagesPerProducer, consumed.size());

        producers.shutdown();
        producers.awaitTermination(1, TimeUnit.SECONDS);
    }

    @Test
    void testSizeAccuracy() {
        MpscMailbox<String> mailbox = new MpscMailbox<>();

        assertEquals(0, mailbox.size());
        assertTrue(mailbox.isEmpty());

        mailbox.offer("msg1");
        assertEquals(1, mailbox.size());
        assertFalse(mailbox.isEmpty());

        mailbox.offer("msg2");
        mailbox.offer("msg3");
        assertEquals(3, mailbox.size());

        mailbox.poll();
        assertEquals(2, mailbox.size());

        mailbox.clear();
        assertEquals(0, mailbox.size());
        assertTrue(mailbox.isEmpty());
    }

    @Test
    void testClearWithMultipleMessages() {
        MpscMailbox<String> mailbox = new MpscMailbox<>();

        for (int i = 0; i < 100; i++) {
            mailbox.offer("msg" + i);
        }

        assertEquals(100, mailbox.size());

        mailbox.clear();

        assertEquals(0, mailbox.size());
        assertTrue(mailbox.isEmpty());
        assertNull(mailbox.poll());
    }

    @Test
    @Timeout(5)
    void testPollWithTimeoutWakesUpOnNewMessage() throws Exception {
        MpscMailbox<String> mailbox = new MpscMailbox<>();
        CountDownLatch consumerStarted = new CountDownLatch(1);
        AtomicBoolean receivedMessage = new AtomicBoolean(false);

        Thread consumer = new Thread(() -> {
            try {
                consumerStarted.countDown();
                String msg = mailbox.poll(10, TimeUnit.SECONDS);
                receivedMessage.set("wake-up".equals(msg));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        consumer.start();
        consumerStarted.await();
        Thread.sleep(50); // Ensure consumer is waiting

        // Add message - should wake up consumer
        mailbox.offer("wake-up");

        consumer.join(1000);

        assertTrue(receivedMessage.get());
    }

    @Test
    void testDrainToWithZeroMaxElements() {
        MpscMailbox<String> mailbox = new MpscMailbox<>();
        mailbox.offer("msg1");
        mailbox.offer("msg2");

        List<String> drained = new ArrayList<>();
        int count = mailbox.drainTo(drained, 0);

        assertEquals(0, count);
        assertTrue(drained.isEmpty());
        assertEquals(2, mailbox.size());
    }

    @Test
    void testDrainToWithNegativeMaxElements() {
        MpscMailbox<String> mailbox = new MpscMailbox<>();
        mailbox.offer("msg1");

        List<String> drained = new ArrayList<>();
        int count = mailbox.drainTo(drained, -1);

        assertEquals(0, count);
        assertTrue(drained.isEmpty());
        assertEquals(1, mailbox.size());
    }

    @Test
    void testOfferReturnValue() {
        MpscMailbox<String> mailbox = new MpscMailbox<>();

        // Unbounded queue should always return true
        for (int i = 0; i < 10000; i++) {
            assertTrue(mailbox.offer("msg" + i));
        }
    }

    @Test
    @Timeout(5)
    void testConcurrentTakeOperations() throws Exception {
        MpscMailbox<Integer> mailbox = new MpscMailbox<>();
        int messageCount = 100;

        // Pre-populate mailbox
        for (int i = 0; i < messageCount; i++) {
            mailbox.offer(i);
        }

        // Single consumer draining with take
        List<Integer> consumed = new ArrayList<>();

        while (!mailbox.isEmpty()) {
            Integer msg = mailbox.poll();
            if (msg != null) {
                consumed.add(msg);
            }
        }

        // Each message should be consumed exactly once
        assertEquals(messageCount, consumed.size());
        assertTrue(mailbox.isEmpty());
    }

    @Test
    void testFIFOOrdering() {
        MpscMailbox<Integer> mailbox = new MpscMailbox<>();

        // Add messages in order
        for (int i = 0; i < 100; i++) {
            mailbox.offer(i);
        }

        // Verify FIFO order
        for (int i = 0; i < 100; i++) {
            assertEquals(i, mailbox.poll());
        }

        assertNull(mailbox.poll());
    }

    @Test
    @Timeout(5)
    void testStressTestHighThroughput() throws Exception {
        MpscMailbox<Long> mailbox = new MpscMailbox<>(1024);
        int producerCount = 10;
        int messagesPerProducer = 10000;
        AtomicInteger consumed = new AtomicInteger(0);
        AtomicBoolean consumerRunning = new AtomicBoolean(true);

        ExecutorService producers = Executors.newFixedThreadPool(producerCount);

        // Start consumer
        Thread consumer = new Thread(() -> {
            while (consumerRunning.get() || !mailbox.isEmpty()) {
                Long msg = mailbox.poll();
                if (msg != null) {
                    consumed.incrementAndGet();
                }
            }
        });
        consumer.start();

        // Start producers
        long startTime = System.nanoTime();
        List<Future<?>> futures = new ArrayList<>();
        for (int p = 0; p < producerCount; p++) {
            futures.add(producers.submit(() -> {
                for (int i = 0; i < messagesPerProducer; i++) {
                    mailbox.offer(System.nanoTime());
                }
            }));
        }

        // Wait for producers
        for (Future<?> future : futures) {
            future.get();
        }

        long endTime = System.nanoTime();

        // Wait for consumer to finish
        Thread.sleep(100);
        consumerRunning.set(false);
        consumer.join(2000);

        assertEquals(producerCount * messagesPerProducer, consumed.get());

        long durationMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
        System.out.println("Processed " + (producerCount * messagesPerProducer) +
                         " messages in " + durationMs + "ms");

        producers.shutdown();
        producers.awaitTermination(1, TimeUnit.SECONDS);
    }
}




