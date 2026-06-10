package com.cajunsystems;

/**
 * Defines lifecycle callbacks for an actor used by MailboxProcessor.
 *
 * @param <T> The type of messages accepted by the actor
 */
public interface ActorLifecycle<T> {
    /** Called before mailbox processing begins. */
    void preStart();

    /**
     * Called to dispatch a received message to the actor.
     *
     * @param message the message to be processed by the actor
     */
    void receive(T message);

    /** Called after mailbox processing ends. */
    void postStop();
}
