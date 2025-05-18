package systems.cajun.backpressure;

import systems.cajun.Actor;
import systems.cajun.config.BackpressureConfig;
import systems.cajun.config.MailboxConfig;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Provides extension methods for enhanced backpressure functionality in actors.
 * This class serves as a bridge between the existing Actor implementation and
 * the new backpressure API without requiring extensive modifications to the Actor class.
 */
public class ActorBackpressureExtensions {

    /**
     * Creates a builder for configuring backpressure on an actor.
     * This provides a modern, fluent API for backpressure configuration.
     *
     * @param <T> The type of messages processed by the actor
     * @param actor The actor to configure
     * @return A builder for fluent backpressure configuration
     */
    public static <T> BackpressureBuilder<T> configureBackpressure(Actor<T> actor) {
        return new BackpressureBuilder<>(actor);
    }

    /**
     * Retrieves a BackpressureManager for the specified actor, creating it if needed.
     *
     * @param <T> The type of messages processed by the actor
     * @param actor The actor to get the manager for
     * @return The BackpressureManager for the actor
     */
    public static <T> BackpressureManager<T> getBackpressureManager(Actor<T> actor) {
        // This access is not ideal but necessary for compatibility
        // Future versions should integrate this directly into the Actor class
        BackpressureManager<T> manager = null;
        
        try {
            // Try to access the backpressureManager field via reflection
            java.lang.reflect.Field field = Actor.class.getDeclaredField("backpressureManager");
            field.setAccessible(true);
            manager = (BackpressureManager<T>) field.get(actor);
            
            if (manager == null) {
                manager = new BackpressureManager<T>();
                field.set(actor, manager);
            }
        } catch (Exception e) {
            // Fall back to creating a new manager without storing it
            manager = new BackpressureManager<T>();
            // Initialize with default config
            BackpressureConfig defaultConfig = new BackpressureConfig();
            manager.enable(defaultConfig);
        }
        
        return manager;
    }

    /**
     * Gets comprehensive status information about an actor's backpressure system.
     *
     * @param <T> The type of messages processed by the actor
     * @param actor The actor to get status for
     * @return Detailed backpressure status information
     */
    public static <T> BackpressureStatus getBackpressureStatus(Actor<T> actor) {
        return getBackpressureManager(actor).getStatus();
    }

    /**
     * Gets the current backpressure state of an actor.
     *
     * @param <T> The type of messages processed by the actor
     * @param actor The actor to get the state for
     * @return The current backpressure state
     */
    public static <T> BackpressureState getCurrentBackpressureState(Actor<T> actor) {
        return getBackpressureManager(actor).getCurrentState();
    }

    /**
     * Gets the list of recent backpressure events for an actor.
     *
     * @param <T> The type of messages processed by the actor
     * @param actor The actor to get events for
     * @return The recent backpressure events
     */
    public static <T> List<BackpressureEvent> getRecentBackpressureEvents(Actor<T> actor) {
        return getBackpressureManager(actor).getRecentEvents();
    }

    /**
     * Sends a message to an actor with customizable backpressure options.
     *
     * @param <T> The type of messages processed by the actor
     * @param actor The actor to send the message to
     * @param message The message to send
     * @param options Options for handling backpressure
     * @return true if the message was accepted, false otherwise
     */
    public static <T> boolean tellWithOptions(Actor<T> actor, T message, BackpressureSendOptions options) {
        if (!actor.isBackpressureEnabled()) {
            // When backpressure is disabled, send through the actor's PID instead of directly
            actor.self().tell(message);
            return true;
        }
        
        BackpressureManager<T> manager = getBackpressureManager(actor);
        
        // Check if message should be handled by strategy
        if (manager.isBackpressureActive()) {
            boolean accept = manager.handleMessage(message, options);
            if (!accept) {
                // If not accepted and we don't want to block, return false
                if (!options.isBlockUntilAccepted()) {
                    return false;
                }
                
                // Otherwise try with timeout
                try {
                    // Instead of using tryTell which doesn't exist, use tell with timeout handling
                    long timeoutMs = options.getTimeout().toMillis();
                    long endTime = System.currentTimeMillis() + timeoutMs;
                    
                    while (System.currentTimeMillis() < endTime) {
                        if (!manager.isBackpressureActive()) {
                            // Send through the actor's PID instead of directly
                            actor.self().tell(message);
                            return true;
                        }
                        
                        // Wait a bit before retrying
                        Thread.sleep(Math.min(10, timeoutMs));
                    }
                    
                    // Timeout expired and backpressure is still active
                    return false;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        
        // No backpressure or message accepted by strategy
        // Send through the actor's PID instead of directly
        actor.self().tell(message);
        return true;
    }

    /**
     * Sets a callback to be notified of backpressure events.
     *
     * @param <T> The type of messages processed by the actor
     * @param actor The actor to set the callback for
     * @param callback The callback to invoke with event information
     */
    public static <T> void setBackpressureCallback(Actor<T> actor, Consumer<BackpressureEvent> callback) {
        getBackpressureManager(actor).setCallback(callback);
    }

    /**
     * Gets whether backpressure is active for an actor.
     *
     * @param <T> The type of messages processed by the actor
     * @param actor The actor to check
     * @return true if backpressure is active, false otherwise
     */
    public static <T> boolean isBackpressureActive(Actor<T> actor) {
        return actor.isBackpressureEnabled() && getBackpressureManager(actor).isBackpressureActive();
    }

    /**
     * Gets the time an actor has been in its current backpressure state.
     *
     * @param <T> The type of messages processed by the actor
     * @param actor The actor to check
     * @return The duration the actor has been in its current state
     */
    public static <T> Duration getTimeInCurrentBackpressureState(Actor<T> actor) {
        return getBackpressureManager(actor).getTimeInCurrentState();
    }
}
