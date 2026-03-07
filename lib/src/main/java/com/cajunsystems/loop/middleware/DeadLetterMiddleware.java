package com.cajunsystems.loop.middleware;

import com.cajunsystems.ActorContext;
import com.cajunsystems.Pid;
import com.cajunsystems.loop.ActorBehavior;
import com.cajunsystems.loop.BehaviorMiddleware;
import com.cajunsystems.roux.Effect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.function.Consumer;

/**
 * A {@link BehaviorMiddleware} that captures messages whose processing failed
 * and forwards them to a dead-letter sink.
 *
 * <p>The sink can be any {@code Consumer<DeadLetter<Message>>} — a Pid, an
 * in-memory queue, an external message broker, or simply a logger.
 *
 * <pre>{@code
 * // Log all dead letters
 * ActorBehavior<E, State, Msg> safe =
 *     baseBehavior.withMiddleware(
 *         new DeadLetterMiddleware<>(letter ->
 *             log.error("Dead letter: {}", letter)));
 *
 * // Forward to a dead-letter actor
 * Pid dlq = system.actorOf(DeadLetterHandler.class).spawn();
 * ActorBehavior<E, State, Msg> safe =
 *     baseBehavior.withMiddleware(
 *         DeadLetterMiddleware.toActor(dlq));
 * }</pre>
 *
 * @param <E>       error type
 * @param <State>   actor state type
 * @param <Message> message type
 */
public final class DeadLetterMiddleware<E extends Throwable, State, Message>
        implements BehaviorMiddleware<E, State, Message> {

    private static final Logger logger = LoggerFactory.getLogger(DeadLetterMiddleware.class);

    /**
     * A message that could not be processed successfully.
     *
     * @param actorId   ID of the actor that failed to process the message
     * @param message   the original message
     * @param error     the error that caused the failure
     * @param timestamp when the failure occurred
     */
    public record DeadLetter<Message>(
            String actorId,
            Message message,
            Throwable error,
            Instant timestamp) {}

    private final Consumer<DeadLetter<Message>> sink;

    /**
     * Creates a middleware that routes failed messages to {@code sink}.
     */
    public DeadLetterMiddleware(Consumer<DeadLetter<Message>> sink) {
        this.sink = sink;
    }

    /**
     * Convenience factory: forward dead letters to a Pid by calling
     * {@link Pid#tell(Object)}.
     */
    public static <E extends Throwable, State, Message>
    DeadLetterMiddleware<E, State, Message> toActor(Pid pid) {
        return new DeadLetterMiddleware<>(letter -> pid.tell(letter));
    }

    /**
     * Convenience factory: log dead letters at ERROR level.
     */
    public static <E extends Throwable, State, Message>
    DeadLetterMiddleware<E, State, Message> toLogger() {
        return new DeadLetterMiddleware<>(letter ->
            logger.error("Dead letter in actor [{}]: message={} error={}",
                letter.actorId(), letter.message(),
                letter.error().getMessage(), letter.error()));
    }

    @Override
    public ActorBehavior<E, State, Message> wrap(ActorBehavior<E, State, Message> next) {
        return (msg, state, ctx) ->
            next.step(msg, state, ctx)
                .tapError(err -> {
                    try {
                        sink.accept(new DeadLetter<>(ctx.getActorId(), msg, err, Instant.now()));
                    } catch (Exception sinkError) {
                        logger.error("Dead-letter sink threw an exception for actor [{}]",
                            ctx.getActorId(), sinkError);
                    }
                });
    }
}
