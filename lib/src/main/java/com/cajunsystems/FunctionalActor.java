package com.cajunsystems;

import com.cajunsystems.handler.StatefulHandler;
import com.cajunsystems.roux.Effect;

import java.util.function.BiFunction;
import java.util.function.BiConsumer;


public record FunctionalActor<State, Message>() {

    /**
     * Creates a chain of functional actors that process messages in sequence.
     * Each actor in the chain forwards messages to the next actor after processing.
     * This method uses the new interface-based approach internally.
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
        if (count <= 0) {
            throw new IllegalArgumentException("Actor chain count must be positive");
        }
        if (initialStates.length != count || actions.length != count) {
            throw new IllegalArgumentException("Initial states and actions arrays must have the same length as count");
        }

        // Create actors in reverse order (last to first)
        Pid[] actorPids = new Pid[count];
        Pid nextPid = null;

        for (int i = count; i >= 1; i--) {
            final String actorId = baseId + "-" + i;
            final int index = i - 1;
            final Pid finalNextPid = nextPid;

            // Wrap the BiFunction as a StatefulHandler returning an Effect
            StatefulHandler<RuntimeException, S, M> handler = new StatefulHandler<RuntimeException, S, M>() {
                @Override
                public Effect<RuntimeException, S> receive(M message, S state, ActorContext context) {
                    S newState = actions[index].apply(state, message);
                    if (finalNextPid != null) {
                        finalNextPid.tell(message);
                    }
                    return Effect.succeed(newState);
                }
            };

            // Create the actor with the handler and initial state
            actorPids[index] = system.statefulActorOf(handler, initialStates[index])
                    .withId(actorId)
                    .spawn();

            // Update nextPid for the next iteration
            nextPid = actorPids[index];
        }

        return actorPids[0];
    }

    /**
     * Main method with error handling support. If an errorHandler is provided, it will be called on exception.
     * Otherwise, errors are logged to System.err and the state is not changed.
     *
     * Note: This method is maintained for backward compatibility. New code should use the
     * interface-based approach with ActorSystem.statefulActorOf() instead.
     */
    public Receiver<Message> receiveMessage(
            BiFunction<State, Message, State> action,
            State state,
            BiConsumer<State, Exception> errorHandler
    ) {
        return new Receiver<>() {
            @Override
            public Receiver<Message> accept(Message message1) {
                try {
                    State newState = action.apply(state, message1);
                    return receiveMessage(action, newState, errorHandler);
                } catch (Exception e) {
                    if (errorHandler != null) {
                        errorHandler.accept(state, e);
                    } else {
                        System.err.println("[FunctionalActor] Error processing message: " + e.getMessage());
                        e.printStackTrace();
                    }
                    // Continue with the same state after error
                    return receiveMessage(action, state, errorHandler);
                }
            }
        };
    }

    /**
     * Creates a StatefulHandler from a functional state transition function.
     * This is a bridge between the old functional approach and the new interface-based approach.
     *
     * @param action The state transition function
     * @param errorHandler Optional error handler
     * @return A StatefulHandler that uses the provided function
     */
    public StatefulHandler<RuntimeException, State, Message> toStatefulHandler(
            BiFunction<State, Message, State> action,
            BiConsumer<State, Exception> errorHandler
    ) {
        return new StatefulHandler<RuntimeException, State, Message>() {
            @Override
            public Effect<RuntimeException, State> receive(Message message, State state, ActorContext context) {
                try {
                    return Effect.succeed(action.apply(state, message));
                } catch (RuntimeException e) {
                    if (errorHandler != null) {
                        errorHandler.accept(state, e);
                    } else {
                        System.err.println("[FunctionalActor] Error processing message: " + e.getMessage());
                        e.printStackTrace();
                    }
                    return Effect.succeed(state); // Return unchanged state on error
                }
            }
        };
    }

    /**
     * Backward-compatible method (no error handler, just logs to System.err)
     */
    public Receiver<Message> receiveMessage(BiFunction<State, Message, State> action, State state) {
        return receiveMessage(action, state, null);
    }
}
