package systems.cajun.backpressure;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import systems.cajun.Actor;
import systems.cajun.ResizableBlockingQueue;
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
    public void enable(BackpressureConfig config) {
        this.config = config;
        this.enabled = true;
        this.criticalThreshold = config.getHighWatermark();
        this.recoveryThreshold = config.getLowWatermark();

        // Default warning threshold to midpoint between normal and critical if not set
        if (this.warningThreshold <= 0) {
            this.warningThreshold = this.criticalThreshold * 0.75f;
        }
    }

    /**
     * Disables backpressure for the managed actor.
     */
    public void disable() {
        this.enabled = false;
    }

    /**
     * Sets the backpressure strategy.
     *
     * @param strategy The strategy to use
     */
    public void setStrategy(BackpressureStrategy strategy) {
        this.strategy = strategy;
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
        this.warningThreshold = warningThreshold;
        this.criticalThreshold = criticalThreshold;
        this.recoveryThreshold = recoveryThreshold;
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
        boolean isActive = newState == BackpressureState.CRITICAL;
        isBackpressureActive.set(isActive);

        // Create a backpressure event for the current metrics
        BackpressureEvent event = createBackpressureEvent(
            newState, fillRatio, currentMailboxSize, mailboxCapacity, rate, false, mailboxCapacity);

        // Add to recent events and trim if needed
        addEventToHistory(event);

        // Notify callback if registered
        if (callback != null) {
            callback.accept(event);
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
        return Duration.between(lastStateChangeTime, Instant.now());
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
        return new ArrayList<>(stateTransitions);
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
            latestEvent = recentEvents.isEmpty() ? null : recentEvents.getFirst();
        }

        // Use custom handler if strategy is CUSTOM
        if (strategy == BackpressureStrategy.CUSTOM && customHandler != null && latestEvent != null) {
            CustomBackpressureHandler.BackpressureAction action = 
                    customHandler.handleMessage(message, latestEvent);

            switch (action) {
                case ACCEPT:
                    return true;
                case REJECT:
                    return false;
                case RETRY_WITH_TIMEOUT:
                    // Would need to implement retry logic here
                    return false;
                case MAKE_ROOM:
                    // Would need to implement logic to drop messages to make room
                    // In a real implementation, we would need to access the actual queue
                    return true;
                default:
                    return false;
            }
        }

        // Handle according to strategy
        switch (strategy) {
            case DROP_NEW:
                // Just reject the message when under backpressure
                return false;

            case DROP_OLDEST:
                // In a real implementation, this would remove the oldest message
                // from the mailbox to make room for the new one
                return true;

            case BLOCK:
            default:
                // Block is handled at the caller level
                return false;
        }
    }

    /**
     * Generates detailed status information about the backpressure system.
     *
     * @return The backpressure status
     */
    public BackpressureStatus getStatus() {
        // Get the current mailbox capacity using reflection
        int capacity = getActorCapacity();

        BackpressureStatus.Builder builder = new BackpressureStatus.Builder()
            .actorId(actor.getActorId())
            .currentState(currentState)
            .fillRatio(currentSize.get() > 0 && capacity > 0 ? 
                    (float) currentSize.get() / capacity : 0)
            .currentSize(currentSize.get())
            .capacity(capacity)
            .processingRate(processingRate.get())
            .enabled(enabled)
            .timestamp(Instant.now())
            .timeInCurrentState(getTimeInCurrentState())
            .lastStateChangeTime(lastStateChangeTime)
            .messagesProcessedSinceLastStateChange(messagesProcessedSinceLastStateChange)
            .recentEvents(getRecentEvents())
            .stateTransitions(getStateTransitions());

        return builder.computeAverages().build();
    }

    /**
     * Gets the capacity of the actor's mailbox using reflection.
     * This is a workaround to access the mailbox capacity until
     * a proper API is exposed in the Actor class.
     *
     * @return The mailbox capacity or Integer.MAX_VALUE if not available
     */
    private int getActorCapacity() {
        try {
            // Try to access the mailbox field
            java.lang.reflect.Field mailboxField = Actor.class.getDeclaredField("mailbox");
            mailboxField.setAccessible(true);
            Object mailbox = mailboxField.get(actor);

            // Check if it's a ResizableBlockingQueue
            if (mailbox instanceof java.util.concurrent.BlockingQueue) {
                if (mailbox.getClass().getSimpleName().equals("ResizableBlockingQueue")) {
                    // Get the capacity using reflection
                    java.lang.reflect.Method getCapacityMethod = 
                            mailbox.getClass().getDeclaredMethod("getCapacity");
                    getCapacityMethod.setAccessible(true);
                    return (int) getCapacityMethod.invoke(mailbox);
                } else {
                    // For other BlockingQueue implementations, try to get remaining capacity
                    java.util.concurrent.BlockingQueue<?> queue = 
                            (java.util.concurrent.BlockingQueue<?>) mailbox;
                    return Integer.MAX_VALUE; // Unbounded queue
                }
            }
        } catch (Exception e) {
            logger.debug("Could not get actor capacity: {}", e.getMessage());
        }

        // Default to max value if we couldn't get the actual capacity
        return Integer.MAX_VALUE;
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
        return (float) size / capacity;
    }

    private BackpressureState determineBackpressureState(float fillRatio) {
        // If currently in CRITICAL, only move to RECOVERY when below criticalThreshold
        if (currentState == BackpressureState.CRITICAL) {
            if (fillRatio < recoveryThreshold) {
                return BackpressureState.RECOVERY;
            } else {
                return BackpressureState.CRITICAL;
            }
        }

        // If currently in RECOVERY, only move to NORMAL when below recoveryThreshold
        // or back to CRITICAL if it rises again
        if (currentState == BackpressureState.RECOVERY) {
            if (fillRatio < recoveryThreshold) {
                return BackpressureState.NORMAL;
            } else if (fillRatio >= criticalThreshold) {
                return BackpressureState.CRITICAL;
            } else {
                return BackpressureState.RECOVERY;
            }
        }

        // From NORMAL or WARNING, determine based on thresholds
        if (fillRatio >= criticalThreshold) {
            return BackpressureState.CRITICAL;
        } else if (fillRatio >= warningThreshold) {
            return BackpressureState.WARNING;
        } else {
            return BackpressureState.NORMAL;
        }
    }

    private void handleStateTransition(BackpressureState oldState, BackpressureState newState, 
            float fillRatio, int size, int capacity, long rate) {

        // Record the time spent in the previous state
        Duration timeInPreviousState = Duration.between(lastStateChangeTime, Instant.now());

        // Update state tracking
        lastStateChangeTime = Instant.now();
        messagesProcessedSinceLastStateChange = messagesProcessed.getAndSet(0);
        currentState = newState;

        // Create a transition record
        String reason = String.format("Fill ratio: %.2f, Size: %d/%d, Rate: %d msgs/sec", 
                fillRatio, size, capacity, rate);
        StateTransition transition = new StateTransition(oldState, newState, Instant.now(), reason);
        stateTransitions.add(transition);

        // Trim transition history if needed
        while (stateTransitions.size() > maxEventsToKeep) {
            stateTransitions.remove(0);
        }

        // Log the transition
        logger.info("Actor {} backpressure state changed: {} -> {} after {} ({} messages processed). {}",
                actor.getActorId(), oldState, newState, timeInPreviousState, 
                messagesProcessedSinceLastStateChange, reason);
    }

    private BackpressureEvent createBackpressureEvent(BackpressureState state, float fillRatio, 
            int size, int capacity, long rate, boolean resized, int previousCapacity) {

        return new BackpressureEvent(
                actor.getActorId(),
                state,
                fillRatio,
                size,
                capacity,
                rate,
                resized,
                previousCapacity);
    }

    private void addEventToHistory(BackpressureEvent event) {
        // Thread-safe operation on the recentEvents collection
        synchronized (recentEvents) {
            // Add the new event
            recentEvents.addFirst(event);

            // Trim the history if needed
            while (recentEvents.size() > maxEventsToKeep && !recentEvents.isEmpty()) {
                try {
                    recentEvents.removeLast();
                } catch (NoSuchElementException e) {
                    // This shouldn't happen with the size check and synchronization, but just in case
                    logger.debug("Attempted to remove from empty event history");
                    break;
                }
            }
        }
    }
}
