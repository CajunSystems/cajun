package com.cajunsystems.backpressure;

import com.cajunsystems.Actor;
import com.cajunsystems.ActorSystem;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.cajunsystems.Pid;
import com.cajunsystems.config.BackpressureConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides centralized backpressure monitoring and management through the ActorSystem.
 * This class allows accessing backpressure functionality via an actor's PID, enforcing
 * the principle that monitoring should be done through the system rather than directly 
 * through actor references.
 */
public class SystemBackpressureMonitor {
    
    private static final Logger logger = LoggerFactory.getLogger(SystemBackpressureMonitor.class);
    
    private final ActorSystem system;
    
    /**
     * Creates a new SystemBackpressureMonitor for the specified actor system.
     *
     * @param system The actor system to monitor
     */
    public SystemBackpressureMonitor(ActorSystem system) {
        this.system = system;
    }
    
    /**
     * Creates a builder for configuring backpressure on an actor identified by PID.
     * This provides a modern, fluent API for backpressure configuration.
     *
     * @param <T> The type of messages processed by the actor
     * @param pid The PID of the actor to configure
     * @return A builder for fluent backpressure configuration
     * @throws IllegalArgumentException if the actor does not exist
     */
    public <T> BackpressureBuilder<T> configureBackpressure(Pid pid) {
        // Verify that the actor exists but use the PID-based builder
        getActorOrThrow(pid);
        return new BackpressureBuilder<>(system, pid);
    }
    
    /**
     * Retrieves a BackpressureManager for the specified actor, creating it if needed.
     *
     * @param <T> The type of messages processed by the actor
     * @param pid The PID of the actor to get the manager for
     * @return The BackpressureManager for the actor
     * @throws IllegalArgumentException if the actor does not exist
     */
    @SuppressWarnings("unchecked")
    public <T> BackpressureManager<T> getBackpressureManager(Pid pid) {
        Actor<T> actor = getActorOrThrow(pid);
        return getOrCreateBackpressureManager(actor);
    }
    
    /**
     * Accesses or creates a BackpressureManager for the specified actor.
     *
     * @param <T> The type of messages processed by the actor
     * @param actor The actor to get the manager for
     * @return The BackpressureManager for the actor
     */
    @SuppressWarnings("unchecked")
    private <T> BackpressureManager<T> getOrCreateBackpressureManager(Actor<T> actor) {
        BackpressureManager<T> manager = null;
        
        try {
            // Try to access the backpressureManager field via reflection
            Field field = Actor.class.getDeclaredField("backpressureManager");
            field.setAccessible(true);
            manager = (BackpressureManager<T>) field.get(actor);
            
            if (manager == null) {
                manager = new BackpressureManager<T>();
                field.set(actor, manager);
            }
        } catch (Exception e) {
            // Fall back to creating a new manager without storing it
            logger.warn("Could not access backpressureManager field on actor {}, creating temporary manager", actor.getActorId(), e);
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
     * @param pid The PID of the actor to get status for
     * @return Detailed backpressure status information
     * @throws IllegalArgumentException if the actor does not exist
     */
    public BackpressureStatus getBackpressureStatus(Pid pid) {
        return getBackpressureManager(pid).getStatus();
    }
    
    /**
     * Gets the current backpressure state of an actor.
     *
     * @param pid The PID of the actor to get the state for
     * @return The current backpressure state
     * @throws IllegalArgumentException if the actor does not exist
     */
    public BackpressureState getCurrentBackpressureState(Pid pid) {
        return getBackpressureManager(pid).getCurrentState();
    }
    
    /**
     * Gets the list of recent backpressure events for an actor.
     *
     * @param pid The PID of the actor to get events for
     * @return The recent backpressure events
     * @throws IllegalArgumentException if the actor does not exist
     */
    public List<BackpressureEvent> getRecentBackpressureEvents(Pid pid) {
        return getBackpressureManager(pid).getRecentEvents();
    }
    
    /**
     * Sends a message to an actor with customizable backpressure options.
     *
     * @param <T> The type of messages processed by the actor
     * @param pid The PID of the actor to send the message to
     * @param message The message to send
     * @param options Options for handling backpressure
     * @return true if the message was accepted, false otherwise
     * @throws IllegalArgumentException if the actor does not exist
     */
    @SuppressWarnings("unchecked")
    public <T> boolean tellWithOptions(Pid pid, T message, BackpressureSendOptions options) {
        Actor<T> actor = getActorOrThrow(pid);
        
        if (!actor.isBackpressureEnabled()) {
            // When backpressure is disabled, send through the actor's PID
            pid.tell(message);
            return true;
        }
        
        BackpressureManager<T> manager = getBackpressureManager(pid);
        
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
                    long timeoutMs = options.getTimeout().toMillis();
                    long endTime = System.currentTimeMillis() + timeoutMs;
                    
                    while (System.currentTimeMillis() < endTime) {
                        if (!manager.isBackpressureActive()) {
                            // Send through the actor's PID
                            pid.tell(message);
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
        // Send through the actor's PID
        pid.tell(message);
        return true;
    }
    
    /**
     * Sets a callback to be notified of backpressure events.
     *
     * @param pid The PID of the actor to set the callback for
     * @param callback The callback to invoke with event information
     * @throws IllegalArgumentException if the actor does not exist
     */
    public <T> void setBackpressureCallback(Pid pid, Consumer<BackpressureEvent> callback) {
        getBackpressureManager(pid).setCallback(callback);
    }
    
    /**
     * Gets whether backpressure is active for an actor.
     *
     * @param pid The PID of the actor to check
     * @return true if backpressure is active, false otherwise
     * @throws IllegalArgumentException if the actor does not exist
     */
    public <T> boolean isBackpressureActive(Pid pid) {
        Actor<T> actor = getActorOrThrow(pid);
        return actor.isBackpressureEnabled() && getBackpressureManager(pid).isBackpressureActive();
    }
    
    /**
     * Gets the time an actor has been in its current backpressure state.
     *
     * @param pid The PID of the actor to check
     * @return The duration the actor has been in its current state
     * @throws IllegalArgumentException if the actor does not exist
     */
    public Duration getTimeInCurrentBackpressureState(Pid pid) {
        return getBackpressureManager(pid).getTimeInCurrentState();
    }
    
    /**
     * Gets the actor corresponding to the provided PID.
     *
     * @param <T> The type of messages processed by the actor
     * @param pid The PID of the actor to retrieve
     * @return The actor corresponding to the PID
     * @throws IllegalArgumentException if the actor does not exist
     */
    @SuppressWarnings("unchecked")
    private <T> Actor<T> getActorOrThrow(Pid pid) {
        Optional<Actor<?>> actorOptional = system.getActorOptional(pid);
        if (actorOptional.isEmpty()) {
            throw new IllegalArgumentException("Actor with PID " + pid + " not found in the system");
        }
        return (Actor<T>) actorOptional.get();
    }
}
