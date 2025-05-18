package systems.cajun.backpressure;

import systems.cajun.Actor;
import systems.cajun.ActorSystem;
import systems.cajun.Pid;
import systems.cajun.config.BackpressureConfig;

import java.util.function.Consumer;

/**
 * Builder for configuring backpressure on an actor through the ActorSystem using the actor's PID.
 * This class provides a modern, fluent API for backpressure configuration.
 *
 * @param <T> The type of messages processed by the actor
 */
public class SystemBackpressureBuilder<T> {
    private final Pid pid;
    private final BackpressureManager<T> manager;
    
    /**
     * Creates a new SystemBackpressureBuilder for the specified actor.
     *
     * @param system The actor system
     * @param pid The PID of the actor to configure
     * @param actor The actor to configure
     */
    public SystemBackpressureBuilder(ActorSystem system, Pid pid, Actor<T> actor) {
        this.pid = pid;
        this.manager = system.getBackpressureMonitor().getBackpressureManager(pid);
    }
    
    /**
     * Sets a custom backpressure handler for more advanced control.
     * This is only used when the strategy is set to CUSTOM.
     *
     * @param handler The custom handler
     * @return This builder for chaining
     */
    public SystemBackpressureBuilder<T> withCustomHandler(CustomBackpressureHandler<T> handler) {
        manager.setCustomHandler(handler);
        return this;
    }
    
    /**
     * Sets the backpressure strategy.
     *
     * @param strategy The strategy to use
     * @return This builder for chaining
     */
    public SystemBackpressureBuilder<T> withStrategy(BackpressureStrategy strategy) {
        manager.setStrategy(strategy);
        return this;
    }
    
    /**
     * Sets a callback to be notified of backpressure events.
     *
     * @param callback The callback to invoke with event information
     * @return This builder for chaining
     */
    public SystemBackpressureBuilder<T> withCallback(Consumer<BackpressureEvent> callback) {
        manager.setCallback(callback);
        return this;
    }
    
    /**
     * Sets the maximum number of events to keep in history.
     *
     * @param maxEventsToKeep The maximum number of events to keep
     * @return This builder for chaining
     */
    public SystemBackpressureBuilder<T> withMaxEvents(int maxEventsToKeep) {
        manager.setMaxEventsToKeep(maxEventsToKeep);
        return this;
    }
    
    /**
     * Enables backpressure for the actor with the specified configuration.
     *
     * @param config The backpressure configuration
     * @return This builder for chaining
     */
    public SystemBackpressureBuilder<T> enable(BackpressureConfig config) {
        manager.enable(config);
        return this;
    }
    
    /**
     * Disables backpressure for the actor.
     *
     * @return This builder for chaining
     */
    public SystemBackpressureBuilder<T> disable() {
        manager.disable();
        return this;
    }
    
    /**
     * Gets the current backpressure status for the actor.
     *
     * @return The current backpressure status
     */
    public BackpressureStatus getStatus() {
        return manager.getStatus();
    }
    
    /**
     * Gets the PID of the actor being configured.
     *
     * @return The actor's PID
     */
    public Pid getPid() {
        return pid;
    }
}
