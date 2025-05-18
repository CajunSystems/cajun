package systems.cajun.backpressure;

import java.time.Instant;
import java.util.Objects;

/**
 * Event object that provides detailed information about the backpressure state of an actor.
 * This is passed to callbacks when the backpressure state changes, providing rich context
 * for consumers to make intelligent decisions about message sending rates.
 */
public class BackpressureEvent {
    private final String actorId;
    private final BackpressureState state;
    private final float fillRatio;
    private final int currentSize;
    private final int capacity;
    private final long processingRate;
    private final Instant timestamp;
    private final boolean wasResized;
    private final int previousCapacity;
    
    /**
     * Creates a new backpressure event with detailed metrics.
     *
     * @param actorId The ID of the actor that generated this event
     * @param state The backpressure state
     * @param fillRatio The current fill ratio of the mailbox (size/capacity)
     * @param currentSize The current number of messages in the mailbox
     * @param capacity The current capacity of the mailbox
     * @param processingRate The processing rate in messages per second
     * @param wasResized Whether the mailbox was resized as part of this event
     * @param previousCapacity The previous capacity before resize, or the same as capacity if no resize occurred
     */
    public BackpressureEvent(
            String actorId,
            BackpressureState state, 
            float fillRatio, 
            int currentSize, 
            int capacity, 
            long processingRate,
            boolean wasResized,
            int previousCapacity) {
        // Validate inputs to prevent invalid states
        this.actorId = actorId != null ? actorId : "unknown";
        this.state = state != null ? state : BackpressureState.NORMAL;
        this.fillRatio = Float.isNaN(fillRatio) || Float.isInfinite(fillRatio) ? 0.0f : fillRatio;
        this.currentSize = Math.max(0, currentSize);
        this.capacity = Math.max(1, capacity); // Avoid division by zero
        this.processingRate = Math.max(0, processingRate);
        this.timestamp = Instant.now();
        this.wasResized = wasResized;
        this.previousCapacity = Math.max(0, previousCapacity);
    }

    /**
     * Gets the ID of the actor that generated this event.
     *
     * @return The actor ID
     */
    public String getActorId() {
        return actorId;
    }

    /**
     * Gets the backpressure state.
     *
     * @return The current backpressure state
     */
    public BackpressureState getState() {
        return state;
    }

    /**
     * Gets the current fill ratio of the mailbox (size/capacity).
     *
     * @return The fill ratio
     */
    public float getFillRatio() {
        return fillRatio;
    }

    /**
     * Gets the current number of messages in the mailbox.
     *
     * @return The current size
     */
    public int getCurrentSize() {
        return currentSize;
    }

    /**
     * Gets the current capacity of the mailbox.
     *
     * @return The capacity
     */
    public int getCapacity() {
        return capacity;
    }

    /**
     * Gets the processing rate in messages per second.
     *
     * @return The processing rate
     */
    public long getProcessingRate() {
        return processingRate;
    }

    /**
     * Gets the timestamp when this event was created.
     *
     * @return The timestamp
     */
    public Instant getTimestamp() {
        return timestamp;
    }
    
    /**
     * Checks if the mailbox was resized as part of this event.
     *
     * @return true if the mailbox was resized, false otherwise
     */
    public boolean wasResized() {
        return wasResized;
    }
    
    /**
     * Gets the previous capacity before resize, or the same as capacity if no resize occurred.
     *
     * @return The previous capacity
     */
    public int getPreviousCapacity() {
        return previousCapacity;
    }
    
    /**
     * Calculates how long the mailbox would take to empty at the current processing rate,
     * assuming no new messages are added.
     *
     * @return The estimated time in milliseconds, or Long.MAX_VALUE if the processing rate is 0
     */
    public long getEstimatedTimeToEmpty() {
        if (processingRate <= 0) {
            return Long.MAX_VALUE;
        }
        try {
            return (long) (currentSize * 1000.0 / processingRate);
        } catch (ArithmeticException e) {
            // Handle potential division by zero or overflow
            return Long.MAX_VALUE;
        }
    }
    
    /**
     * Calculates how much remaining capacity is available in the mailbox.
     *
     * @return The remaining capacity
     */
    public int getRemainingCapacity() {
        return capacity - currentSize;
    }
    
    /**
     * Checks if the backpressure is in an active state (either CRITICAL or WARNING).
     *
     * @return true if backpressure is active, false otherwise
     */
    public boolean isBackpressureActive() {
        return state == BackpressureState.CRITICAL || state == BackpressureState.WARNING;
    }
    
    @Override
    public String toString() {
        return "BackpressureEvent{" +
                "actorId='" + actorId + '\'' +
                ", state=" + state +
                ", fillRatio=" + String.format("%.2f", fillRatio) +
                ", size=" + currentSize +
                ", capacity=" + capacity +
                ", rate=" + processingRate + " msgs/sec" +
                (wasResized ? ", resized from " + previousCapacity : "") +
                '}';
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BackpressureEvent that = (BackpressureEvent) o;
        return Float.compare(that.fillRatio, fillRatio) == 0 &&
                currentSize == that.currentSize &&
                capacity == that.capacity &&
                processingRate == that.processingRate &&
                wasResized == that.wasResized &&
                previousCapacity == that.previousCapacity &&
                Objects.equals(actorId, that.actorId) &&
                state == that.state;
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(actorId, state, fillRatio, currentSize, capacity, 
                            processingRate, timestamp, wasResized, previousCapacity);
    }
}
