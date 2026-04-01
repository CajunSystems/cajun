package com.cajunsystems.handler;

import com.cajunsystems.ActorContext;
import com.cajunsystems.roux.Effect;

/**
 * A functional interface describing a stateful actor's message-handling behavior
 * in terms of a Roux {@link Effect}.
 *
 * <p>This is the lambda-friendly counterpart to {@link StatefulHandler}. Use it with
 * {@link com.cajunsystems.ActorSystem#fromEffect} to define actor behavior inline,
 * without the ceremony of a named handler class:
 *
 * <pre>{@code
 * Pid counter = system.fromEffect(
 *     (CounterMsg msg, Integer count, ActorContext ctx) -> switch (msg) {
 *         case Increment() -> Effect.succeed(count + 1);
 *         case Reset()     -> Effect.succeed(0);
 *         case GetCount(var replyTo) -> Effect.suspend(() -> {
 *             ctx.tell(replyTo, count);
 *             return count;
 *         });
 *     },
 *     0  // initial state
 * ).withId("counter").spawn();
 * }</pre>
 *
 * <p>For actors that need lifecycle hooks ({@code preStart}, {@code postStop},
 * {@code onError}), implement {@link StatefulHandler} directly instead.
 *
 * @param <E>       the error type (must extend {@link Throwable})
 * @param <State>   the type of the actor's state
 * @param <Message> the type of messages this behavior handles
 */
@FunctionalInterface
public interface EffectBehavior<E extends Throwable, State, Message> {

    /**
     * Processes a message and returns an effect describing the resulting state transition.
     *
     * @param message the message to process
     * @param state   the current actor state
     * @param context the actor context
     * @return an effect whose success value is the new state
     */
    Effect<E, State> receive(Message message, State state, ActorContext context);

    /**
     * Adapts this behavior to a {@link StatefulHandler}, enabling it to be used
     * with {@link com.cajunsystems.builder.StatefulActorBuilder} and all its options
     * (persistence, middleware, supervision, etc.).
     *
     * @return a {@code StatefulHandler} delegating to this behavior
     */
    default StatefulHandler<E, State, Message> asHandler() {
        return this::receive;
    }
}
