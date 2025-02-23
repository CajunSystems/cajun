package systems.cajun;

public interface Receiver<Message> {

    Receiver<Message> receive(Message message);
}
