package com.cajunsystems;

import com.cajunsystems.builder.ActorBuilder;
import com.cajunsystems.handler.Handler;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Optional;
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
     * Replies to a message that implements {@link ReplyingMessage}.
     * This is a convenience method that extracts the replyTo PID and sends the response.
     *
     * @param <T> The type of the response message
     * @param request The original request message that implements ReplyingMessage
     * @param response The response message to send
     */
    <T> void reply(ReplyingMessage request, T response);
    
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
     * Creates a builder for configuring a child actor using the fluent API.
     * The returned builder automatically sets the parent reference so callers
     * can customize supervision strategy, mailbox, etc., before spawning.
     *
     * @param <Message> The type of messages the child actor processes
     * @param handlerClass The handler implementation for the child
     * @return A builder preconfigured with the current actor as parent
     */
    <Message> ActorBuilder<Message> childBuilder(Class<? extends Handler<Message>> handlerClass);

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
    
    /**
     * Gets the sender of the current message being processed.
     * This is useful for the ask pattern where the sender is the temporary reply actor.
     * Returns an empty Optional if there is no sender context (e.g., for messages sent via tell without ask).
     *
     * @return An Optional containing the PID of the sender, or empty if no sender context
     */
    Optional<Pid> getSender();
    
    /**
     * Forwards a message to another actor, preserving the original sender context.
     * This is useful when an actor acts as an intermediary and wants the final recipient
     * to know about the original sender (e.g., for ask pattern replies).
     * 
     * @param <T> The type of the message
     * @param target The target actor PID
     * @param message The message to forward
     */
    <T> void forward(Pid target, T message);
    
    /**
     * Gets a logger for this actor with the actor ID as context.
     * This provides consistent logging output across all actors.
     *
     * @return A logger instance configured for this actor
     */
    Logger getLogger();
}
