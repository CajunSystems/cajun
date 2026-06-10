package com.cajunsystems.backpressure;

/**
 * Defines different strategies for handling backpressure in the actor system.
 * These strategies determine how the system behaves when an actor's mailbox
 * approaches its capacity limits.
 */
public enum BackpressureStrategy {
    /**
     * Block the sender until space is available in the mailbox.
     * This is the default behavior.
     */
    BLOCK,
    
    /**
     * Drop new messages when the mailbox is full.
     * This prioritizes older messages over newer ones.
     */
    DROP_NEW,
    
    /**
     * Drop the oldest messages in the mailbox when it is full.
     * This prioritizes newer messages over older ones.
     */
    DROP_OLDEST,
    
    /**
     * Use a custom strategy defined by the user.
     * This requires implementing a CustomBackpressureHandler.
     */
    CUSTOM
}
