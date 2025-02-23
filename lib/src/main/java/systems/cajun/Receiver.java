package systems.cajun;

public interface Receiver<Message> {

    Receiver<Message> accept(Message message);
}
