package systems.cajun.backpressure;

import java.io.Serializable;
import java.time.Instant;

/**
 * Metrics related to backpressure in a stateful actor.
 * This class provides a snapshot of the current backpressure state.
 */
public class BackpressureMetrics implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private final double backpressureLevel;
    private final int currentQueueSize;
    private final int rejectedMessagesCount;
    private final int delayedMessagesCount;
    private final long averageProcessingTimeNanos;
    private final int persistenceQueueSize;
    private final Instant timestamp;
    
    /**
     * Creates a new BackpressureMetrics instance.
     *
     * @param backpressureLevel Current backpressure level (0.0-1.0)
     * @param currentQueueSize Current size of the message queue
     * @param rejectedMessagesCount Number of messages rejected due to backpressure
     * @param delayedMessagesCount Number of messages delayed due to backpressure
     * @param averageProcessingTimeNanos Average processing time per message in nanoseconds
     * @param persistenceQueueSize Current size of the persistence operation queue
     */
    public BackpressureMetrics(
            double backpressureLevel,
            int currentQueueSize,
            int rejectedMessagesCount,
            int delayedMessagesCount,
            long averageProcessingTimeNanos,
            int persistenceQueueSize) {
        this.backpressureLevel = backpressureLevel;
        this.currentQueueSize = currentQueueSize;
        this.rejectedMessagesCount = rejectedMessagesCount;
        this.delayedMessagesCount = delayedMessagesCount;
        this.averageProcessingTimeNanos = averageProcessingTimeNanos;
        this.persistenceQueueSize = persistenceQueueSize;
        this.timestamp = Instant.now();
    }
    
    public double getBackpressureLevel() {
        return backpressureLevel;
    }
    
    public int getCurrentQueueSize() {
        return currentQueueSize;
    }
    
    public int getRejectedMessagesCount() {
        return rejectedMessagesCount;
    }
    
    public int getDelayedMessagesCount() {
        return delayedMessagesCount;
    }
    
    public long getAverageProcessingTimeNanos() {
        return averageProcessingTimeNanos;
    }
    
    public int getPersistenceQueueSize() {
        return persistenceQueueSize;
    }
    
    public Instant getTimestamp() {
        return timestamp;
    }
    
    @Override
    public String toString() {
        return "BackpressureMetrics{" +
                "backpressureLevel=" + backpressureLevel +
                ", currentQueueSize=" + currentQueueSize +
                ", rejectedMessagesCount=" + rejectedMessagesCount +
                ", delayedMessagesCount=" + delayedMessagesCount +
                ", averageProcessingTimeNanos=" + averageProcessingTimeNanos +
                ", persistenceQueueSize=" + persistenceQueueSize +
                ", timestamp=" + timestamp +
                '}';
    }
}
