package com.cajunsystems;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Provides a restricted view of actor functionality to handlers.
 * This allows handlers to access necessary actor features without exposing internal implementation details.
 */
public interface ActorContext {
    
    /**
     * Gets the PID of this actor.
     *
     * @return The PID of this actor
     */
    Pid self();
    
    /**
     * Gets the actor ID.
     *
     * @return The actor ID
     */
    String getActorId();
    
    /**
     * Sends a message to another actor.
     *
     * @param <T> The type of the message
     * @param target The target actor's PID
     * @param message The message to send
     */
    <T> void tell(Pid target, T message);
    
    /**
     * Sends a message to this actor after a delay.
     *
     * @param <T> The type of the message
     * @param message The message to send
     * @param delay The delay amount
     * @param timeUnit The time unit for the delay
     */
    <T> void tellSelf(T message, long delay, TimeUnit timeUnit);
    
    /**
     * Sends a message to this actor.
     *
     * @param <T> The type of the message
     * @param message The message to send
     */
    <T> void tellSelf(T message);
    
    /**
     * Creates a child actor of the specified class.
     *
     * @param <T> The type of the child actor
     * @param handlerClass The class of the handler for the child actor
     * @param childId The ID for the child actor
     * @return The PID of the created child actor
     */
    <T> Pid createChild(Class<?> handlerClass, String childId);
    
    /**
     * Creates a child actor of the specified class with an auto-generated ID.
     *
     * @param <T> The type of the child actor
     * @param handlerClass The class of the handler for the child actor
     * @return The PID of the created child actor
     */
    <T> Pid createChild(Class<?> handlerClass);
    
    /**
     * Gets the parent of this actor, or null if this actor has no parent.
     *
     * @return The parent actor's PID, or null if no parent
     */
    Pid getParent();
    
    /**
     * Gets the PIDs of all child actors.
     *
     * @return A map of child actor IDs to their PIDs
     */
    Map<String, Pid> getChildren();
    
    /**
     * Gets the actor system this actor belongs to.
     *
     * @return The actor system
     */
    ActorSystem getSystem();
    
    /**
     * Stops this actor.
     */
    void stop();
}
