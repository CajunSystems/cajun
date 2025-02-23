package systems.cajun;

import java.util.function.BiFunction;

public record FunctionalActor<State, Message>() {

    public Receiver<Message> receiveMessage(BiFunction<State, Message, State> action, State state) {
        return new Receiver<>() {
            @Override
            public Receiver<Message> receive(Message message1) {
                return receiveMessage(action, action.apply(state, message1));
            }
        };
    }
}

