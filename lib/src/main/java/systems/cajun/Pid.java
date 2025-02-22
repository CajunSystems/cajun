package systems.cajun;

public record Pid(String actorId, ActorSystem system) {

    <Message> void tell(Message message) {
        system.routeMessage(actorId, message);
    }
}
