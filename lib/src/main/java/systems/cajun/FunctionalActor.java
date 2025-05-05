package systems.cajun;

import java.util.function.BiFunction;

public record FunctionalActor<State, Message>() {

    /**
     * Creates a chain of functional actors that process messages in sequence.
     * Each actor in the chain forwards messages to the next actor after processing.
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
        for (int i = count; i >= 1; i--) {
            String actorId = baseId + "-" + i;
            int index = i - 1;
            FunctionalActor<S, M> functionalActor = new FunctionalActor<>();
            Receiver<M> receiver = functionalActor.receiveMessage(actions[index], initialStates[index]);
            actorPids[index] = system.register(receiver, actorId);
        }

        // Connect the actors by setting up message forwarding
        for (int i = 0; i < count - 1; i++) {
            final int nextIndex = i + 1;
            final Pid nextPid = actorPids[nextIndex];

            // Get the current actor and wrap its receiver to forward messages
            Actor<?> actor = system.getActor(actorPids[i]);
            if (actor != null && actor instanceof ChainedActor) {
                ((ChainedActor<?>) actor).withNext(nextPid);
            }
        }

        return actorPids[0];
    }

    /**
     * Main method with error handling support. If an errorHandler is provided, it will be called on exception.
     * Otherwise, errors are logged to System.err and the state is not changed.
     */
    public Receiver<Message> receiveMessage(
            BiFunction<State, Message, State> action,
            State state,
            java.util.function.BiConsumer<State, Exception> errorHandler
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
     * Backward-compatible method (no error handler, just logs to System.err)
     */
    public Receiver<Message> receiveMessage(BiFunction<State, Message, State> action, State state) {
        return receiveMessage(action, state, null);
    }
}
