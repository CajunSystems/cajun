package systems.cajun;

import java.util.concurrent.TimeUnit;

public record Pid(String actorId, ActorSystem system) {

    public <Message> void tell(Message message) {
        system.routeMessage(actorId, message);
    }

    public <Message> void tell(Message message, long delay, TimeUnit timeUnit) {
        system.routeMessage(actorId, message, delay, timeUnit);
    }
}
