package com.cajunsystems.handler;

import com.cajunsystems.ActorContext;
import com.cajunsystems.roux.Effect;

import java.util.function.BiFunction;

/**
 * Adapter that converts a functional-style {@code BiFunction<State, Message, State>} into a
 * {@link StatefulHandler} whose {@link #receive} method wraps the return value in a
 * {@link Effect#succeed(Object)} Roux effect.
 *
 * <p>This preserves backward compatibility for code that builds handlers from plain
 * state-transition functions.  The error type is fixed to {@link RuntimeException} because a
 * plain {@code BiFunction} can only propagate unchecked exceptions; callers that need typed
 * errors should implement {@link StatefulHandler} directly.
 *
 * @param <State>   the type of the actor's state
 * @param <Message> the type of messages this handler processes
 */
public class StatefulFunctionalHandlerAdapter<State, Message>
        implements StatefulHandler<RuntimeException, State, Message> {

    private final BiFunction<State, Message, State> stateTransitionFunction;

    /**
     * Creates a new adapter with the specified state transition function.
     *
     * @param stateTransitionFunction the function that processes messages and produces new state
     */
    public StatefulFunctionalHandlerAdapter(BiFunction<State, Message, State> stateTransitionFunction) {
        this.stateTransitionFunction = stateTransitionFunction;
    }

    @Override
    public Effect<RuntimeException, State> receive(Message message, State state, ActorContext context) {
        return Effect.succeed(stateTransitionFunction.apply(state, message));
    }

    /**
     * Creates a new adapter from a state transition function.
     *
     * @param <S>                     the type of the actor's state
     * @param <M>                     the type of messages the handler processes
     * @param stateTransitionFunction the function that processes messages and produces new state
     * @return a new stateful handler adapter
     */
    public static <S, M> StatefulFunctionalHandlerAdapter<S, M> of(BiFunction<S, M, S> stateTransitionFunction) {
        return new StatefulFunctionalHandlerAdapter<>(stateTransitionFunction);
    }
}
