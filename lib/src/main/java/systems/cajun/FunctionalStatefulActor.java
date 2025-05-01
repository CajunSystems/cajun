package systems.cajun;

import systems.cajun.persistence.StateStore;
import systems.cajun.persistence.StateStoreFactory;

import java.util.function.BiFunction;

/**
 * A functional approach to creating stateful actors.
 * This class provides a more concise way to create stateful actors using lambda expressions.
 *
 * @param <State> The type of the actor's state
 * @param <Message> The type of messages the actor can process
 */
public record FunctionalStatefulActor<State, Message>() {

    /**
     * Creates a chain of functional stateful actors that process messages in sequence.
     * Each actor in the chain forwards messages to the next actor after processing.
     *
     * @param system The actor system
     * @param baseId The base ID for the actors
     * @param count The number of actors in the chain
     * @param initialStates Array of initial states for each actor
     * @param actions Array of action functions for each actor
     * @param stateStore The state store to use for persistence
     * @return The PID of the first actor in the chain
     */
    public static <S, M> Pid createChain(
            ActorSystem system,
            String baseId,
            int count,
            S[] initialStates,
            BiFunction<S, M, S>[] actions,
            StateStore<String, S> stateStore
    ) {
        if (count <= 0) {
            throw new IllegalArgumentException("Actor chain count must be positive");
        }
        if (initialStates.length != count || actions.length != count) {
            throw new IllegalArgumentException("Initial states and actions arrays must have the same length as count");
        }

        // Create actors in reverse order (last to first)
        Pid[] actorPids = new Pid[count];
        for (int i = count; i >= 1; i--) {
            String actorId = baseId + "-" + i;
            int index = i - 1;
            
            // Create a stateful actor for each position in the chain
            StatefulActor<S, M> actor = createStatefulActor(
                    system, actorId, initialStates[index], actions[index], stateStore);
            
            actorPids[index] = actor.self();
            system.getActors().put(actorId, actor);
            actor.start();
        }

        // Connect the actors by setting up message forwarding
        for (int i = 0; i < count - 1; i++) {
            final int nextIndex = i + 1;
            final Pid nextPid = actorPids[nextIndex];

            // Get the current actor and set up forwarding
            Actor<?> actor = system.getActor(actorPids[i]);
            if (actor != null) {
                actor.withNext(nextPid);
            }
        }

        return actorPids[0];
    }
    
    /**
     * Creates a chain of functional stateful actors with an in-memory state store.
     *
     * @param system The actor system
     * @param baseId The base ID for the actors
     * @param count The number of actors in the chain
     * @param initialStates Array of initial states for each actor
     * @param actions Array of action functions for each actor
     * @return The PID of the first actor in the chain
     */
    public static <S, M> Pid createChain(
            ActorSystem system,
            String baseId,
            int count,
            S[] initialStates,
            BiFunction<S, M, S>[] actions
    ) {
        return createChain(system, baseId, count, initialStates, actions, StateStoreFactory.createInMemoryStore());
    }
    
    /**
     * Creates a stateful actor with the given parameters.
     *
     * @param system The actor system
     * @param actorId The actor ID
     * @param initialState The initial state
     * @param action The action function
     * @param stateStore The state store to use
     * @return The created stateful actor
     */
    public static <S, M> StatefulActor<S, M> createStatefulActor(
            ActorSystem system,
            String actorId,
            S initialState,
            BiFunction<S, M, S> action,
            StateStore<String, S> stateStore
    ) {
        return new StatefulActor<>(system, actorId, initialState, stateStore) {
            @Override
            protected S processMessage(S state, M message) {
                return action.apply(state, message);
            }
        };
    }
    
    /**
     * Creates a stateful actor with an in-memory state store.
     *
     * @param system The actor system
     * @param actorId The actor ID
     * @param initialState The initial state
     * @param action The action function
     * @return The created stateful actor
     */
    public static <S, M> StatefulActor<S, M> createStatefulActor(
            ActorSystem system,
            String actorId,
            S initialState,
            BiFunction<S, M, S> action
    ) {
        return createStatefulActor(system, actorId, initialState, action, StateStoreFactory.createInMemoryStore());
    }
    
    /**
     * Registers a stateful actor with the actor system.
     *
     * @param system The actor system
     * @param actorId The actor ID
     * @param initialState The initial state
     * @param action The action function
     * @param stateStore The state store to use
     * @return The PID of the registered actor
     */
    public static <S, M> Pid register(
            ActorSystem system,
            String actorId,
            S initialState,
            BiFunction<S, M, S> action,
            StateStore<String, S> stateStore
    ) {
        StatefulActor<S, M> actor = createStatefulActor(system, actorId, initialState, action, stateStore);
        system.getActors().put(actorId, actor);
        actor.start();
        return actor.self();
    }
    
    /**
     * Registers a stateful actor with the actor system using an in-memory state store.
     *
     * @param system The actor system
     * @param actorId The actor ID
     * @param initialState The initial state
     * @param action The action function
     * @return The PID of the registered actor
     */
    public static <S, M> Pid register(
            ActorSystem system,
            String actorId,
            S initialState,
            BiFunction<S, M, S> action
    ) {
        return register(system, actorId, initialState, action, StateStoreFactory.createInMemoryStore());
    }
}
