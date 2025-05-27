package com.cajunsystems.config;

/**
 * Configuration for actor mailbox settings.
 */
public class MailboxConfig {
    // Default values for mailbox configuration
    public static final int DEFAULT_INITIAL_CAPACITY = 64;
    public static final int DEFAULT_MAX_CAPACITY = 10_000;
    public static final float DEFAULT_RESIZE_THRESHOLD = 0.75f;
    public static final float DEFAULT_RESIZE_FACTOR = 2.0f;

    private int initialCapacity;
    private int maxCapacity;
    private float resizeThreshold;
    private float resizeFactor;

    /**
     * Creates a new MailboxConfig with default values.
     */
    public MailboxConfig() {
        this.initialCapacity = DEFAULT_INITIAL_CAPACITY;
        this.maxCapacity = DEFAULT_MAX_CAPACITY;
        this.resizeThreshold = DEFAULT_RESIZE_THRESHOLD;
        this.resizeFactor = DEFAULT_RESIZE_FACTOR;
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
}
