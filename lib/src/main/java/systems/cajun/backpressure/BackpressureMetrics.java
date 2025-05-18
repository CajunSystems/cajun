package systems.cajun.backpressure;

import java.time.Instant;

/**
 * Contains metrics related to backpressure for an actor's mailbox.
 * Used to track and report on the state of an actor's mailbox for backpressure purposes.
 */
public class BackpressureMetrics {
    private int currentSize;
    private int capacity;
    private long processingRate;
    private boolean backpressureActive;
    private BackpressureState currentState = BackpressureState.NORMAL;
    private Instant lastStateChangeTime = Instant.now();

    /**
     * Creates a new BackpressureMetrics with default values.
     */
    public BackpressureMetrics() {
        this.currentSize = 0;
        this.capacity = 0;
        this.processingRate = 0;
        this.backpressureActive = false;
    }

    /**
     * Updates all metrics at once.
     * 
     * @param currentSize The current number of messages in the mailbox
     * @param capacity The total capacity of the mailbox
     * @param processingRate The current message processing rate (messages per second)
     * @param backpressureActive Whether backpressure is currently active
     */
    public void update(int currentSize, int capacity, long processingRate, boolean backpressureActive) {
        this.currentSize = currentSize;
        this.capacity = capacity;
        this.processingRate = processingRate;
        this.backpressureActive = backpressureActive;
    }

    /**
     * Updates the current state and the time of the state change.
     * 
     * @param newState The new backpressure state
     */
    public void updateState(BackpressureState newState) {
        if (this.currentState != newState) {
            this.currentState = newState;
            this.lastStateChangeTime = Instant.now();
        }
    }

    /**
     * Gets the current number of messages in the mailbox.
     * 
     * @return The current mailbox size
     */
    public int getCurrentSize() {
        return currentSize;
    }

    /**
     * Gets the total capacity of the mailbox.
     * 
     * @return The mailbox capacity
     */
    public int getCapacity() {
        return capacity;
    }

    /**
     * Gets the current fill ratio of the mailbox (currentSize / capacity).
     * 
     * @return The fill ratio as a float between 0.0 and 1.0, or 0.0 if capacity is 0
     */
    public float getFillRatio() {
        return capacity > 0 ? (float) currentSize / capacity : 0.0f;
    }

    /**
     * Gets the current message processing rate in messages per second.
     * 
     * @return The processing rate
     */
    public long getProcessingRate() {
        return processingRate;
    }

    /**
     * Checks if backpressure is currently active.
     * 
     * @return true if backpressure is active, false otherwise
     */
    public boolean isBackpressureActive() {
        return backpressureActive;
    }

    /**
     * Gets the current backpressure state.
     * 
     * @return The current state
     */
    public BackpressureState getCurrentState() {
        return currentState;
    }

    /**
     * Gets the time of the last state change.
     * 
     * @return The timestamp of the last state change
     */
    public Instant getLastStateChangeTime() {
        return lastStateChangeTime;
    }
}
