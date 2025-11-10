package com.cajunsystems.config;

import com.cajunsystems.dispatcher.MailboxType;
import com.cajunsystems.dispatcher.OverflowStrategy;

/**
 * Configuration for actor mailbox settings.
 * Supports both traditional blocking mailboxes and dispatcher-based mailboxes.
 */
public class MailboxConfig {
    // Default values for mailbox configuration
    public static final int DEFAULT_INITIAL_CAPACITY = 64;
    public static final int DEFAULT_MAX_CAPACITY = 10_000;
    public static final float DEFAULT_RESIZE_THRESHOLD = 0.75f;
    public static final float DEFAULT_RESIZE_FACTOR = 2.0f;
    public static final int DEFAULT_THROUGHPUT = 64;
    public static final MailboxType DEFAULT_MAILBOX_TYPE = MailboxType.BLOCKING;
    public static final OverflowStrategy DEFAULT_OVERFLOW_STRATEGY = OverflowStrategy.BLOCK;

    private int initialCapacity;
    private int maxCapacity;
    private float resizeThreshold;
    private float resizeFactor;
    private MailboxType mailboxType;
    private int throughput;
    private OverflowStrategy overflowStrategy;

    /**
     * Creates a new MailboxConfig with default values.
     */
    public MailboxConfig() {
        this.initialCapacity = DEFAULT_INITIAL_CAPACITY;
        this.maxCapacity = DEFAULT_MAX_CAPACITY;
        this.resizeThreshold = DEFAULT_RESIZE_THRESHOLD;
        this.resizeFactor = DEFAULT_RESIZE_FACTOR;
        this.mailboxType = DEFAULT_MAILBOX_TYPE;
        this.throughput = DEFAULT_THROUGHPUT;
        this.overflowStrategy = DEFAULT_OVERFLOW_STRATEGY;
    }

    /**
     * Sets the initial capacity for the mailbox.
     * 
     * @param initialCapacity The initial capacity
     * @return This MailboxConfig instance
     */
    public MailboxConfig setInitialCapacity(int initialCapacity) {
        this.initialCapacity = initialCapacity;
        return this;
    }

    /**
     * Sets the maximum capacity for the mailbox.
     * 
     * @param maxCapacity The maximum capacity
     * @return This MailboxConfig instance
     */
    public MailboxConfig setMaxCapacity(int maxCapacity) {
        this.maxCapacity = maxCapacity;
        return this;
    }

    /**
     * Gets the initial capacity for the mailbox.
     * 
     * @return The initial capacity
     */
    public int getInitialCapacity() {
        return initialCapacity;
    }

    /**
     * Gets the maximum capacity for the mailbox.
     * 
     * @return The maximum capacity
     */
    public int getMaxCapacity() {
        return maxCapacity;
    }

    /**
     * Sets the resize threshold for the mailbox. When the mailbox reaches this threshold
     * of capacity, it will attempt to resize.
     * 
     * @param resizeThreshold The resize threshold as a fraction between 0 and 1
     * @return This MailboxConfig instance
     */
    public MailboxConfig setResizeThreshold(float resizeThreshold) {
        this.resizeThreshold = resizeThreshold;
        return this;
    }

    /**
     * Gets the resize threshold for the mailbox.
     * 
     * @return The resize threshold
     */
    public float getResizeThreshold() {
        return resizeThreshold;
    }

    /**
     * Sets the resize factor for the mailbox. When the mailbox resizes, it will
     * multiply its capacity by this factor.
     * 
     * @param resizeFactor The resize factor
     * @return This MailboxConfig instance
     */
    public MailboxConfig setResizeFactor(float resizeFactor) {
        this.resizeFactor = resizeFactor;
        return this;
    }

    /**
     * Gets the resize factor for the mailbox.
     * 
     * @return The resize factor
     */
    public float getResizeFactor() {
        return resizeFactor;
    }
    
    /**
     * Determines if this mailbox is resizable.
     * 
     * @return true if the mailbox is resizable, false otherwise
     */
    public boolean isResizable() {
        return false; // Base implementation is not resizable, subclasses may override
    }
    
    /**
     * Sets the mailbox type.
     * 
     * @param mailboxType The mailbox type (BLOCKING, DISPATCHER_CBQ, or DISPATCHER_MPSC)
     * @return This MailboxConfig instance
     */
    public MailboxConfig setMailboxType(MailboxType mailboxType) {
        this.mailboxType = mailboxType;
        return this;
    }
    
    /**
     * Gets the mailbox type.
     * 
     * @return The mailbox type
     */
    public MailboxType getMailboxType() {
        return mailboxType;
    }
    
    /**
     * Sets the throughput (batch size) for dispatcher-based mailboxes.
     * This controls how many messages are processed per actor activation.
     * 
     * @param throughput The throughput (must be >= 1)
     * @return This MailboxConfig instance
     */
    public MailboxConfig setThroughput(int throughput) {
        this.throughput = Math.max(1, throughput);
        return this;
    }
    
    /**
     * Gets the throughput (batch size) for dispatcher-based mailboxes.
     * 
     * @return The throughput
     */
    public int getThroughput() {
        return throughput;
    }
    
    /**
     * Sets the overflow strategy for dispatcher-based mailboxes.
     * 
     * @param overflowStrategy The overflow strategy (BLOCK or DROP)
     * @return This MailboxConfig instance
     */
    public MailboxConfig setOverflowStrategy(OverflowStrategy overflowStrategy) {
        this.overflowStrategy = overflowStrategy;
        return this;
    }
    
    /**
     * Gets the overflow strategy for dispatcher-based mailboxes.
     * 
     * @return The overflow strategy
     */
    public OverflowStrategy getOverflowStrategy() {
        return overflowStrategy;
    }
    
    /**
     * Determines if this mailbox configuration uses dispatcher mode.
     * 
     * @return true if mailboxType is DISPATCHER_CBQ or DISPATCHER_MPSC
     */
    public boolean isDispatcherMode() {
        return mailboxType == MailboxType.DISPATCHER_CBQ || mailboxType == MailboxType.DISPATCHER_MPSC;
    }
}
