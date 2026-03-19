package com.cajunsystems.direct;

import com.cajunsystems.ActorContext;
import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import org.slf4j.Logger;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Context provided to {@link DirectHandler} and {@link StatefulDirectHandler} instances,
 * giving access to actor functionality without exposing internal implementation details.
 *
 * <p>This is the direct-style equivalent of {@link ActorContext}.
 */
public interface DirectContext {

    /**
     * Gets the PID of this actor.
     *
     * @return The PID of this actor
     */
    Pid self();

    /**
     * Gets the actor ID.
     *
     * @return The actor ID string
     */
    String getActorId();

    /**
     * Sends a fire-and-forget message to another actor.
     * Does not block; returns immediately.
     *
     * @param <T>     The message type
     * @param target  The target actor's PID
     * @param message The message to send
     */
    <T> void tell(Pid target, T message);

    /**
     * Sends a message to this actor after a delay.
     *
     * @param <T>      The message type
     * @param message  The message to send
     * @param delay    The delay amount
     * @param timeUnit The time unit for the delay
     */
    <T> void tellSelf(T message, long delay, TimeUnit timeUnit);

    /**
     * Sends a message to this actor immediately (enqueued).
     *
     * @param <T>     The message type
     * @param message The message to send
     */
    <T> void tellSelf(T message);

    /**
     * Gets the parent actor's PID, or {@code null} if this actor has no parent.
     *
     * @return The parent's PID, or null
     */
    Pid getParent();

    /**
     * Gets the PIDs of all child actors.
     *
     * @return A map of child actor IDs to their PIDs
     */
    Map<String, Pid> getChildren();

    /**
     * Gets the underlying actor system.
     *
     * @return The actor system
     */
    ActorSystem getSystem();

    /**
     * Stops this actor after it finishes processing the current message.
     */
    void stop();

    /**
     * Gets a logger configured with this actor's ID as context.
     *
     * @return An SLF4J logger instance
     */
    Logger getLogger();

    /**
     * Returns the underlying {@link ActorContext} for advanced interoperability
     * with the base Cajun API.
     *
     * @return The underlying ActorContext
     */
    ActorContext underlying();
}
