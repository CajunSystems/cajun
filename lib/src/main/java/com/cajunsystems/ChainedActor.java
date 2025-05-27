package com.cajunsystems;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An extension of the Actor class that provides workflow chaining capabilities.
 * This allows actors to be connected in a sequence where messages are processed
 * and then forwarded to the next actor in the chain.
 *
 * @param <Message> The type of messages this actor processes
 */
public abstract class ChainedActor<Message> extends Actor<Message> {

    private static final Logger logger = LoggerFactory.getLogger(ChainedActor.class);
    
    // Reference to the next actor in a workflow chain
    private Pid nextActor;

    /**
     * Creates a new ChainedActor with an auto-generated ID.
     *
     * @param system The actor system this actor belongs to
     */
    public ChainedActor(ActorSystem system) {
        super(system);
    }

    /**
     * Creates a new ChainedActor with the specified ID.
     *
     * @param system The actor system this actor belongs to
     * @param actorId The ID for this actor
     */
    public ChainedActor(ActorSystem system, String actorId) {
        super(system, actorId);
    }

    /**
     * Sets the next actor in a workflow chain.
     * 
     * @param nextActor The PID of the next actor
     * @return This actor instance for method chaining
     */
    public ChainedActor<Message> withNext(Pid nextActor) {
        this.nextActor = nextActor;
        return this;
    }

    /**
     * Forwards a message to the next actor in the workflow chain.
     * If no next actor is set, this method does nothing.
     * 
     * @param message The message to forward
     */
    protected void forward(Message message) {
        if (nextActor != null) {
            nextActor.tell(message);
        } else {
            logger.warn("Actor {} attempted to forward message but no next actor is set", getActorId());
        }
    }

    /**
     * Gets the next actor in the workflow chain.
     * 
     * @return The PID of the next actor, or null if no next actor is set
     */
    public Pid getNextActor() {
        return nextActor;
    }
}
