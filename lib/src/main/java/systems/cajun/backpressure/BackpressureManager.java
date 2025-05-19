package systems.cajun.backpressure;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import systems.cajun.Actor;
import systems.cajun.backpressure.BackpressureStatus.StateTransition;
import systems.cajun.config.BackpressureConfig;

/**
 * Manages enhanced backpressure functionality for actors.
 * This class serves as the core component connecting the existing Actor
 * implementation with the new backpressure API.
 *
 * @param <T> The type of messages processed by the actor
 */
public class BackpressureManager<T> {
    private static final Logger logger = LoggerFactory.getLogger(BackpressureManager.class);

    // Reference to the actor being managed
    private final Actor<T> actor;

    // Backpressure configuration
    private boolean enabled = false;
    private BackpressureConfig config;
    private BackpressureStrategy strategy = BackpressureStrategy.BLOCK;
    private CustomBackpressureHandler<T> customHandler;
    private int mailboxCapacity = 100; // Default capacity

    // Thresholds
    private float warningThreshold = 0.7f;  // 70% capacity triggers WARNING state
    private float criticalThreshold = 0.8f; // 80% capacity triggers CRITICAL state
    private float recoveryThreshold = 0.5f; // 50% capacity triggers RECOVERY state

    // State tracking
    private BackpressureState currentState = BackpressureState.NORMAL;
    private Instant lastStateChangeTime = Instant.now();
    private long messagesProcessedSinceLastStateChange = 0;
    private final Deque<BackpressureEvent> recentEvents = new LinkedList<>();
    private int maxEventsToKeep = 20;
    private final List<StateTransition> stateTransitions = new ArrayList<>();

    // Metrics
    private final AtomicInteger currentSize = new AtomicInteger(0);
    private final AtomicLong messagesProcessed = new AtomicLong(0);
    private final AtomicLong processingRate = new AtomicLong(0);
    private final AtomicBoolean isBackpressureActive = new AtomicBoolean(false);

    // Callbacks
    private Consumer<BackpressureEvent> callback;

    // Additional fields for retry functionality
    private final ConcurrentHashMap<Object, RetryEntry<T>> retryQueue = new ConcurrentHashMap<>();
    private final ScheduledExecutorService retryExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "backpressure-retry-thread");
        t.setDaemon(true);
        return t;
    });
    private long defaultRetryTimeoutMs = 1000; // Default 1 second timeout

    /**
     * Creates a new BackpressureManager.
     */
    public BackpressureManager() {
        this.actor = null; // Will be set by the actor during initialization
    }

    /**
     * Creates a new BackpressureManager for the specified actor with the provided configuration.
     * 
     * @param actor The actor to manage backpressure for
     * @param config The backpressure configuration
     */
    public BackpressureManager(Actor<T> actor, BackpressureConfig config) {
        this.actor = actor;
        enable(config);
    }

    /**
     * Enables backpressure for the managed actor.
     *
     * @param config The backpressure configuration to use
     */
    public synchronized void enable(BackpressureConfig config) {
        if (config == null) {
            logger.warn("Attempted to enable backpressure with null config, using default values");
            this.config = new BackpressureConfig();
        } else {
            this.config = config;
        }
        
        // Set thresholds from config
        float highWatermark = this.config.getHighWatermark();
        float lowWatermark = this.config.getLowWatermark();
        
        // Validate watermarks
        if (highWatermark <= 0 || highWatermark > 1.0f) {
            logger.warn("Invalid high watermark: {}, using default value of 0.8", highWatermark);
            highWatermark = 0.8f;
        }
        
        if (lowWatermark <= 0 || lowWatermark >= highWatermark) {
            logger.warn("Invalid low watermark: {}, using default value of 0.5", lowWatermark);
            lowWatermark = 0.5f;
        }
        
        this.criticalThreshold = highWatermark;
        this.recoveryThreshold = lowWatermark;

        // Default warning threshold to midpoint between normal and critical if not set
        if (this.warningThreshold <= 0 || this.warningThreshold >= this.criticalThreshold) {
            this.warningThreshold = (this.criticalThreshold + this.recoveryThreshold) / 2;
        }
        
        // Reset metrics and state if needed
        if (!this.enabled) {
            // Only reset if we're transitioning from disabled to enabled
            this.currentState = BackpressureState.NORMAL;
            this.lastStateChangeTime = Instant.now();
            this.messagesProcessedSinceLastStateChange = 0;
            
            synchronized (recentEvents) {
                recentEvents.clear();
            }
            
            synchronized (stateTransitions) {
                stateTransitions.clear();
            }
            
            // Log enabling of backpressure
            String actorId = actor != null ? actor.getActorId() : "unknown";
            logger.info("Enabled backpressure for actor {} with thresholds: warning={}, critical={}, recovery={}", 
                    actorId, this.warningThreshold, this.criticalThreshold, this.recoveryThreshold);
        }
        
        // Set enabled flag last to ensure all configuration is complete
        this.enabled = true;
    }

    /**
     * Disables backpressure for the managed actor.
     */
    public synchronized void disable() {
        if (this.enabled) {
            // Only log if we're actually changing state
            String actorId = actor != null ? actor.getActorId() : "unknown";
            logger.info("Disabled backpressure for actor {}", actorId);
            
            // Reset backpressure active flag
            isBackpressureActive.set(false);
            
            // Clear any pending state
            this.currentState = BackpressureState.NORMAL;
        }
        
        this.enabled = false;
    }

    /**
     * Sets the backpressure strategy.
     *
     * @param strategy The strategy to use
     */
    public void setStrategy(BackpressureStrategy strategy) {
        if (strategy == null) {
            logger.warn("Attempted to set null backpressure strategy, defaulting to BLOCK");
            this.strategy = BackpressureStrategy.BLOCK;
        } else {
            this.strategy = strategy;
        }
    }

    /**
     * Sets a custom backpressure handler for more advanced control.
     * This is only used when the strategy is set to CUSTOM.
     *
     * @param handler The custom handler
     */
    public void setCustomHandler(CustomBackpressureHandler<T> handler) {
        this.customHandler = handler;
        if (handler != null) {
            this.strategy = BackpressureStrategy.CUSTOM;
        }
    }

    /**
     * Sets thresholds for different backpressure states.
     *
     * @param warningThreshold Threshold for WARNING state (0.0-1.0)
     * @param criticalThreshold Threshold for CRITICAL state (0.0-1.0)
     * @param recoveryThreshold Threshold for RECOVERY state (0.0-1.0)
     */
    public void setThresholds(float warningThreshold, float criticalThreshold, float recoveryThreshold) {
        // Validate thresholds to ensure they are within valid ranges
        this.warningThreshold = Math.max(0.0f, Math.min(1.0f, warningThreshold));
        this.criticalThreshold = Math.max(0.0f, Math.min(1.0f, criticalThreshold));
        this.recoveryThreshold = Math.max(0.0f, Math.min(1.0f, recoveryThreshold));
        
        // Ensure logical ordering of thresholds
        if (this.criticalThreshold < this.warningThreshold) {
            logger.warn("Critical threshold {} is less than warning threshold {}, adjusting critical threshold",
                    this.criticalThreshold, this.warningThreshold);
            this.criticalThreshold = this.warningThreshold;
        }
        
        if (this.recoveryThreshold > this.warningThreshold) {
            logger.warn("Recovery threshold {} is greater than warning threshold {}, adjusting recovery threshold",
                    this.recoveryThreshold, this.warningThreshold);
            this.recoveryThreshold = this.warningThreshold * 0.8f;
        }
    }

    /**
     * Sets the callback to be notified of backpressure events.
     *
     * @param callback The callback to invoke with event information
     */
    public void setCallback(Consumer<BackpressureEvent> callback) {
        this.callback = callback;
    }

    /**
     * Sets the maximum number of backpressure events to keep in history.
     *
     * @param maxEventsToKeep The maximum number of events to keep
     */
    public void setMaxEventsToKeep(int maxEventsToKeep) {
        this.maxEventsToKeep = maxEventsToKeep;
    }

    /**
     * Updates metrics based on the current state of the actor's mailbox.
     * This should be called periodically to keep the metrics up-to-date.
     *
     * @param currentMailboxSize The current size of the mailbox
     * @param mailboxCapacity The capacity of the mailbox
     * @param rate The processing rate in messages per second
     */
    public void updateMetrics(int currentMailboxSize, int mailboxCapacity, long rate) {
        if (!enabled) {
            return;
        }

        try {
            currentSize.set(currentMailboxSize);
            processingRate.set(rate);
            messagesProcessed.incrementAndGet();

            // Calculate fill ratio
            float fillRatio = calculateFillRatio(currentMailboxSize, mailboxCapacity);

            // Determine the new state based on thresholds
            BackpressureState newState = determineBackpressureState(fillRatio);

            // Handle state transition if needed
            if (newState != currentState) {
                handleStateTransition(currentState, newState, fillRatio, currentMailboxSize, mailboxCapacity, rate);
            }

            // Set backpressure active flag based on the current state
            boolean isActive = newState == BackpressureState.CRITICAL || newState == BackpressureState.WARNING;
            isBackpressureActive.set(isActive);

            // Create a backpressure event for the current metrics
            BackpressureEvent event = createBackpressureEvent(
                newState, fillRatio, currentMailboxSize, mailboxCapacity, rate, false, mailboxCapacity);

            // Add to recent events and trim if needed
            addEventToHistory(event);

            // Notify callback if registered
            if (callback != null) {
                try {
                    callback.accept(event);
                } catch (Exception e) {
                    // Catch and log any exceptions from callbacks to prevent them from affecting the actor
                    logger.error("Exception in backpressure callback: {}", e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            // Catch any unexpected exceptions to prevent the actor from crashing
            logger.error("Exception in backpressure metrics update: {}", e.getMessage(), e);
        }
    }

    /**
     * Checks if backpressure is currently active.
     *
     * @return true if backpressure is active, false otherwise
     */
    public boolean isBackpressureActive() {
        return isBackpressureActive.get();
    }

    /**
     * Determines if a message should be accepted based on the current backpressure state
     * and the provided send options.
     *
     * @param options The options for sending the message
     * @return true if the message should be accepted, false otherwise
     */
    public boolean shouldAcceptMessage(BackpressureSendOptions options) {
        if (!enabled) {
            return true; // Backpressure is disabled, always accept
        }

        // High priority messages bypass backpressure in certain states
        if (options.isHighPriority() && currentState != BackpressureState.CRITICAL) {
            return true;
        }

        // Check based on the current strategy
        switch (strategy) {
            case BLOCK:
                // Always accept in BLOCK strategy, actual blocking happens in send()
                return true;

            case DROP_NEW:
                // In critical state, reject new messages
                return currentState != BackpressureState.CRITICAL || options.isHighPriority();

            case DROP_OLDEST:
                // Always accept in DROP_OLDEST, dropping happens in send()
                return true;

            case CUSTOM:
                // Use custom handler if available
                if (customHandler != null) {
                    int mailboxSize = currentSize.get();
                    int capacity = getMailboxCapacity();
                    return customHandler.shouldAccept(mailboxSize, capacity, options);
                }
                // Fall back to blocking if no handler
                return true;

            default:
                return true;
        }
    }

    /**
     * Gets the current backpressure state.
     *
     * @return The current backpressure state
     */
    public BackpressureState getCurrentState() {
        return currentState;
    }

    /**
     * Gets the current mailbox capacity.
     *
     * @return The current mailbox capacity
     */
    public int getMailboxCapacity() {
        if (config != null && config.getMaxCapacity() > 0) {
            return config.getMaxCapacity();
        }
        return mailboxCapacity;
    }

    /**
     * Sets the mailbox capacity.
     *
     * @param capacity The mailbox capacity
     */
    public void setMailboxCapacity(int capacity) {
        this.mailboxCapacity = capacity;
    }

    /**
     * Gets the time since the last state change.
     *
     * @return The duration since the last state change
     */
    public Duration getTimeInCurrentState() {
        try {
            return Duration.between(lastStateChangeTime, Instant.now());
        } catch (Exception e) {
            logger.warn("Error calculating time in current state: {}", e.getMessage());
            return Duration.ZERO;
        }
    }

    /**
     * Gets the list of recent backpressure events.
     *
     * @return The recent events
     */
    public List<BackpressureEvent> getRecentEvents() {
        synchronized (recentEvents) {
            return new ArrayList<>(recentEvents);
        }
    }

    /**
     * Gets the list of state transitions.
     *
     * @return The state transitions
     */
    public List<StateTransition> getStateTransitions() {
        synchronized (stateTransitions) {
            return new ArrayList<>(stateTransitions);
        }
    }

    /**
     * Gets whether backpressure is enabled.
     *
     * @return true if backpressure is enabled, false otherwise
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Gets the current backpressure strategy.
     *
     * @return The current strategy
     */
    public BackpressureStrategy getStrategy() {
        return strategy;
    }

    /**
     * Handles a message according to the current backpressure strategy.
     *
     * @param message The message to handle
     * @param options The backpressure send options
     * @return true if the message was accepted, false otherwise
     */
    public boolean handleMessage(T message, BackpressureSendOptions options) {
        if (!enabled || !isBackpressureActive.get()) {
            // No backpressure, just accept the message
            return true;
        }

        // Get the most recent event for decision making
        BackpressureEvent latestEvent;
        synchronized (recentEvents) {
            latestEvent = !recentEvents.isEmpty() ? recentEvents.getFirst() : null;
        }

        // If we don't have any events yet, accept the message
        if (latestEvent == null) {
            logger.debug("No backpressure events available yet, accepting message");
            return true;
        }

        // Use custom handler if strategy is CUSTOM
        if (strategy == BackpressureStrategy.CUSTOM && customHandler != null) {
            try {
                CustomBackpressureHandler.BackpressureAction action = 
                        customHandler.handleMessage(message, latestEvent);

                switch (action) {
                    case ACCEPT:
                        return true;
                    case REJECT:
                        return false;
                    case RETRY_WITH_TIMEOUT:
                        // Implement retry logic with a timeout
                        return scheduleRetry(message, options, defaultRetryTimeoutMs);
                    case MAKE_ROOM:
                        // Implement logic to drop messages to make room
                        return makeRoomInMailbox();
                    default:
                        logger.warn("Unknown backpressure action: {}, defaulting to REJECT", action);
                        return false;
                }
            } catch (Exception e) {
                // Catch any exceptions from the custom handler to prevent them from affecting the actor
                logger.error("Exception in custom backpressure handler: {}", e.getMessage(), e);
                return false;
            }
        }

        // Handle according to strategy
        try {
            switch (strategy) {
                case DROP_NEW:
                    // Just reject the message when under backpressure
                    logger.debug("DROP_NEW strategy active, rejecting new message");
                    return false;

                case DROP_OLDEST:
                    // Remove the oldest message from the mailbox to make room for the new one
                    if (actor != null) {
                        try {
                            boolean success = makeRoomInMailbox();
                            if (success) {
                                logger.debug("Successfully dropped oldest message to make room");
                            } else {
                                logger.debug("Failed to drop oldest message, but accepting message anyway");
                            }
                            // Still accept the message even if we couldn't drop the oldest
                            return true;
                        } catch (Exception e) {
                            logger.error("Failed to drop oldest message: {}", e.getMessage(), e);
                            // Still accept the message even if we couldn't drop the oldest
                            return true;
                        }
                    }
                    return true;
                    
                case BLOCK:
                default:
                    // Block is handled at the caller level
                    logger.debug("BLOCK strategy active, message will be blocked");
                    return false;
            }
        } catch (Exception e) {
            // Catch any unexpected exceptions to prevent the actor from crashing
            logger.error("Exception in backpressure message handling: {}", e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Attempts to make room in the mailbox by removing the oldest message.
     * This is used by the DROP_OLDEST strategy and the MAKE_ROOM action.
     * 
     * Uses the actor's dropOldestMessage method if available, otherwise returns false.
     *
     * @return true if room was successfully made, false otherwise
     */
    private boolean makeRoomInMailbox() {
        if (actor == null) {
            logger.warn("Cannot make room in mailbox: no actor reference");
            return false;
        }
        
        try {
            // Call the actor's method to drop the oldest message
            return actor.dropOldestMessage();
        } catch (Exception e) {
            logger.error("Unexpected exception making room in mailbox: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Generates detailed status information about the backpressure system.
     *
     * @return The backpressure status
     */
    public BackpressureStatus getStatus() {
        try {
            // Get the current mailbox capacity using reflection
            int capacity = getActorCapacity();
            String actorId = actor != null ? actor.getActorId() : "unknown";
            
            // Create a defensive copy of the current size to avoid race conditions
            int currentSizeValue = currentSize.get();
            
            // Calculate fill ratio safely
            float fillRatio = calculateFillRatio(currentSizeValue, capacity);
            
            BackpressureStatus.Builder builder = new BackpressureStatus.Builder()
                .actorId(actorId)
                .currentState(currentState)
                .fillRatio(fillRatio)
                .currentSize(currentSizeValue)
                .capacity(capacity)
                .processingRate(processingRate.get())
                .enabled(enabled)
                .timestamp(Instant.now())
                .timeInCurrentState(getTimeInCurrentState())
                .lastStateChangeTime(lastStateChangeTime)
                .messagesProcessedSinceLastStateChange(messagesProcessedSinceLastStateChange)
                .recentEvents(getRecentEvents())
                .stateTransitions(getStateTransitions())
                .strategy(strategy)
                .warningThreshold(warningThreshold)
                .criticalThreshold(criticalThreshold)
                .recoveryThreshold(recoveryThreshold);

            return builder.computeAverages().build();
        } catch (Exception e) {
            // If anything goes wrong, return a minimal status object
            logger.error("Error generating backpressure status: {}", e.getMessage(), e);
            
            return new BackpressureStatus.Builder()
                .actorId(actor != null ? actor.getActorId() : "unknown")
                .currentState(currentState)
                .fillRatio(0.0f)
                .currentSize(currentSize.get())
                .capacity(Integer.MAX_VALUE)
                .processingRate(0)
                .enabled(enabled)
                .timestamp(Instant.now())
                .build();
        }
    }

    /**
     * Gets the capacity of the actor's mailbox.
     * Uses the actor's getCapacity method directly instead of reflection.
     *
     * @return The mailbox capacity or Integer.MAX_VALUE if not available
     */
    private int getActorCapacity() {
        if (actor == null) {
            return Integer.MAX_VALUE;
        }
        
        try {
            return actor.getCapacity();
        } catch (Exception e) {
            logger.debug("Could not get actor capacity: {}", e.getMessage());
            return Integer.MAX_VALUE;
        }
    }

    /**
     * Returns a builder for configuring this BackpressureManager.
     *
     * @return A builder for fluent configuration
     */
    public BackpressureBuilder<T> configure() {
        return new BackpressureBuilder<>(actor);
    }

    // Helper methods

    private float calculateFillRatio(int size, int capacity) {
        if (capacity <= 0) {
            return 0;
        }
        try {
            return Math.max(0.0f, Math.min(1.0f, (float) size / capacity));
        } catch (ArithmeticException e) {
            logger.warn("Error calculating fill ratio: {}", e.getMessage());
            return 0.0f;
        }
    }

    private BackpressureState determineBackpressureState(float fillRatio) {
        try {
            // Ensure fillRatio is within valid bounds
            float safeRatio = Math.max(0.0f, Math.min(1.0f, fillRatio));
            
            // If currently in CRITICAL, only move to RECOVERY when below recoveryThreshold
            if (currentState == BackpressureState.CRITICAL) {
                if (safeRatio < recoveryThreshold) {
                    return BackpressureState.RECOVERY;
                } else {
                    return BackpressureState.CRITICAL;
                }
            }

            // If currently in RECOVERY, only move to NORMAL when below recoveryThreshold
            // or back to CRITICAL if it rises again
            if (currentState == BackpressureState.RECOVERY) {
                if (safeRatio < recoveryThreshold) {
                    return BackpressureState.NORMAL;
                } else if (safeRatio >= criticalThreshold) {
                    return BackpressureState.CRITICAL;
                } else {
                    return BackpressureState.RECOVERY;
                }
            }

            // From NORMAL or WARNING, determine based on thresholds
            if (safeRatio >= criticalThreshold) {
                return BackpressureState.CRITICAL;
            } else if (safeRatio >= warningThreshold) {
                return BackpressureState.WARNING;
            } else {
                return BackpressureState.NORMAL;
            }
        } catch (Exception e) {
            logger.error("Error determining backpressure state: {}", e.getMessage(), e);
            // Default to current state if there's an error, or NORMAL if no current state
            return currentState != null ? currentState : BackpressureState.NORMAL;
        }
    }

    private void handleStateTransition(BackpressureState oldState, BackpressureState newState, 
            float fillRatio, int size, int capacity, long rate) {

        if (oldState == newState) {
            return; // No transition needed
        }

        try {
            // Record the time spent in the previous state
            Duration timeInPreviousState = Duration.between(lastStateChangeTime, Instant.now());

            // Update state tracking
            lastStateChangeTime = Instant.now();
            long previousMessages = messagesProcessedSinceLastStateChange;
            messagesProcessedSinceLastStateChange = messagesProcessed.getAndSet(0);
            
            // Set the current state
            currentState = newState;

            // Create a transition record with detailed information
            String reason = String.format("Fill ratio: %.2f, Size: %d/%d, Rate: %d msgs/sec, Time in previous state: %s, Messages processed: %d", 
                    fillRatio, size, capacity, rate, formatDuration(timeInPreviousState), previousMessages);
            StateTransition transition = new StateTransition(oldState, newState, Instant.now(), reason);
            
            synchronized (stateTransitions) {
                stateTransitions.add(transition);

                // Trim transition history if needed
                while (stateTransitions.size() > maxEventsToKeep) {
                    stateTransitions.remove(0);
                }
            }

            // Update backpressure active flag based on the new state
            boolean isActive = newState == BackpressureState.CRITICAL || newState == BackpressureState.WARNING;
            isBackpressureActive.set(isActive);

            // Log the transition with appropriate level based on severity
            if (newState == BackpressureState.CRITICAL) {
                logger.warn("Actor {} backpressure state changed: {} -> {} after {} ({} messages processed). {}",
                        actor != null ? actor.getActorId() : "unknown", oldState, newState, formatDuration(timeInPreviousState), 
                        previousMessages, reason);
            } else if (oldState == BackpressureState.CRITICAL && newState == BackpressureState.RECOVERY) {
                logger.info("Actor {} recovering from CRITICAL backpressure state after {} ({} messages processed). {}",
                        actor != null ? actor.getActorId() : "unknown", formatDuration(timeInPreviousState), 
                        previousMessages, reason);
            } else {
                logger.info("Actor {} backpressure state changed: {} -> {} after {} ({} messages processed). {}",
                        actor != null ? actor.getActorId() : "unknown", oldState, newState, formatDuration(timeInPreviousState), 
                        previousMessages, reason);
            }
            
            // Create a backpressure event for the state transition
            BackpressureEvent event = createBackpressureEvent(
                    newState, fillRatio, size, capacity, rate, false, capacity);
            
            // Add to recent events
            addEventToHistory(event);
            
            // Notify callback if registered
            if (callback != null) {
                try {
                    callback.accept(event);
                } catch (Exception e) {
                    logger.error("Exception in backpressure callback during state transition: {}", e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            // Catch any unexpected exceptions to prevent the actor from crashing
            logger.error("Exception during backpressure state transition: {}", e.getMessage(), e);
            // Ensure the state is still updated even if there's an exception
            currentState = newState;
            // Update backpressure active flag based on the new state
            boolean isActive = newState == BackpressureState.CRITICAL || newState == BackpressureState.WARNING;
            isBackpressureActive.set(isActive);
        }
    }
    
    /**
     * Helper method to format duration in a human-readable format
     */
    private String formatDuration(Duration duration) {
        long totalSeconds = duration.getSeconds();
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        long millis = duration.toMillis() % 1000;
        
        if (hours > 0) {
            return String.format("%dh %dm %d.%03ds", hours, minutes, seconds, millis);
        } else if (minutes > 0) {
            return String.format("%dm %d.%03ds", minutes, seconds, millis);
        } else {
            return String.format("%d.%03ds", seconds, millis);
        }
    }

    private BackpressureEvent createBackpressureEvent(BackpressureState state, float fillRatio, 
            int size, int capacity, long rate, boolean resized, int previousCapacity) {
        try {
            String actorId = actor != null ? actor.getActorId() : "unknown";
            return new BackpressureEvent(
                    actorId,
                    state,
                    fillRatio,
                    size,
                    capacity,
                    rate,
                    resized,
                    previousCapacity);
        } catch (Exception e) {
            logger.error("Error creating backpressure event: {}", e.getMessage(), e);
            // Create a fallback event with safe default values
            return new BackpressureEvent(
                    "unknown",
                    BackpressureState.NORMAL,
                    0.0f,
                    0,
                    Integer.MAX_VALUE,
                    0,
                    false,
                    Integer.MAX_VALUE);
        }
    }

    private void addEventToHistory(BackpressureEvent event) {
        if (event == null) {
            logger.warn("Attempted to add null event to history, ignoring");
            return;
        }
        
        // Thread-safe operation on the recentEvents collection
        synchronized (recentEvents) {
            try {
                // Add the new event
                recentEvents.addFirst(event);

                // Trim the history if needed
                while (recentEvents.size() > maxEventsToKeep && !recentEvents.isEmpty()) {
                    recentEvents.removeLast();
                }
            } catch (NoSuchElementException e) {
                // This shouldn't happen with the size check and synchronization, but just in case
                logger.debug("Attempted to remove from empty event history");
            } catch (Exception e) {
                logger.error("Error managing event history: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Sets the default timeout for retry operations in milliseconds.
     *
     * @param timeoutMs The timeout in milliseconds
     */
    public void setDefaultRetryTimeoutMs(long timeoutMs) {
        if (timeoutMs <= 0) {
            logger.warn("Invalid retry timeout: {}, using default value of 1000ms", timeoutMs);
            this.defaultRetryTimeoutMs = 1000;
        } else {
            this.defaultRetryTimeoutMs = timeoutMs;
        }
    }

    /**
     * Gets the default timeout for retry operations in milliseconds.
     *
     * @return The timeout in milliseconds
     */
    public long getDefaultRetryTimeoutMs() {
        return defaultRetryTimeoutMs;
    }


    
    /**
     * Schedules a message for retry after the specified timeout.
     * 
     * @param message The message to retry
     * @param options The send options
     * @param timeoutMs The timeout in milliseconds
     * @return true if the retry was scheduled, false otherwise
     */
    private boolean scheduleRetry(T message, BackpressureSendOptions options, long timeoutMs) {
        if (actor == null) {
            logger.warn("Cannot schedule retry: no actor reference");
            return false;
        }
        
        try {
            // Generate a unique key for this message
            final Object messageKey = System.identityHashCode(message) + "-" + System.nanoTime();
            
            // Create a retry entry
            RetryEntry<T> entry = new RetryEntry<>(message, options, timeoutMs, getCurrentEvent());
            
            // Store in retry queue
            retryQueue.put(messageKey, entry);
            
            // Schedule retry task
            retryExecutor.schedule(() -> {
                try {
                    // Remove from retry queue
                    RetryEntry<T> retryEntry = retryQueue.remove(messageKey);
                    if (retryEntry == null) {
                        return; // Already processed or cancelled
                    }
                    
                    // Check if backpressure is still active
                    if (!enabled || !isBackpressureActive.get()) {
                        // Backpressure no longer active, send the message
                        if (actor != null) {
                            actor.self().tell(retryEntry.getMessage());
                            logger.debug("Retry successful: backpressure no longer active");
                        }
                        return;
                    }
                    
                    // Get current backpressure state
                    BackpressureEvent currentEvent = getCurrentEvent();
                    
                    // If no events or backpressure has improved, send the message
                    if (currentEvent == null || 
                            currentEvent.getFillRatio() < retryEntry.getOriginalEvent().getFillRatio()) {
                        if (actor != null) {
                            actor.self().tell(retryEntry.getMessage());
                            logger.debug("Retry successful: backpressure has improved");
                        }
                    } else {
                        // Backpressure still active and hasn't improved
                        logger.debug("Retry failed: backpressure still active");
                    }
                } catch (Exception e) {
                    logger.error("Error during message retry: {}", e.getMessage(), e);
                }
            }, timeoutMs, TimeUnit.MILLISECONDS);
            
            logger.debug("Scheduled message retry in {}ms", timeoutMs);
            return true;
        } catch (Exception e) {
            logger.error("Failed to schedule message retry: {}", e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Gets the current backpressure event, or creates a default one if none exists.
     * 
     * @return The current backpressure event
     */
    private BackpressureEvent getCurrentEvent() {
        synchronized (recentEvents) {
            return !recentEvents.isEmpty() ? 
                    recentEvents.getFirst() : 
                    new BackpressureEvent(
                        actor != null ? actor.getActorId() : "unknown",
                        currentState,
                        1.0f, // Assume worst case
                        Integer.MAX_VALUE,
                        Integer.MAX_VALUE,
                        0,
                        false,
                        Integer.MAX_VALUE);
        }
    }
    
    /**
     * Cleans up resources used by this BackpressureManager.
     * Should be called when the actor is shutting down.
     */
    public void shutdown() {
        try {
            retryExecutor.shutdownNow();
            retryQueue.clear();
        } catch (Exception e) {
            logger.error("Error during BackpressureManager shutdown: {}", e.getMessage(), e);
        }
    }
}
