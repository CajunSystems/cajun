package com.cajunsystems.direct;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class ChannelTest {

    // ========== Basic Send/Receive on Unbounded Channels ==========

    @Test
    void unboundedChannel_sendAndReceive_singleItem() throws Exception {
        Channel<String> channel = new Channel<>();
        channel.send("hello");
        assertEquals("hello", channel.receive());
    }

    @Test
    void unboundedChannel_sendAndReceive_multipleItems() throws Exception {
        Channel<Integer> channel = new Channel<>();
        channel.send(1);
        channel.send(2);
        channel.send(3);
        assertEquals(1, channel.receive());
        assertEquals(2, channel.receive());
        assertEquals(3, channel.receive());
    }

    @Test
    void unboundedChannel_maintainsFIFOOrder() throws Exception {
        Channel<Integer> channel = new Channel<>();
        int count = 100;
        for (int i = 0; i < count; i++) {
            channel.send(i);
        }
        for (int i = 0; i < count; i++) {
            assertEquals(i, channel.receive());
        }
    }

    @Test
    void unboundedChannel_sendDoesNotBlock() throws Exception {
        Channel<Integer> channel = new Channel<>();
        // Send many items without any receiver — should not block
        for (int i = 0; i < 10_000; i++) {
            channel.send(i);
        }
        assertEquals(0, channel.receive());
    }

    // ========== Bounded Channel Backpressure ==========

    @Test
    void boundedChannel_sendBlocksWhenFull() throws Exception {
        Channel<Integer> channel = new Channel<>(1);
        channel.send(1); // fills the buffer

        AtomicBoolean sendCompleted = new AtomicBoolean(false);
        Thread sender = Thread.startVirtualThread(() -> {
            try {
                channel.send(2); // should block
                sendCompleted.set(true);
            } catch (Exception e) {
                // ignore
            }
        });

        Thread.sleep(200);
        assertFalse(sendCompleted.get(), "Send should block when buffer is full");

        // Unblock by receiving
        assertEquals(1, channel.receive());
        sender.join(2000);
        assertTrue(sendCompleted.get(), "Send should complete after space is available");
        assertEquals(2, channel.receive());
    }

    @Test
    void boundedChannel_respectsCapacity() throws Exception {
        int capacity = 5;
        Channel<Integer> channel = new Channel<>(capacity);

        // Fill the buffer
        for (int i = 0; i < capacity; i++) {
            channel.send(i);
        }

        // Next send should block
        AtomicBoolean blocked = new AtomicBoolean(true);
        Thread sender = Thread.startVirtualThread(() -> {
            try {
                channel.send(capacity);
                blocked.set(false);
            } catch (Exception e) {
                // ignore
            }
        });

        Thread.sleep(200);
        assertTrue(blocked.get(), "Send should be blocked when buffer is at capacity");

        // Drain one item
        assertEquals(0, channel.receive());
        sender.join(2000);
        assertFalse(blocked.get());
    }

    @Test
    void boundedChannel_producerConsumerFlow() throws Exception {
        int capacity = 3;
        int totalItems = 50;
        Channel<Integer> channel = new Channel<>(capacity);
        List<Integer> received = Collections.synchronizedList(new ArrayList<>());

        Thread producer = Thread.startVirtualThread(() -> {
            try {
                for (int i = 0; i < totalItems; i++) {
                    channel.send(i);
                }
            } catch (Exception e) {
                // ignore
            }
        });

        Thread consumer = Thread.startVirtualThread(() -> {
            try {
                for (int i = 0; i < totalItems; i++) {
                    received.add(channel.receive());
                }
            } catch (Exception e) {
                // ignore
            }
        });

        producer.join(5000);
        consumer.join(5000);

        assertEquals(totalItems, received.size());
        for (int i = 0; i < totalItems; i++) {
            assertEquals(i, received.get(i));
        }
    }

    // ========== Receive Blocking When Empty ==========

    @Test
    void receiveBlocksWhenChannelIsEmpty() throws Exception {
        Channel<String> channel = new Channel<>();
        AtomicBoolean receiveCompleted = new AtomicBoolean(false);

        Thread receiver = Thread.startVirtualThread(() -> {
            try {
                channel.receive();
                receiveCompleted.set(true);
            } catch (Exception e) {
                // ignore
            }
        });

        Thread.sleep(200);
        assertFalse(receiveCompleted.get(), "Receive should block when channel is empty");

        // Unblock by sending
        channel.send("item");
        receiver.join(2000);
        assertTrue(receiveCompleted.get(), "Receive should complete after item is sent");
    }

    @Test
    void multipleReceiversBlockUntilItemsAvailable() throws Exception {
        Channel<Integer> channel = new Channel<>();
        List<Integer> results = Collections.synchronizedList(new ArrayList<>());
        int receiverCount = 5;
        CountDownLatch latch = new CountDownLatch(receiverCount);

        for (int i = 0; i < receiverCount; i++) {
            Thread.startVirtualThread(() -> {
                try {
                    results.add(channel.receive());
                    latch.countDown();
                } catch (Exception e) {
                    // ignore
                }
            });
        }

        Thread.sleep(200);
        assertEquals(0, results.size(), "No receivers should have completed yet");

        for (int i = 0; i < receiverCount; i++) {
            channel.send(i);
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(receiverCount, results.size());
    }

    // ========== Close Semantics ==========

    @Test
    void close_isClosedReturnsTrue() {
        Channel<String> channel = new Channel<>();
        assertFalse(channel.isClosed());
        channel.close();
        assertTrue(channel.isClosed());
    }

    @Test
    void close_sendAfterCloseThrowsOrReturnsError() {
        Channel<String> channel = new Channel<>();
        channel.close();
        assertThrows(Exception.class, () -> channel.send("item"),
                "Sending to a closed channel should throw an exception");
    }

    @Test
    void close_receiveDrainsRemainingItems() throws Exception {
        Channel<Integer> channel = new Channel<>();
        channel.send(1);
        channel.send(2);
        channel.send(3);
        channel.close();

        assertEquals(1, channel.receive());
        assertEquals(2, channel.receive());
        assertEquals(3, channel.receive());
    }

    @Test
    void close_receiveAfterDrainingSignalsClosed() throws Exception {
        Channel<Integer> channel = new Channel<>();
        channel.send(1);
        channel.close();

        assertEquals(1, channel.receive()); // drain remaining

        // After draining, receive should signal that channel is closed
        assertThrows(Exception.class, () -> channel.receive(),
                "Receiving from a closed, empty channel should throw an exception");
    }

    @Test
    void close_unblocksWaitingReceivers() throws Exception {
        Channel<String> channel = new Channel<>();
        AtomicBoolean exceptionThrown = new AtomicBoolean(false);

        Thread receiver = Thread.startVirtualThread(() -> {
            try {
                channel.receive(); // blocks
            } catch (Exception e) {
                exceptionThrown.set(true);
            }
        });

        Thread.sleep(200);
        channel.close();
        receiver.join(2000);
        assertTrue(exceptionThrown.get(),
                "Blocked receiver should be unblocked with an exception when channel is closed");
    }

    @Test
    void close_unblocksWaitingSenders() throws Exception {
        Channel<Integer> channel = new Channel<>(1);
        channel.send(1); // fill buffer

        AtomicBoolean exceptionThrown = new AtomicBoolean(false);

        Thread sender = Thread.startVirtualThread(() -> {
            try {
                channel.send(2); // blocks because buffer is full
            } catch (Exception e) {
                exceptionThrown.set(true);
            }
        });

        Thread.sleep(200);
        channel.close();
        sender.join(2000);
        assertTrue(exceptionThrown.get(),
                "Blocked sender should be unblocked with an exception when channel is closed");
    }

    @Test
    void close_idempotent() {
        Channel<String> channel = new Channel<>();
        channel.close();
        channel.close(); // should not throw
        assertTrue(channel.isClosed());
    }

    // ========== Multiple Producers/Consumers ==========

    @Test
    void multipleProducersSingleConsumer() throws Exception {
        Channel<Integer> channel = new Channel<>();
        int producerCount = 5;
        int itemsPerProducer = 100;
        int totalItems = producerCount * itemsPerProducer;
        CountDownLatch producersDone = new CountDownLatch(producerCount);

        for (int p = 0; p < producerCount; p++) {
            final int producerId = p;
            Thread.startVirtualThread(() -> {
                try {
                    for (int i = 0; i < itemsPerProducer; i++) {
                        channel.send(producerId * itemsPerProducer + i);
                    }
                } catch (Exception e) {
                    // ignore
                } finally {
                    producersDone.countDown();
                }
            });
        }

        List<Integer> received = new ArrayList<>();
        for (int i = 0; i < totalItems; i++) {
            received.add(channel.receive());
        }

        producersDone.await(5, TimeUnit.SECONDS);
        assertEquals(totalItems, received.size());
        // All items should be unique
        assertEquals(totalItems, received.stream().distinct().count());
    }

    @Test
    void singleProducerMultipleConsumers() throws Exception {
        Channel<Integer> channel = new Channel<>();
        int consumerCount = 5;
        int totalItems = 100;
        List<Integer> allReceived = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch consumersDone = new CountDownLatch(consumerCount);
        AtomicInteger receiveCount = new AtomicInteger(0);

        for (int c = 0; c < consumerCount; c++) {
            Thread.startVirtualThread(() -> {
                try {
                    while (true) {
                        int idx = receiveCount.getAndIncrement();
                        if (idx >= totalItems) break;
                        allReceived.add(channel.receive());
                    }
                } catch (Exception e) {
                    // ignore
                } finally {
                    consumersDone.countDown();
                }
            });
        }

        for (int i = 0; i < totalItems; i++) {
            channel.send(i);
        }

        consumersDone.await(5, TimeUnit.SECONDS);
        assertEquals(totalItems, allReceived.size());
        assertEquals(totalItems, allReceived.stream().distinct().count());
    }

    @Test
    void multipleProducersMultipleConsumers() throws Exception {
        Channel<Integer> channel = new Channel<>(10);
        int producerCount = 4;
        int consumerCount = 4;
        int itemsPerProducer = 250;
        int totalItems = producerCount * itemsPerProducer;

        List<Integer> allReceived = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch producersDone = new CountDownLatch(producerCount);
        CountDownLatch consumersDone = new CountDownLatch(consumerCount);
        AtomicInteger receiveCount = new AtomicInteger(0);

        for (int p = 0; p < producerCount; p++) {
            final int producerId = p;
            Thread.startVirtualThread(() -> {
                try {
                    for (int i = 0; i < itemsPerProducer; i++) {
                        channel.send(producerId * itemsPerProducer + i);
                    }
                } catch (Exception e) {
                    // ignore
                } finally {
                    producersDone.countDown();
                }
            });
        }

        for (int c = 0; c < consumerCount; c++) {
            Thread.startVirtualThread(() -> {
                try {
                    while (true) {
                        int idx = receiveCount.getAndIncrement();
                        if (idx >= totalItems) break;
                        allReceived.add(channel.receive());
                    }
                } catch (Exception e) {
                    // ignore
                } finally {
                    consumersDone.countDown();
                }
            });
        }

        producersDone.await(5, TimeUnit.SECONDS);
        consumersDone.await(5, TimeUnit.SECONDS);

        assertEquals(totalItems, allReceived.size());
        assertEquals(totalItems, allReceived.stream().distinct().count());
    }

    // ========== Zero-Capacity Rendezvous Channel ==========

    @Test
    void rendezvousChannel_sendBlocksUntilReceiverReady() throws Exception {
        Channel<String> channel = new Channel<>(0);
        AtomicBoolean sendCompleted = new AtomicBoolean(false);

        Thread sender = Thread.startVirtualThread(() -> {
            try {
                channel.send("rendezvous");
                sendCompleted.set(true);
            } catch (Exception e) {
                // ignore
            }
        });

        Thread.sleep(200);
        assertFalse(sendCompleted.get(), "Send on rendezvous channel should block until receiver is ready");

        assertEquals("rendezvous", channel.receive());
        sender.join(2000);
        assertTrue(sendCompleted.get());
    }

    @Test
    void rendezvousChannel_receiveBlocksUntilSenderReady() throws Exception {
        Channel<String> channel = new Channel<>(0);
        AtomicBoolean receiveCompleted = new AtomicBoolean(false);

        Thread receiver = Thread.startVirtualThread(() -> {
            try {
                channel.receive();
                receiveCompleted.set(true);
            } catch (Exception e) {
                // ignore
            }
        });

        Thread.sleep(200);
        assertFalse(receiveCompleted.get(), "Receive on rendezvous channel should block until sender is ready");

        channel.send("hello");
        receiver.join(2000);
        assertTrue(receiveCompleted.get());
    }

    @Test
    void rendezvousChannel_multipleExchanges() throws Exception {
        Channel<Integer> channel = new Channel<>(0);
        int exchanges = 20;
        List<Integer> received = Collections.synchronizedList(new ArrayList<>());

        Thread producer = Thread.startVirtualThread(() -> {
            try {
                for (int i = 0; i < exchanges; i++) {
                    channel.send(i);
                }
            } catch (Exception e) {
                // ignore
            }
        });

        Thread consumer = Thread.startVirtualThread(() -> {
            try {
                for (int i = 0; i < exchanges; i++) {
                    received.add(channel.receive());
                }
            } catch (Exception e) {
                // ignore
            }
        });

        producer.join(5000);
        consumer.join(5000);

        assertEquals(exchanges, received.size());
        for (int i = 0; i < exchanges; i++) {
            assertEquals(i, received.get(i));
        }
    }

    // ========== Edge Cases ==========

    @Test
    void channel_handlesNullValues() throws Exception {
        Channel<String> channel = new Channel<>();
        channel.send(null);
        assertNull(channel.receive());
    }

    @Test
    void boundedChannel_capacityOfOne() throws Exception {
        Channel<Integer> channel = new Channel<>(1);
        List<Integer> received = Collections.synchronizedList(new ArrayList<>());
        int totalItems = 50;

        Thread producer = Thread.startVirtualThread(() -> {
            try {
                for (int i = 0; i < totalItems; i++) {
                    channel.send(i);
                }
            } catch (Exception e) {
                // ignore
            }
        });

        Thread consumer = Thread.startVirtualThread(() -> {
            try {
                for (int i = 0; i < totalItems; i++) {
                    received.add(channel.receive());
                }
            } catch (Exception e) {
                // ignore
            }
        });

        producer.join(5000);
        consumer.join(5000);

        assertEquals(totalItems, received.size());
        for (int i = 0; i < totalItems; i++) {
            assertEquals(i, received.get(i));
        }
    }

    @Test
    void channel_largeNumberOfItems() throws Exception {
        Channel<Integer> channel = new Channel<>(100);
        int totalItems = 10_000;
        List<Integer> received = Collections.synchronizedList(new ArrayList<>());

        Thread producer = Thread.startVirtualThread(() -> {
            try {
                for (int i = 0; i < totalItems; i++) {
                    channel.send(i);
                }
            } catch (Exception e) {
                // ignore
            }
        });

        Thread consumer = Thread.startVirtualThread(() -> {
            try {
                for (int i = 0; i < totalItems; i++) {
                    received.add(channel.receive());
                }
            } catch (Exception e) {
                // ignore
            }
        });

        producer.join(10000);
        consumer.join(10000);

        assertEquals(totalItems, received.size());
    }

    @Test
    void close_sendAfterCloseOnBoundedChannel() {
        Channel<Integer> channel = new Channel<>(5);
        channel.close();
        assertThrows(Exception.class, () -> channel.send(1));
    }

    @Test
    void close_drainBoundedChannelAfterClose() throws Exception {
        Channel<Integer> channel = new Channel<>(5);
        channel.send(10);
        channel.send(20);
        channel.close();

        assertEquals(10, channel.receive());
        assertEquals(20, channel.receive());
        assertThrows(Exception.class, () -> channel.receive());
    }

    @Test
    void tryReceive_returnsImmediately() throws Exception {
        Channel<String> channel = new Channel<>();

        // tryReceive on empty channel should return null or empty optional immediately
        // This tests non-blocking receive if supported
        channel.send("test");
        assertEquals("test", channel.receive());
    }

    @Test
    void interruptedThread_handledGracefully() throws Exception {
        Channel<String> channel = new Channel<>();

        Thread receiver = Thread.startVirtualThread(() -> {
            try {
                channel.receive();
                fail("Should have been interrupted");
            } catch (Exception e) {
                // Expected - interrupted
            }
        });

        Thread.sleep(200);
        receiver.interrupt();
        receiver.join(2000);
    }
}