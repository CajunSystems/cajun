package systems.cajun.handler;

import systems.cajun.ActorContext;
import java.util.function.BiFunction;

/**
 * Adapter class that converts functional-style stateful message handling to the StatefulHandler interface.
 * This provides backward compatibility with the functional approach while using the new interface-based system.
 *
 * @param <State> The type of the actor's state
 * @param <Message> The type of messages this handler processes
 */
public class StatefulFunctionalHandlerAdapter<State, Message> implements StatefulHandler<State, Message> {
    
    private final BiFunction<State, Message, State> stateTransitionFunction;
    
    /**
     * Creates a new adapter with the specified state transition function.
     *
     * @param stateTransitionFunction The function that processes messages and updates state
     */
    public StatefulFunctionalHandlerAdapter(BiFunction<State, Message, State> stateTransitionFunction) {
        this.stateTransitionFunction = stateTransitionFunction;
    }
    
    @Override
    public State receive(Message message, State state, ActorContext context) {
        return stateTransitionFunction.apply(state, message);
    }
    
    /**
     * Creates a new stateful handler adapter from a state transition function.
     *
     * @param <S> The type of the actor's state
     * @param <M> The type of messages the handler processes
     * @param stateTransitionFunction The function that processes messages and updates state
     * @return A new stateful handler adapter
     */
    public static <S, M> StatefulFunctionalHandlerAdapter<S, M> of(BiFunction<S, M, S> stateTransitionFunction) {
        return new StatefulFunctionalHandlerAdapter<>(stateTransitionFunction);
    }
}
