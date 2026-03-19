package com.cajunsystems.direct;

/**
 * Exception thrown when a direct actor call fails.
 *
 * <p>This exception is thrown by {@link ActorRef#call(Object)} when:
 * <ul>
 *   <li>The actor's {@link DirectHandler#onError} method throws</li>
 *   <li>The ask times out</li>
 *   <li>The actor system encounters an error delivering the message</li>
 * </ul>
 */
public class DirectActorException extends RuntimeException {

    public DirectActorException(String message) {
        super(message);
    }

    public DirectActorException(String message, Throwable cause) {
        super(message, cause);
    }

    public DirectActorException(Throwable cause) {
        super(cause);
    }
}
