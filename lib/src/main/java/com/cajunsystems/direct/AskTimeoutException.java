package com.cajunsystems.direct;

import java.time.Duration;

/**
 * A RuntimeException thrown when a blocking ask() call exceeds its timeout.
 * Contains the timeout duration and the target actor's identifier for diagnostic purposes.
 */
public class AskTimeoutException extends RuntimeException {

    private final Duration timeout;
    private final String targetActorId;

    /**
     * Creates a new AskTimeoutException.
     *
     * @param targetActorId the identifier of the target actor that did not respond in time
     * @param timeout       the duration that was exceeded
     */
    public AskTimeoutException(String targetActorId, Duration timeout) {
        super("Ask to actor '" + targetActorId + "' timed out after " + timeout);
        this.targetActorId = targetActorId;
        this.timeout = timeout;
    }

    /**
     * Creates a new AskTimeoutException with a cause.
     *
     * @param targetActorId the identifier of the target actor that did not respond in time
     * @param timeout       the duration that was exceeded
     * @param cause         the underlying cause
     */
    public AskTimeoutException(String targetActorId, Duration timeout, Throwable cause) {
        super("Ask to actor '" + targetActorId + "' timed out after " + timeout, cause);
        this.targetActorId = targetActorId;
        this.timeout = timeout;
    }

    /**
     * Returns the timeout duration that was exceeded.
     *
     * @return the timeout duration
     */
    public Duration getTimeout() {
        return timeout;
    }

    /**
     * Returns the identifier of the target actor that did not respond in time.
     *
     * @return the target actor's identifier
     */
    public String getTargetActorId() {
        return targetActorId;
    }
}