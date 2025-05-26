package systems.cajun;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Implementation of the ActorContext interface that delegates to an underlying Actor instance.
 */
public class ActorContextImpl implements ActorContext {
    
    private final Actor<?> actor;
    
    /**
     * Creates a new ActorContextImpl for the specified actor.
     *
     * @param actor The actor to create a context for
     */
    public ActorContextImpl(Actor<?> actor) {
        this.actor = actor;
    }
    
    @Override
    public Pid self() {
        return actor.self();
    }
    
    @Override
    public String getActorId() {
        return actor.getActorId();
    }
    
    @Override
    public <T> void tell(Pid target, T message) {
        ActorSystem system = actor.getSystem();
        system.tell(target, message);
    }
    
    @Override
    public <T> void tellSelf(T message, long delay, TimeUnit timeUnit) {
        ActorSystem system = actor.getSystem();
        system.tell(actor.self(), message, delay, timeUnit);
    }
    
    @Override
    public <T> void tellSelf(T message) {
        ActorSystem system = actor.getSystem();
        system.tell(actor.self(), message);
    }
    
    @Override
    public <T> Pid createChild(Class<?> handlerClass, String childId) {
        ActorSystem system = actor.getSystem();
        if (systems.cajun.handler.Handler.class.isAssignableFrom(handlerClass)) {
            @SuppressWarnings("unchecked")
            Class<? extends systems.cajun.handler.Handler<Object>> handlerType = 
                (Class<? extends systems.cajun.handler.Handler<Object>>) handlerClass;
            return system.actorOf(handlerType)
                    .withParent(actor)
                    .withId(childId)
                    .spawn();
        } else {
            // Fall back to traditional actor creation
            return system.register((Class<? extends Actor<?>>) handlerClass, childId);
        }
    }
    
    @Override
    public <T> Pid createChild(Class<?> handlerClass) {
        ActorSystem system = actor.getSystem();
        if (systems.cajun.handler.Handler.class.isAssignableFrom(handlerClass)) {
            @SuppressWarnings("unchecked")
            Class<? extends systems.cajun.handler.Handler<Object>> handlerType = 
                (Class<? extends systems.cajun.handler.Handler<Object>>) handlerClass;
            return system.actorOf(handlerType)
                    .withParent(actor)
                    .spawn();
        } else {
            // Fall back to traditional actor creation
            return system.register((Class<? extends Actor<?>>) handlerClass);
        }
    }
    
    @Override
    public Pid getParent() {
        Actor<?> parent = actor.getParent();
        return parent != null ? parent.self() : null;
    }
    
    @Override
    public Map<String, Pid> getChildren() {
        Map<String, Pid> result = new HashMap<>();
        for (Map.Entry<String, Actor<?>> entry : actor.getChildren().entrySet()) {
            result.put(entry.getKey(), entry.getValue().self());
        }
        return result;
    }
    
    @Override
    public ActorSystem getSystem() {
        return actor.getSystem();
    }
    
    @Override
    public void stop() {
        actor.stop();
    }
}
