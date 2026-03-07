package com.cajunsystems.loop.middleware;

import com.cajunsystems.loop.ActorBehavior;
import com.cajunsystems.loop.BehaviorMiddleware;
import com.cajunsystems.loop.LoopStep;
import com.cajunsystems.roux.Effect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.function.Predicate;

/**
 * A {@link BehaviorMiddleware} that transparently retries a failed step up to
 * {@code maxAttempts} times with optional exponential backoff.
 *
 * <p>Only errors matching the supplied {@code retryPredicate} are retried;
 * non-retryable errors propagate immediately.
 *
 * <pre>{@code
 * // Retry up to 3 times with 50 ms initial delay (doubles each time)
 * ActorBehavior<IOException, State, Msg> reliable =
 *     baseBehavior.withMiddleware(
 *         RetryMiddleware.withExponentialBackoff(3, Duration.ofMillis(50)));
 *
 * // Only retry transient errors
 * ActorBehavior<Exception, State, Msg> selective =
 *     baseBehavior.withMiddleware(
 *         RetryMiddleware.withPredicate(3, err -> err instanceof TransientException));
 * }</pre>
 *
 * @param <E>       error type
 * @param <State>   actor state type
 * @param <Message> message type
 */
public final class RetryMiddleware<E extends Throwable, State, Message>
        implements BehaviorMiddleware<E, State, Message> {

    private static final Logger logger = LoggerFactory.getLogger(RetryMiddleware.class);

    private final int maxAttempts;
    private final Duration initialDelay;
    private final Predicate<E> retryPredicate;

    /**
     * Retry every error up to {@code maxAttempts} times with no delay.
     */
    public RetryMiddleware(int maxAttempts) {
        this(maxAttempts, Duration.ZERO, ignored -> true);
    }

    /**
     * Retry every error up to {@code maxAttempts} times with exponential backoff.
     */
    public RetryMiddleware(int maxAttempts, Duration initialDelay) {
        this(maxAttempts, initialDelay, ignored -> true);
    }

    /**
     * Full constructor.
     *
     * @param maxAttempts    maximum number of attempts (first try + retries)
     * @param initialDelay   initial backoff delay; doubled on each subsequent retry
     * @param retryPredicate only retry when this predicate returns {@code true}
     */
    public RetryMiddleware(int maxAttempts, Duration initialDelay, Predicate<E> retryPredicate) {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be >= 1");
        this.maxAttempts = maxAttempts;
        this.initialDelay = initialDelay;
        this.retryPredicate = retryPredicate;
    }

    // -------------------------------------------------------------------------
    // Static factory conveniences
    // -------------------------------------------------------------------------

    public static <E extends Throwable, State, Message>
    RetryMiddleware<E, State, Message> withExponentialBackoff(int maxAttempts, Duration initial) {
        return new RetryMiddleware<>(maxAttempts, initial);
    }

    public static <E extends Throwable, State, Message>
    RetryMiddleware<E, State, Message> withPredicate(int maxAttempts, Predicate<E> predicate) {
        return new RetryMiddleware<>(maxAttempts, Duration.ZERO, predicate);
    }

    // -------------------------------------------------------------------------
    // BehaviorMiddleware
    // -------------------------------------------------------------------------

    @Override
    public ActorBehavior<E, State, Message> wrap(ActorBehavior<E, State, Message> next) {
        return (msg, state, ctx) ->
            retryStep(next, msg, state, ctx, 1, initialDelay.toMillis());
    }

    @SuppressWarnings("unchecked")
    private Effect<E, LoopStep<State>> retryStep(
            ActorBehavior<E, State, Message> behavior,
            Message msg,
            State state,
            com.cajunsystems.ActorContext ctx,
            int attempt,
            long delayMs) {

        return behavior.step(msg, state, ctx)
            .catchAll(err -> {
                if (attempt < maxAttempts && retryPredicate.test(err)) {
                    logger.warn("[{}] attempt {}/{} failed ({}), retrying in {} ms",
                        ctx.getActorId(), attempt, maxAttempts,
                        err.getClass().getSimpleName(), delayMs);

                    if (delayMs > 0) {
                        try {
                            Thread.sleep(delayMs);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return Effect.fail(err);
                        }
                    }
                    return retryStep(behavior, msg, state, ctx, attempt + 1, delayMs * 2);
                }

                logger.warn("[{}] giving up after {} attempt(s): {}",
                    ctx.getActorId(), attempt, err.getMessage());
                return Effect.fail(err);
            });
    }
}
