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
            T actor = actorClass.getDeclaredConstructor(ActorSystem.class, String.class).newInstance(this, actorId);
            actors.put(actorId, actor);
            actor.start();
            return new Pid(actorId, this);
        } catch (InvocationTargetException | InstantiationException | IllegalAccessException |
                 NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    public <T extends Actor<?>, Message> Pid register(Receiver<Message> receiver, String actorId) {
        Actor<Message> actor = new Actor<Message>(this) {
            @Override
            protected void receive(Message o) {
                receiver.receive(o);
            }
        };
        actors.put(actorId, actor);
        actor.start();
        return new Pid(actorId, this);
    }

    public <T extends Actor<?>> Pid register(Class<T> actorClass) {
        try {
            T actor = actorClass.getDeclaredConstructor(ActorSystem.class).newInstance(this);
            actors.put(actor.getActorId(), actor);
            actor.start();
            return new Pid(actor.getActorId(), this);
        } catch (InvocationTargetException | InstantiationException | IllegalAccessException |
                 NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    public void shutdown(String actorId) {
        var actor = actors.get(actorId);
        if (actor != null) {
            if (actor.isRunning()) {
                actor.stop();
            }
            this.actors.remove(actorId);
        }
    }

    @SuppressWarnings("unchecked")
    <Message> void routeMessage(String actorId, Message message) {
        Actor<Message> actor = (Actor<Message>) actors.get(actorId);
        if (actor != null) {
            actor.tell(message);
        } else {
            System.out.println(STR."Actor not found: \{actorId}");
        }
    }


}
