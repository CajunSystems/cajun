package systems.cajun.flow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the BackpressureAwareQueue class.
 */
public class BackpressureAwareQueueTest {

    @Test
    public void testBasicQueueOperations() {
        BackpressureAwareQueue<String> queue = new BackpressureAwareQueue<>(10);
        
        // Test isEmpty
        assertTrue(queue.isEmpty());
        assertEquals(0, queue.size());
        
        // Test add/offer
        assertTrue(queue.offer("item1"));
        assertFalse(queue.isEmpty());
        assertEquals(1, queue.size());
        
        // Test peek
        assertEquals("item1", queue.peek());
        assertEquals(1, queue.size()); // Peek doesn't remove
        
        // Test poll
        assertEquals("item1", queue.poll());
        assertTrue(queue.isEmpty());
        assertEquals(0, queue.size());
        
        // Test null handling
        assertThrows(NullPointerException.class, () -> queue.offer(null));
    }
    
    @Test
    public void testQueueCapacity() {
        int capacity = 5;
        BackpressureAwareQueue<Integer> queue = new BackpressureAwareQueue<>(capacity);
        
        // Fill the queue to capacity
        for (int i = 0; i < capacity; i++) {
            assertTrue(queue.offer(i));
        }
        
        assertEquals(capacity, queue.size());
        assertEquals(0, queue.remainingCapacity());
        
        // Test behavior when queue is full (depends on backpressure mode)
        // Default is ADAPTIVE, which might accept more items
        boolean accepted = queue.offer(capacity);
        if (accepted) {
            assertEquals(capacity + 1, queue.size());
        } else {
            assertEquals(capacity, queue.size());
        }
    }
    
    @Test
    public void testDropBackpressureMode() {
        int capacity = 5;
        BackpressureAwareQueue<Integer> queue = new BackpressureAwareQueue<>(
                capacity, BackpressureAwareQueue.BackpressureMode.BUFFER_THEN_DROP);
        
        // Fill the queue to capacity
        for (int i = 0; i < capacity; i++) {
            assertTrue(queue.offer(i));
        }
        
        // Additional items should be dropped
        assertFalse(queue.offer(100));
        assertEquals(capacity, queue.size());
        assertEquals(1, queue.getDroppedMessageCount());
        
        // Verify queue contents
        for (int i = 0; i < capacity; i++) {
            assertEquals(i, queue.poll());
        }
        
        assertTrue(queue.isEmpty());
    }
    
    @Test
    @Timeout(5) // Timeout after 5 seconds
    public void testBlockBackpressureMode() throws InterruptedException {
        int capacity = 5;
        BackpressureAwareQueue<Integer> queue = new BackpressureAwareQueue<>(
                capacity, BackpressureAwareQueue.BackpressureMode.BUFFER_THEN_BLOCK);
        
        // Fill the queue to capacity
        for (int i = 0; i < capacity; i++) {
            assertTrue(queue.offer(i));
        }
        
        // Test that offer with timeout returns false when queue is full
        assertFalse(queue.offer(100, 100, TimeUnit.MILLISECONDS));
        
        // Start a thread that will try to add an item (will block)
        CountDownLatch addedLatch = new CountDownLatch(1);
        Thread producer = new Thread(() -> {
            try {
                queue.put(100); // This should block until space is available
                addedLatch.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        producer.start();
        
        // Sleep briefly to ensure the producer thread has started and is blocked
        Thread.sleep(100);
        
        // Remove an item to make space
        assertEquals(0, queue.poll());
        
        // The producer should now be able to add its item
        assertTrue(addedLatch.await(1, TimeUnit.SECONDS));
        
        // Verify queue contents (should have items 1-4 and 100)
        assertEquals(capacity, queue.size());
        
        // Clean up
        producer.join(100);
    }
    
    @Test
    public void testAdaptiveBackpressureMode() {
        int capacity = 10;
        BackpressureAwareQueue<Integer> queue = new BackpressureAwareQueue<>(
                capacity, BackpressureAwareQueue.BackpressureMode.ADAPTIVE);
        
        // Fill the queue partially
        for (int i = 0; i < capacity / 2; i++) {
            assertTrue(queue.offer(i));
        }
        
        // Backpressure level should be higher
        double initialLevel = queue.getCurrentBackpressureLevel();
        assertTrue(initialLevel > 0.0, "Backpressure level should be greater than 0, but was " + initialLevel);
        
        // Add more items to increase backpressure
        for (int i = capacity / 2; i < capacity; i++) {
            assertTrue(queue.offer(i));
        }
        
        // Simulate processing time to affect backpressure
        queue.updateBackpressureLevel(TimeUnit.MILLISECONDS.toNanos(20)); // 20ms is higher than target
        
        // Backpressure level should increase
        double levelAfterSlowProcessing = queue.getCurrentBackpressureLevel();
        assertTrue(levelAfterSlowProcessing >= initialLevel, 
                "Backpressure level should increase or stay the same after slow processing, " +
                "was " + initialLevel + ", now " + levelAfterSlowProcessing);
        
        // Process some messages to reduce backpressure
        for (int i = 0; i < capacity / 2; i++) {
            assertNotNull(queue.poll());
        }
        
        // Simulate fast processing
        queue.updateBackpressureLevel(TimeUnit.MILLISECONDS.toNanos(1)); // 1ms is lower than target
        
        // Backpressure level should decrease
        assertTrue(queue.getCurrentBackpressureLevel() < levelAfterSlowProcessing);
    }
    
    @Test
    public void testMetrics() {
        BackpressureAwareQueue<String> queue = new BackpressureAwareQueue<>(
                5, BackpressureAwareQueue.BackpressureMode.BUFFER_THEN_DROP);
        
        // Add and process some items
        queue.offer("item1");
        queue.offer("item2");
        queue.poll(); // Process one item
        
        // Check metrics
        assertEquals(0, queue.getDroppedMessageCount());
        assertEquals(0, queue.getDelayedMessageCount());
        assertEquals(1, queue.getProcessedMessageCount());
        assertEquals(1, queue.size());
        
        // Fill the queue to capacity (it already has 1 item)
        queue.offer("item3");
        queue.offer("item4");
        queue.offer("item5");
        queue.offer("item6"); // This should still fit
        
        // These should be dropped as the queue is now at capacity
        assertFalse(queue.offer("item7"), "Queue should reject item7 as it's full");
        assertFalse(queue.offer("item8"), "Queue should reject item8 as it's full");
        
        // Check updated metrics
        assertEquals(2, queue.getDroppedMessageCount(), "Should have dropped exactly 2 messages");
        assertEquals(0, queue.getDelayedMessageCount());
        assertEquals(1, queue.getProcessedMessageCount());
        assertEquals(5, queue.size(), "Queue should be at capacity");
        
        // Process all remaining items
        while (!queue.isEmpty()) {
            queue.poll();
        }
        
        // Check final metrics
        assertEquals(2, queue.getDroppedMessageCount());
        assertEquals(0, queue.getDelayedMessageCount());
        assertEquals(6, queue.getProcessedMessageCount());
        assertEquals(0, queue.size());
    }
    
    @Test
    public void testConcurrentAccess() throws InterruptedException {
        int capacity = 100;
        BackpressureAwareQueue<Integer> queue = new BackpressureAwareQueue<>(capacity);
        
        int numProducers = 5;
        int numConsumers = 3;
        int itemsPerProducer = 1000;
        
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch producersDone = new CountDownLatch(numProducers);
        CountDownLatch consumersDone = new CountDownLatch(numConsumers);
        
        AtomicInteger totalConsumed = new AtomicInteger(0);
        
        // Create producer threads
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        
        for (int p = 0; p < numProducers; p++) {
            final int producerId = p;
            executor.submit(() -> {
                try {
                    startLatch.await(); // Wait for start signal
                    
                    for (int i = 0; i < itemsPerProducer; i++) {
                        int item = producerId * itemsPerProducer + i;
                        queue.offer(item);
                    }
                    
                    producersDone.countDown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        
        // Create consumer threads
        for (int c = 0; c < numConsumers; c++) {
            executor.submit(() -> {
                try {
                    startLatch.await(); // Wait for start signal
                    
                    while (true) {
                        Integer item = queue.poll(10, TimeUnit.MILLISECONDS);
                        if (item != null) {
                            totalConsumed.incrementAndGet();
                        } else if (producersDone.getCount() == 0 && queue.isEmpty()) {
                            // If producers are done and queue is empty, we're done
                            break;
                        }
                    }
                    
                    consumersDone.countDown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        
        // Start all threads
        startLatch.countDown();
        
        // Wait for completion
        assertTrue(producersDone.await(10, TimeUnit.SECONDS), "Producers did not complete in time");
        assertTrue(consumersDone.await(10, TimeUnit.SECONDS), "Consumers did not complete in time");
        
        // Verify all items were consumed
        int expectedItems = numProducers * itemsPerProducer;
        int actualConsumed = totalConsumed.get();
        
        // Allow for some dropped messages in adaptive mode
        int droppedMessages = (int) queue.getDroppedMessageCount();
        assertEquals(expectedItems, actualConsumed + droppedMessages, 
                "Expected " + expectedItems + " items, but consumed " + actualConsumed + 
                " and dropped " + droppedMessages);
        
        executor.shutdown();
        assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
    }
    
    @Test
    public void testDrainTo() {
        BackpressureAwareQueue<String> queue = new BackpressureAwareQueue<>(10);
        
        // Add some items
        queue.offer("item1");
        queue.offer("item2");
        queue.offer("item3");
        
        // Drain to a list
        List<String> drained = new ArrayList<>();
        int count = queue.drainTo(drained);
        
        assertEquals(3, count);
        assertEquals(3, drained.size());
        assertEquals("item1", drained.get(0));
        assertEquals("item2", drained.get(1));
        assertEquals("item3", drained.get(2));
        
        assertTrue(queue.isEmpty());
        
        // Test draining with max elements
        queue.offer("item4");
        queue.offer("item5");
        queue.offer("item6");
        
        drained.clear();
        count = queue.drainTo(drained, 2);
        
        assertEquals(2, count);
        assertEquals(2, drained.size());
        assertEquals(1, queue.size());
    }
}
