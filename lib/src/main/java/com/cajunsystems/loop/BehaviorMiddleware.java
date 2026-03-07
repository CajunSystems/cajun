package com.cajunsystems.loop;

/**
 * A composable interceptor for {@link ActorBehavior}.
 *
 * <p>Middlewares implement cross-cutting concerns — logging, metrics, retry,
 * rate limiting, tracing — without modifying the handler logic.  Multiple
 * middlewares are composed by chaining {@link #wrap} calls, forming an
 * inside-out decorator stack:
 *
 * <pre>{@code
 * ActorBehavior<E, State, Msg> pipeline =
 *     baseBehavior
 *         .withMiddleware(new LoggingMiddleware<>(logger))
 *         .withMiddleware(new MetricsMiddleware<>(registry, "my-actor"))
 *         .withMiddleware(new RetryMiddleware<>(2));
 * }</pre>
 *
 * <p>Execution order (outermost first):
 * <pre>
 * RetryMiddleware → MetricsMiddleware → LoggingMiddleware → baseBehavior
 * </pre>
 *
 * @param <E>       error type of the effects
 * @param <State>   actor state type
 * @param <Message> actor message type
 */
@FunctionalInterface
public interface BehaviorMiddleware<E extends Throwable, State, Message> {

    /**
     * Wraps the given behavior, returning a new behavior that applies this
     * middleware's logic around every call to {@link ActorBehavior#step}.
     *
     * @param next the behavior to wrap (the next layer inward)
     * @return the wrapped behavior
     */
    ActorBehavior<E, State, Message> wrap(ActorBehavior<E, State, Message> next);
}
