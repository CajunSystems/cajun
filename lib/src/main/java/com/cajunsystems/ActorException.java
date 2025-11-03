package com.cajunsystems;

/**
 * Exception thrown when an error occurs in an actor.
 * This exception is typically used when escalating errors from actors to their supervisors.
 */
public class ActorException extends RuntimeException {

    /** The ID of the actor where the exception occurred. */
    private final String actorId;

    /**
     * Creates a new ActorException with the specified detail message.
     *
     * @param message the detail message
     */
    public ActorException(String message) {
        super(message);
        this.actorId = null;
    }

    /**
     * Creates a new ActorException with the specified detail message and cause.
     *
     * @param message the detail message
     * @param cause the cause of the exception
     */
    public ActorException(String message, Throwable cause) {
        super(message, cause);
        this.actorId = null;
    }

    /**
     * Creates a new ActorException with the specified detail message and actor ID.
     *
     * @param message the detail message
     * @param actorId the ID of the actor where the exception occurred
     */
    public ActorException(String message, String actorId) {
        super(message);
        this.actorId = actorId;
    }

    /**
     * Creates a new ActorException with the specified detail message, cause, and actor ID.
     *
     * @param message the detail message
     * @param cause the cause of the exception
     * @param actorId the ID of the actor where the exception occurred
     */
    public ActorException(String message, Throwable cause, String actorId) {
        super(message, cause);
        this.actorId = actorId;
    }

    /**
     * Returns the ID of the actor where the exception occurred.
     *
     * @return the actor ID, or null if not specified
     */
    public String getActorId() {
        return actorId;
    }
}
