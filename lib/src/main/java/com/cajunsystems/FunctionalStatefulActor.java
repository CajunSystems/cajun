package com.cajunsystems;


import com.cajunsystems.metrics.ActorMetrics;
import com.cajunsystems.persistence.BatchedMessageJournal;
import com.cajunsystems.persistence.OperationAwareMessage;
import com.cajunsystems.persistence.SnapshotStore;
import com.cajunsystems.persistence.filesystem.PersistenceFactory;

import java.util.function.BiFunction;

/**
 * A functional approach to creating stateful actors.
 * This class provides a more concise way to create stateful actors using lambda expressions.
 *
 * @param <State> The type of the actor's state
 * @param <Message> The type of messages the actor can process
 */
public record FunctionalStatefulActor<State, Message extends OperationAwareMessage>() {

    /**
     * Creates a chain of functional stateful actors that process messages in sequence.
     * Each actor in the chain forwards messages to the next actor after processing.
     *
     * @param system The actor system
     * @param baseId The base ID for the actors
     * @param count The number of actors in the chain
     * @param initialStates Array of initial states for each actor
     * @param actions Array of action functions for each actor
     * @param messageJournal The batched message journal to use for persistence
     * @param snapshotStore The snapshot store to use for persistence
     * @return The PID of the first actor in the chain
     */
    public static <S, M extends OperationAwareMessage> Pid createChain(
            ActorSystem system,
            String baseId,
            int count,
            S[] initialStates,
            BiFunction<S, M, S>[] actions,
            BatchedMessageJournal<M> messageJournal,
            SnapshotStore<S> snapshotStore
    ) {
        if (count <= 0) {
            throw new IllegalArgumentException("Actor chain count must be positive");
        }
        if (initialStates.length != count || actions.length != count) {
            throw new IllegalArgumentException("Initial states and actions arrays must have the same length as count");
        }

        // Create actors in reverse order so each can forward to the next
        @SuppressWarnings("unchecked")
        StatefulActor<S, M>[] actors = new StatefulActor[count];
        Pid[] actorPids = new Pid[count];
        for (int i = count - 1; i >= 0; i--) {
            final int index = i; // Create a final copy for the lambda
            String actorId = baseId + "-" + i;
            
            // Create the actor
            final Pid nextPid = i < count - 1 ? actors[i + 1].self() : null;
            actors[i] = createStatefulActor(
                    system,
                    baseId + "-" + i,
                    initialStates[i],
                    (state, message) -> {
                        // Process the message
                        S newState = actions[index].apply(state, message);
                        
                        // Forward to the next actor if there is one
                        if (nextPid != null) {
                            // Use the actor's PID to send message through ActorSystem
                            nextPid.tell(message);
                        }
                        
                        return newState;
                    },
                    messageJournal,
                    snapshotStore
            );
            actorPids[i] = actors[i].self();
            system.getActors().put(actorId, actors[i]);
            actors[i].start();
        }

        return actorPids[0];
    }
    
    /**
     * Creates a chain of functional stateful actors with default persistence.
     *
     * @param system The actor system
     * @param baseId The base ID for the actors
     * @param count The number of actors in the chain
     * @param initialStates Array of initial states for each actor
     * @param actions Array of action functions for each actor
     * @return The PID of the first actor in the chain
     */
    public static <S, M extends OperationAwareMessage> Pid createChain(
            ActorSystem system,
            String baseId,
            int count,
            S[] initialStates,
            BiFunction<S, M, S>[] actions
    ) {
        return createChain(system, baseId, count, initialStates, actions, 
                          PersistenceFactory.createBatchedFileMessageJournal(),
                          PersistenceFactory.createFileSnapshotStore());
    }
    
    /**
     * Creates a stateful actor with the given parameters.
     *
     * @param system The actor system
     * @param actorId The actor ID
     * @param initialState The initial state
     * @param action The action function
     * @param messageJournal The message journal to use
     * @param snapshotStore The snapshot store to use
     * @return The created stateful actor
     */
    public static <S, M extends OperationAwareMessage> StatefulActor<S, M> createStatefulActor(
            ActorSystem system,
            String actorId,
            S initialState,
            BiFunction<S, M, S> action,
            BatchedMessageJournal<M> messageJournal,
            SnapshotStore<S> snapshotStore
    ) {
        return new StatefulActor<S, M>(system, actorId, initialState, messageJournal, snapshotStore) {
            @Override
            protected S processMessage(S state, M message) {
                return action.apply(state, message);
            }
            
            @Override
            public ActorMetrics getMetrics() {
                return super.getMetrics();
            }
        };
    }
    
    /**
     * Registers a stateful actor with the actor system.
     *
     * @param system The actor system
     * @param actorId The actor ID
     * @param initialState The initial state
     * @param action The action function
     * @param messageJournal The message journal to use
     * @param snapshotStore The snapshot store to use
     * @return The PID of the registered actor
     */
    public static <S, M extends OperationAwareMessage> Pid register(
            ActorSystem system,
            String actorId,
            S initialState,
            BiFunction<S, M, S> action,
            BatchedMessageJournal<M> messageJournal,
            SnapshotStore<S> snapshotStore
    ) {
        StatefulActor<S, M> actor = createStatefulActor(system, actorId, initialState, action, messageJournal, snapshotStore);
        actor.start();
        return actor.self();
    }
    
    /**
     * Registers a stateful actor with the actor system using default persistence.
     *
     * @param system The actor system
     * @param actorId The actor ID
     * @param initialState The initial state
     * @param action The action function
     * @return The PID of the registered actor
     */
    public static <S, M extends OperationAwareMessage> Pid register(
            ActorSystem system,
            String actorId,
            S initialState,
            BiFunction<S, M, S> action
    ) {
        return register(system, actorId, initialState, action, 
                       PersistenceFactory.createBatchedFileMessageJournal(),
                       PersistenceFactory.createFileSnapshotStore());
    }
}
