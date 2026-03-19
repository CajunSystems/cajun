package com.cajunsystems.direct.internal;

/**
 * Internal wire protocol used by direct-style actor adapters to carry both success and
 * failure replies through the standard ask-pattern infrastructure.
 *
 * <p>This sealed interface allows exception information to be transmitted back to the
 * caller of {@link com.cajunsystems.direct.ActorRef#call} without hanging the ask future
 * until timeout.
 *
 * @param <T> The reply value type
 */
public sealed interface DirectWireReply<T> {

    /**
     * Represents a successful reply carrying the handler's return value.
     */
    record Success<T>(T value) implements DirectWireReply<T> {}

    /**
     * Represents a failed reply carrying error information.
     */
    record Failure<T>(String errorMessage, Throwable cause) implements DirectWireReply<T> {}
}
