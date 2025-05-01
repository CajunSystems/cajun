package systems.cajun;

import java.util.function.BiFunction;

public record FunctionalActor<State, Message>() {

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


