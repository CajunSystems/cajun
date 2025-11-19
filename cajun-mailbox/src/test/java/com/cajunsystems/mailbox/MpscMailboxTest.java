package com.cajunsystems.mailbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
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
    void testDrainToSelf() {
        // Can't test this directly with MpscMailbox since it doesn't implement Collection
        // But we test that the check exists in drainTo
        MpscMailbox<String> mailbox = new MpscMailbox<>();
        assertThrows(IllegalArgumentException.class,
            () -> mailbox.drainTo((List) mailbox, 10));
    }

    @Test
    @Timeout(10)
    void testMultipleProducersSingleConsumer() throws Exception {
        MpscMailbox<Integer> mailbox = new MpscMailbox<>();
        int producerCount = 10;
        int messagesPerProducer = 1000;
        int totalMessages = producerCount * messagesPerProducer;

        CyclicBarrier startBarrier = new CyclicBarrier(producerCount + 1);
        ExecutorService producers = Executors.newFixedThreadPool(producerCount);
        AtomicInteger messageCounter = new AtomicInteger(0);

        // Start producers
        List<Future<?>> producerFutures = new ArrayList<>();
        for (int p = 0; p < producerCount; p++) {
            int producerId = p;
            producerFutures.add(producers.submit(() -> {
                try {
                    startBarrier.await();
                    for (int i = 0; i < messagesPerProducer; i++) {
                        int value = producerId * messagesPerProducer + i;
                        mailbox.offer(value);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }

        // Start consuming
        startBarrier.await();

        // Single consumer
        List<Integer> consumed = new ArrayList<>();
        while (consumed.size() < totalMessages) {
            Integer msg = mailbox.poll(100, TimeUnit.MILLISECONDS);
            if (msg != null) {
                consumed.add(msg);
            }
        }

        // Wait for all producers to finish
        for (Future<?> future : producerFutures) {
            future.get();
        }

        assertEquals(totalMessages, consumed.size());

        producers.shutdown();
        producers.awaitTermination(1, TimeUnit.SECONDS);
    }

    @Test
    @Timeout(5)
    void testSignalingBehavior() throws Exception {
        MpscMailbox<String> mailbox = new MpscMailbox<>();
        CountDownLatch consumerReady = new CountDownLatch(1);
        CountDownLatch messageReceived = new CountDownLatch(1);

        // Consumer thread
        Thread consumer = new Thread(() -> {
            try {
                consumerReady.countDown();
                String msg = mailbox.take();
                if ("test".equals(msg)) {
                    messageReceived.countDown();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        consumer.start();
        consumerReady.await();
        Thread.sleep(50); // Ensure consumer is waiting

        // Producer adds message
        mailbox.offer("test");

        // Consumer should be signaled and receive message
        assertTrue(messageReceived.await(1, TimeUnit.SECONDS));
        consumer.join();
    }

    @Test
    void testSizeAndIsEmpty() {
        MpscMailbox<String> mailbox = new MpscMailbox<>();

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
}
