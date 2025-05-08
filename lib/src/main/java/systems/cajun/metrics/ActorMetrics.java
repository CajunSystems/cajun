package systems.cajun.metrics;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;

/**
 * Metrics collection for actors.
 * This class provides metrics for monitoring actor health and performance.
 */
public class ActorMetrics {
    private final String actorId;
    
    // Message metrics
    private final AtomicLong messageCount = new AtomicLong(0);
    private final AtomicLong messageProcessingTimeNs = new AtomicLong(0);
    private final AtomicLong stateChangeCount = new AtomicLong(0);
    private final AtomicLong snapshotCount = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);
    
    // Timing metrics
    private final AtomicLong lastMessageReceivedTimestamp = new AtomicLong(0);
    private final AtomicLong lastStateChangeTimestamp = new AtomicLong(0);
    private final AtomicLong lastSnapshotTimestamp = new AtomicLong(0);
    private final AtomicLong lastErrorTimestamp = new AtomicLong(0);
    
    // Processing time statistics
    private final AtomicLong minProcessingTimeNs = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong maxProcessingTimeNs = new AtomicLong(0);
    
    /**
     * Creates a new ActorMetrics instance.
     *
     * @param actorId The ID of the actor these metrics belong to
     */
    public ActorMetrics(String actorId) {
        this.actorId = actorId;
    }
    
    /**
     * Registers this metrics instance with the metrics registry.
     * This should be called when the actor starts.
     */
    public void register() {
        // For now, just log registration - actual registration will be implemented later
        // MetricsRegistry.registerActorMetrics(actorId, this);
        System.out.println("Registered metrics for actor: " + actorId);
    }
    
    /**
     * Unregisters this metrics instance from the metrics registry.
     * This should be called when the actor stops.
     */
    public void unregister() {
        // For now, just log unregistration - actual unregistration will be implemented later
        // MetricsRegistry.unregisterActorMetrics(actorId);
        System.out.println("Unregistered metrics for actor: " + actorId);
    }
    
    /**
     * Records that a message was received by the actor.
     */
    public void messageReceived() {
        messageCount.incrementAndGet();
        lastMessageReceivedTimestamp.set(System.currentTimeMillis());
    }
    
    /**
     * Records that a message was processed by the actor.
     *
     * @param processingTimeNs The time it took to process the message, in nanoseconds
     */
    public void messageProcessed(long processingTimeNs) {
        messageProcessingTimeNs.addAndGet(processingTimeNs);
        
        // Update min processing time if this message was processed faster
        long currentMin = minProcessingTimeNs.get();
        while (processingTimeNs < currentMin) {
            if (minProcessingTimeNs.compareAndSet(currentMin, processingTimeNs)) {
                break;
            }
            currentMin = minProcessingTimeNs.get();
        }
        
        // Update max processing time if this message took longer
        long currentMax = maxProcessingTimeNs.get();
        while (processingTimeNs > currentMax) {
            if (maxProcessingTimeNs.compareAndSet(currentMax, processingTimeNs)) {
                break;
            }
            currentMax = maxProcessingTimeNs.get();
        }
    }
    
    /**
     * Records that the actor's state was changed.
     */
    public void stateChanged() {
        stateChangeCount.incrementAndGet();
        lastStateChangeTimestamp.set(System.currentTimeMillis());
    }
    
    /**
     * Records that a snapshot was taken.
     */
    public void snapshotTaken() {
        snapshotCount.incrementAndGet();
        lastSnapshotTimestamp.set(System.currentTimeMillis());
    }
    
    /**
     * Records that an error occurred.
     */
    public void errorOccurred() {
        errorCount.incrementAndGet();
        lastErrorTimestamp.set(System.currentTimeMillis());
    }
    
    /**
     * Gets the total number of messages received by the actor.
     *
     * @return The message count
     */
    public long getMessageCount() {
        return messageCount.get();
    }
    
    /**
     * Gets the total time spent processing messages, in nanoseconds.
     *
     * @return The total processing time in nanoseconds
     */
    public long getTotalProcessingTimeNs() {
        return messageProcessingTimeNs.get();
    }
    
    /**
     * Gets the average time spent processing messages, in nanoseconds.
     *
     * @return The average processing time in nanoseconds, or 0 if no messages have been processed
     */
    public long getAverageProcessingTimeNs() {
        long count = messageCount.get();
        if (count > 0) {
            return messageProcessingTimeNs.get() / count;
        }
        return 0;
    }
    
    /**
     * Gets the average time spent processing messages, in milliseconds.
     *
     * @return The average processing time in milliseconds, or 0 if no messages have been processed
     */
    public double getAverageProcessingTimeMs() {
        return TimeUnit.NANOSECONDS.toMicros(getAverageProcessingTimeNs()) / 1000.0;
    }
    
    /**
     * Gets the minimum time spent processing a message, in nanoseconds.
     *
     * @return The minimum processing time in nanoseconds, or Long.MAX_VALUE if no messages have been processed
     */
    public long getMinProcessingTimeNs() {
        return minProcessingTimeNs.get();
    }
    
    /**
     * Gets the maximum time spent processing a message, in nanoseconds.
     *
     * @return The maximum processing time in nanoseconds, or 0 if no messages have been processed
     */
    public long getMaxProcessingTimeNs() {
        return maxProcessingTimeNs.get();
    }
    
    /**
     * Gets the total number of state changes.
     *
     * @return The state change count
     */
    public long getStateChangeCount() {
        return stateChangeCount.get();
    }
    
    /**
     * Gets the total number of snapshots taken.
     *
     * @return The snapshot count
     */
    public long getSnapshotCount() {
        return snapshotCount.get();
    }
    
    /**
     * Gets the total number of errors that occurred.
     *
     * @return The error count
     */
    public long getErrorCount() {
        return errorCount.get();
    }
    
    /**
     * Gets the timestamp of the last message received, in milliseconds since the epoch.
     *
     * @return The timestamp, or 0 if no messages have been received
     */
    public long getLastMessageReceivedTimestamp() {
        return lastMessageReceivedTimestamp.get();
    }
    
    /**
     * Gets the timestamp of the last state change, in milliseconds since the epoch.
     *
     * @return The timestamp, or 0 if no state changes have occurred
     */
    public long getLastStateChangeTimestamp() {
        return lastStateChangeTimestamp.get();
    }
    
    /**
     * Gets the timestamp of the last snapshot, in milliseconds since the epoch.
     *
     * @return The timestamp, or 0 if no snapshots have been taken
     */
    public long getLastSnapshotTimestamp() {
        return lastSnapshotTimestamp.get();
    }
    
    /**
     * Gets the timestamp of the last error, in milliseconds since the epoch.
     *
     * @return The timestamp, or 0 if no errors have occurred
     */
    public long getLastErrorTimestamp() {
        return lastErrorTimestamp.get();
    }
    
    /**
     * Gets the message processing rate, in messages per second.
     *
     * @param windowSizeMs The time window to consider, in milliseconds
     * @return The message processing rate, or 0 if no messages have been processed in the window
     */
    public double getMessageRatePerSecond(long windowSizeMs) {
        long now = System.currentTimeMillis();
        long lastMessageTime = lastMessageReceivedTimestamp.get();
        
        if (lastMessageTime == 0 || now - lastMessageTime > windowSizeMs) {
            return 0.0;
        }
        
        // Calculate messages per second based on the total count and actor uptime
        // This is a simplistic approach; a more accurate implementation would use a sliding window
        long uptime = now - lastMessageTime + windowSizeMs; // Add window size to account for the first message
        return (messageCount.get() * 1000.0) / uptime;
    }
    
    /**
     * Gets the error rate, in errors per second.
     *
     * @param windowSizeMs The time window to consider, in milliseconds
     * @return The error rate, or 0 if no errors have occurred in the window
     */
    public double getErrorRatePerSecond(long windowSizeMs) {
        long now = System.currentTimeMillis();
        long lastErrorTime = lastErrorTimestamp.get();
        
        if (lastErrorTime == 0 || now - lastErrorTime > windowSizeMs) {
            return 0.0;
        }
        
        // Calculate errors per second based on the total count and actor uptime
        long uptime = now - lastErrorTime + windowSizeMs; // Add window size to account for the first error
        return (errorCount.get() * 1000.0) / uptime;
    }
    
    /**
     * Gets the actor ID.
     *
     * @return The actor ID
     */
    public String getActorId() {
        return actorId;
    }
    
    /**
     * Returns a string representation of the metrics.
     *
     * @return A string representation of the metrics
     */
    @Override
    public String toString() {
        return String.format(
            "ActorMetrics[actorId=%s, messages=%d, avgProcessingTime=%.3fms, stateChanges=%d, snapshots=%d, errors=%d]",
            actorId,
            messageCount.get(),
            getAverageProcessingTimeMs(),
            stateChangeCount.get(),
            snapshotCount.get(),
            errorCount.get()
        );
    }
}
