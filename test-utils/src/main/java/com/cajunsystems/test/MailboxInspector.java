package com.cajunsystems.test;

import com.cajunsystems.Actor;

import java.lang.reflect.Field;
import java.time.Duration;

/**
 * Inspector for examining mailbox state and backpressure metrics during testing.
 * Provides visibility into message queue depth, capacity, and processing rates.
 * 
 * <p>Usage:
 * <pre>{@code
 * TestPid<Message> actor = testKit.spawn(MyHandler.class);
 * MailboxInspector inspector = MailboxInspector.create(actor, testKit.system());
 * 
 * // Send messages
 * actor.tell(new Message());
 * 
 * // Inspect mailbox state
 * assertTrue(inspector.size() > 0);
 * assertTrue(inspector.fillRatio() < 0.8);
 * }</pre>
 */
public class MailboxInspector {
    
    private final Actor<?> actor;
    
    private MailboxInspector(Actor<?> actor) {
        this.actor = actor;
    }
    
    /**
     * Creates a MailboxInspector for the given actor.
     * 
     * @param testPid the test pid wrapping the actor
     * @param system the actor system (used to look up the actor)
     * @return a MailboxInspector instance
     */
    public static MailboxInspector create(TestPid<?> testPid, com.cajunsystems.ActorSystem system) {
        var actor = getActorFromSystem(testPid.actorId(), system);
        return new MailboxInspector(actor);
    }
    
    /**
     * Gets the current number of messages in the mailbox.
     * 
     * @return the current mailbox size
     */
    public int size() {
        return actor.getCurrentSize();
    }
    
    /**
     * Gets the maximum capacity of the mailbox.
     * 
     * @return the mailbox capacity
     */
    public int capacity() {
        return actor.getCapacity();
    }
    
    /**
     * Gets the current fill ratio (size/capacity).
     * 
     * @return the fill ratio between 0.0 and 1.0
     */
    public double fillRatio() {
        return actor.getFillRatio();
    }
    
    /**
     * Checks if the mailbox is empty.
     * 
     * @return true if mailbox has no messages
     */
    public boolean isEmpty() {
        return size() == 0;
    }
    
    /**
     * Checks if the mailbox is full.
     * 
     * @return true if mailbox is at capacity
     */
    public boolean isFull() {
        return size() >= capacity();
    }
    
    /**
     * Checks if the mailbox fill ratio exceeds the given threshold.
     * 
     * @param threshold the threshold (0.0 to 1.0)
     * @return true if fill ratio exceeds threshold
     */
    public boolean exceedsThreshold(double threshold) {
        return fillRatio() > threshold;
    }
    
    /**
     * Waits until the mailbox is empty or timeout is reached.
     * 
     * @param timeout the maximum time to wait
     * @return true if mailbox became empty, false if timeout
     * @throws InterruptedException if interrupted while waiting
     */
    public boolean awaitEmpty(Duration timeout) throws InterruptedException {
        long endTime = System.currentTimeMillis() + timeout.toMillis();
        
        while (System.currentTimeMillis() < endTime) {
            if (isEmpty()) {
                return true;
            }
            Thread.sleep(10);
        }
        
        return isEmpty();
    }
    
    /**
     * Waits until the mailbox size drops below the given threshold.
     * 
     * @param threshold the size threshold
     * @param timeout the maximum time to wait
     * @return true if size dropped below threshold, false if timeout
     * @throws InterruptedException if interrupted while waiting
     */
    public boolean awaitSizeBelow(int threshold, Duration timeout) throws InterruptedException {
        long endTime = System.currentTimeMillis() + timeout.toMillis();
        
        while (System.currentTimeMillis() < endTime) {
            if (size() < threshold) {
                return true;
            }
            Thread.sleep(10);
        }
        
        return size() < threshold;
    }
    
    /**
     * Gets the current processing rate (messages per second).
     * 
     * @return the processing rate
     */
    public double processingRate() {
        return actor.getProcessingRate();
    }
    
    /**
     * Gets a snapshot of current mailbox metrics.
     * 
     * @return a MailboxMetrics snapshot
     */
    public MailboxMetrics metrics() {
        return new MailboxMetrics(
            size(),
            capacity(),
            fillRatio(),
            processingRate()
        );
    }
    
    /**
     * Snapshot of mailbox metrics at a point in time.
     *
     * @param size the number of messages currently in the mailbox
     * @param capacity the maximum capacity of the mailbox
     * @param fillRatio the ratio of current size to capacity (0.0 to 1.0)
     * @param processingRate the current message processing rate (messages per second)
     */
    public record MailboxMetrics(
        int size,
        int capacity,
        double fillRatio,
        double processingRate
    ) {
        @Override
        public String toString() {
            return String.format(
                "MailboxMetrics{size=%d, capacity=%d, fillRatio=%.2f, rate=%.2f msg/s}",
                size, capacity, fillRatio, processingRate
            );
        }
    }
    
    // Helper method to get actor from system
    private static Actor<?> getActorFromSystem(String actorId, com.cajunsystems.ActorSystem system) {
        try {
            // Access the actors map from ActorSystem
            Field actorsField = com.cajunsystems.ActorSystem.class.getDeclaredField("actors");
            actorsField.setAccessible(true);
            
            @SuppressWarnings("unchecked")
            var actors = (java.util.concurrent.ConcurrentHashMap<String, Actor<?>>) actorsField.get(system);
            
            Actor<?> actor = actors.get(actorId);
            if (actor == null) {
                throw new IllegalArgumentException("Actor not found: " + actorId);
            }
            
            return actor;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalStateException("Failed to access actor from system", e);
        }
    }
}
