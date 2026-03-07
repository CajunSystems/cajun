package com.cajunsystems.loop;

import com.cajunsystems.ActorContext;
import com.cajunsystems.roux.Effect;

/**
 * Describes one iteration of an actor's message-processing loop as a Roux
 * {@link Effect}.
 *
 * <p>Whereas {@link com.cajunsystems.handler.StatefulHandler#receive} returns an
 * {@code Effect<E, State>} that only describes the state transition, an
 * {@code ActorBehavior} returns {@code Effect<E, LoopStep<State>>} — giving the
 * handler control over the actor's lifecycle ({@link LoopStep#continue_},
 * {@link LoopStep#stop}, {@link LoopStep#restart}) as well as the next state.
 *
 * <p>Behaviours are composable via {@link BehaviorMiddleware}:
 * <pre>{@code
 * ActorBehavior<E, State, Msg> observed =
 *     new LoggingMiddleware<E, State, Msg>().wrap(baseBehavior);
 * }</pre>
 *
 * @param <E>       the error type declared by the handler (must extend {@link Throwable})
 * @param <State>   the actor's state type
 * @param <Message> the message type
 */
@FunctionalInterface
public interface ActorBehavior<E extends Throwable, State, Message> {

    /**
     * Processes one message and returns an effect whose success value is a
     * {@link LoopStep} describing what the actor should do next.
     *
     * @param message the message to process
     * @param state   the current state of the actor
     * @param context actor context (send messages, access children, etc.)
     * @return an effect describing the next loop step and the new state
     */
    Effect<E, LoopStep<State>> step(Message message, State state, ActorContext context);

    /**
     * Wraps this behavior with a {@link BehaviorMiddleware}, returning a new
     * behavior that applies the middleware on every step.
     *
     * @param middleware the middleware to apply
     * @return the wrapped behavior
     */
    default ActorBehavior<E, State, Message> withMiddleware(
            BehaviorMiddleware<E, State, Message> middleware) {
        return middleware.wrap(this);
    }
}
