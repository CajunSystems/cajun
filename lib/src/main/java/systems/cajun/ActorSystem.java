package systems.cajun;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.ConcurrentHashMap;

public class ActorSystem {

    private final ConcurrentHashMap<String, Actor<?>> actors;


    public ActorSystem() {
        this.actors = new ConcurrentHashMap<>();
    }

    public <T extends Actor<?>> Pid register(Class<T> actorClass, String actorId) {
        try {
            T actor = actorClass.getDeclaredConstructor().newInstance();
            actors.put(actorId, actor);
            actor.start();
            return new Pid(actorId, this);
        } catch (InvocationTargetException | InstantiationException | IllegalAccessException |
                 NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    protected <Message> Actor<Message> getActor(String actorId) {
        return (Actor<Message>) actors.get(actorId);
    }

    <Message> void routeMessage(String actorId, Message message) {
        Actor<Message> actor = (Actor<Message>) actors.get(actorId);
        if (actor != null) {
            actor.tell(message);
        } else {
            System.out.println(STR."Actor not found: \{actorId}");
        }
    }


}
