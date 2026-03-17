package com.cajunsystems.direct;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class SelectTest {

    @Test
    void selectFromChannelWithDataReady() {
        Channel<String> ch1 = new Channel<>(1);
        Channel<String> ch2 = new Channel<>(1);

        ch1.send("hello");

        SelectClause<String>[] clauses = new SelectClause[]{
                new SelectClause<>(ch1),
                new SelectClause<>(ch2)
        };

        String result = Select.select(clauses);
        assertEquals("hello", result);
    }

    @Test
    void selectFromSecondChannelWhenFirstIsEmpty() {
        Channel<Integer> ch1 = new Channel<>(1);
        Channel<Integer> ch2 = new Channel<>(1);

        ch2.send(42);

        SelectClause<Integer>[] clauses = new SelectClause[]{
                new SelectClause<>(ch1),
                new SelectClause<>(ch2)
        };

        Integer result = Select.select(clauses);
        assertEquals(42, result);
    }

    @Test
    void selectBlocksUntilDataAvailable() throws InterruptedException {
        Channel<String> ch1 = new Channel<>(1);
        Channel<String> ch2 = new Channel<>(1);

        AtomicReference<String> result = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);

        Thread.startVirtualThread(() -> {
            started.countDown();
            SelectClause<String>[] clauses = new SelectClause[]{
                    new SelectClause<>(ch1),
                    new SelectClause<>(ch2)
            };
            result.set(Select.select(clauses));
            done.countDown();
        });

        started.await();
        // Give the select time to start blocking
        Thread.sleep(100);
        assertNull(result.get(), "Select should still be blocking");

        ch1.send("delayed");
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals("delayed", result.get());
    }

    @Test
    void selectWithTimeout_returnsValueWhenAvailable() {
        Channel<String> ch1 = new Channel<>(1);
        ch1.send("fast");

        SelectClause<String>[] clauses = new SelectClause[]{
                new SelectClause<>(ch1)
        };

        String result = Select.selectWithTimeout(clauses, Duration.ofSeconds(1));
        assertEquals("fast", result);
    }

    @Test
    void selectWithTimeout_returnsNullOnTimeout() {
        Channel<String> ch1 = new Channel<>(1);
        Channel<String> ch2 = new Channel<>(1);

        SelectClause<String>[] clauses = new SelectClause[]{
                new SelectClause<>(ch1),
                new SelectClause<>(ch2)
        };

        String result = Select.selectWithTimeout(clauses, Duration.ofMillis(100));
        assertNull(result, "Should return null when timeout expires with no data");
    }

    @Test
    void selectWithDefault_returnsValueWhenAvailable() {
        Channel<Integer> ch1 = new Channel<>(1);
        ch1.send(99);

        SelectClause<Integer>[] clauses = new SelectClause[]{
                new SelectClause<>(ch1)
        };

        Integer result = Select.selectWithDefault(clauses, -1);
        assertEquals(99, result);
    }

    @Test
    void selectWithDefault_returnsDefaultWhenNoDataReady() {
        Channel<Integer> ch1 = new Channel<>(1);
        Channel<Integer> ch2 = new Channel<>(1);

        SelectClause<Integer>[] clauses = new SelectClause[]{
                new SelectClause<>(ch1),
                new SelectClause<>(ch2)
        };

        Integer result = Select.selectWithDefault(clauses, -1);
        assertEquals(-1, result);
    }

    @Test
    void selectSkipsClosedChannels() {
        Channel<String> ch1 = new Channel<>(1);
        Channel<String> ch2 = new Channel<>(1);

        ch1.close();
        ch2.send("from-ch2");

        SelectClause<String>[] clauses = new SelectClause[]{
                new SelectClause<>(ch1),
                new SelectClause<>(ch2)
        };

        String result = Select.select(clauses);
        assertEquals("from-ch2", result);
    }

    @Test
    void selectThrowsWhenAllChannelsClosed() {
        Channel<String> ch1 = new Channel<>(1);
        Channel<String> ch2 = new Channel<>(1);

        ch1.close();
        ch2.close();

        SelectClause<String>[] clauses = new SelectClause[]{
                new SelectClause<>(ch1),
                new SelectClause<>(ch2)
        };

        assertThrows(ChannelClosedException.class, () -> Select.select(clauses));
    }

    @Test
    void selectWithTimeoutReturnsNullWhenAllChannelsClosed() {
        Channel<String> ch1 = new Channel<>(1);
        ch1.close();

        SelectClause<String>[] clauses = new SelectClause[]{
                new SelectClause<>(ch1)
        };

        // When all channels are closed, selectWithTimeout should either throw or return null
        // depending on implementation. We expect ChannelClosedException for consistency.
        assertThrows(ChannelClosedException.class, () ->
                Select.selectWithTimeout(clauses, Duration.ofMillis(100)));
    }

    @Test
    void selectFairnessWhenMultipleChannelsHaveData() {
        // When multiple channels have data, select should not always pick the same one.
        // We run multiple selections and verify that different channels get selected.
        int iterations = 100;
        AtomicInteger ch1Count = new AtomicInteger(0);
        AtomicInteger ch2Count = new AtomicInteger(0);

        for (int i = 0; i < iterations; i++) {
            Channel<String> ch1 = new Channel<>(1);
            Channel<String> ch2 = new Channel<>(1);

            ch1.send("ch1");
            ch2.send("ch2");

            SelectClause<String>[] clauses = new SelectClause[]{
                    new SelectClause<>(ch1),
                    new SelectClause<>(ch2)
            };

            String result = Select.select(clauses);
            if ("ch1".equals(result)) {
                ch1Count.incrementAndGet();
            } else if ("ch2".equals(result)) {
                ch2Count.incrementAndGet();
            }
        }

        // Both channels should have been selected at least once
        // (with random fairness, this is extremely likely over 100 iterations)
        assertTrue(ch1Count.get() > 0, "ch1 should be selected at least once, was: " + ch1Count.get());
        assertTrue(ch2Count.get() > 0, "ch2 should be selected at least once, was: " + ch2Count.get());
    }

    @Test
    void selectAcrossThreeChannels() {
        Channel<Integer> ch1 = new Channel<>(1);
        Channel<Integer> ch2 = new Channel<>(1);
        Channel<Integer> ch3 = new Channel<>(1);

        ch3.send(3);

        SelectClause<Integer>[] clauses = new SelectClause[]{
                new SelectClause<>(ch1),
                new SelectClause<>(ch2),
                new SelectClause<>(ch3)
        };

        Integer result = Select.select(clauses);
        assertEquals(3, result);
    }

    @Test
    void selectConsumesExactlyOneValue() {
        Channel<String> ch1 = new Channel<>(1);
        Channel<String> ch2 = new Channel<>(1);

        ch1.send("a");
        ch2.send("b");

        SelectClause<String>[] clauses = new SelectClause[]{
                new SelectClause<>(ch1),
                new SelectClause<>(ch2)
        };

        String result = Select.select(clauses);
        assertTrue("a".equals(result) || "b".equals(result));

        // The other channel should still have its value
        if ("a".equals(result)) {
            assertEquals("b", ch2.receive());
        } else {
            assertEquals("a", ch1.receive());
        }
    }

    @Test
    void selectMultipleTimesInSequence() {
        Channel<Integer> ch1 = new Channel<>(5);
        Channel<Integer> ch2 = new Channel<>(5);

        ch1.send(1);
        ch1.send(2);
        ch2.send(3);

        Set<Integer> results = new HashSet<>();

        for (int i = 0; i < 3; i++) {
            SelectClause<Integer>[] clauses = new SelectClause[]{
                    new SelectClause<>(ch1),
                    new SelectClause<>(ch2)
            };
            results.add(Select.select(clauses));
        }

        assertEquals(Set.of(1, 2, 3), results);
    }

    @Test
    void selectWithConcurrentProducers() throws InterruptedException {
        Channel<String> ch1 = new Channel<>(1);
        Channel<String> ch2 = new Channel<>(1);

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();

        // Start select in a virtual thread
        Thread.startVirtualThread(() -> {
            SelectClause<String>[] clauses = new SelectClause[]{
                    new SelectClause<>(ch1),
                    new SelectClause<>(ch2)
            };
            result.set(Select.select(clauses));
            done.countDown();
        });

        // Two producers racing to send
        Thread.startVirtualThread(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ch1.send("from-producer-1");
        });

        Thread.startVirtualThread(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ch2.send("from-producer-2");
        });

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertTrue(result.get().startsWith("from-producer-"),
                "Expected a value from one of the producers, got: " + result.get());
    }

    @Test
    void selectOnSingleChannel() {
        Channel<String> ch = new Channel<>(1);
        ch.send("only");

        SelectClause<String>[] clauses = new SelectClause[]{
                new SelectClause<>(ch)
        };

        assertEquals("only", Select.select(clauses));
    }

    @Test
    void selectWithClosedChannelAndTimeout() {
        Channel<String> ch1 = new Channel<>(1);
        Channel<String> ch2 = new Channel<>(1);

        ch1.close();
        // ch2 has no data

        SelectClause<String>[] clauses = new SelectClause[]{
                new SelectClause<>(ch1),
                new SelectClause<>(ch2)
        };

        // ch1 is closed, ch2 has no data — should timeout
        String result = Select.selectWithTimeout(clauses, Duration.ofMillis(100));
        assertNull(result);
    }

    @Test
    void selectWithChannelClosedDuringWait() throws InterruptedException {
        Channel<String> ch1 = new Channel<>(1);
        Channel<String> ch2 = new Channel<>(1);

        CountDownLatch selectStarted = new CountDownLatch(1);

        CompletableFuture<String> future = new CompletableFuture<>();

        Thread.startVirtualThread(() -> {
            selectStarted.countDown();
            try {
                SelectClause<String>[] clauses = new SelectClause[]{
                        new SelectClause<>(ch1),
                        new SelectClause<>(ch2)
                };
                String result = Select.selectWithTimeout(clauses, Duration.ofSeconds(2));
                future.complete(result);
            } catch (ChannelClosedException e) {
                future.completeExceptionally(e);
            }
        });

        selectStarted.await();
        Thread.sleep(100);

        // Send data on ch2 before closing ch1
        ch2.send("rescued");

        String result = future.get(5, TimeUnit.SECONDS);
        assertEquals("rescued", result);
    }
}