package systems.cajun.backpressure;

import java.io.Serializable;
import java.util.function.Consumer;

/**
 * Defines strategies for handling backpressure in stateful actors.
 * Backpressure occurs when an actor receives messages faster than it can process them,
 * potentially leading to resource exhaustion.
 */
public interface BackpressureStrategy extends Serializable {
    
    /**
     * Determines if backpressure should be applied based on the current system state.
     * 
     * @param queueSize Current size of the message queue
     * @param processingTimeNanos Recent message processing time in nanoseconds
     * @param persistenceQueueSize Size of the persistence operation queue
     * @return true if backpressure should be applied, false otherwise
     */
    boolean shouldApplyBackpressure(int queueSize, long processingTimeNanos, int persistenceQueueSize);
    
    /**
     * Handles a message under backpressure conditions.
     * This method is called when backpressure is being applied.
     * 
     * @param message The message to handle
     * @param normalProcessing The normal processing function to call if the message should be processed
     * @param <M> The message type
     * @return true if the message was handled, false if it was rejected
     */
    <M> boolean handleMessageUnderBackpressure(M message, Consumer<M> normalProcessing);
    
    /**
     * Called when a message has been successfully processed.
     * This allows the strategy to update its internal state.
     * 
     * @param processingTimeNanos The time taken to process the message in nanoseconds
     */
    void onMessageProcessed(long processingTimeNanos);
    
    /**
     * Called when a message processing operation fails.
     * This allows the strategy to update its internal state.
     * 
     * @param exception The exception that occurred
     */
    void onMessageProcessingFailed(Throwable exception);
    
    /**
     * Gets the current backpressure level, a value between 0.0 (no backpressure) 
     * and 1.0 (maximum backpressure).
     * 
     * @return The current backpressure level
     */
    double getCurrentBackpressureLevel();
    
    /**
     * Gets a snapshot of the current metrics tracked by this backpressure strategy.
     * 
     * @return A BackpressureMetrics object containing current metrics
     */
    systems.cajun.backpressure.BackpressureMetrics getMetrics();
}
