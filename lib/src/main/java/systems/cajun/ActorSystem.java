package systems.cajun;


import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ActorSystem {

    private final ConcurrentHashMap<String, Actor<?>> actors;
    private final ScheduledExecutorService delayScheduler;

    /**
     * Creates a chain of actors and connects them in sequence.
     * 
     * @param <T> The type of actors in the chain
     * @param actorClass The class of actors to create
     * @param baseId The base ID for the actors (will be appended with "-1", "-2", etc.)
     * @param count The number of actors to create
     * @return The PID of the first actor in the chain
     */
    public <T extends Actor<?>> Pid createActorChain(Class<T> actorClass, String baseId, int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("Actor chain count must be positive");
        }

        // Create actors in reverse order (last to first)
        Pid[] actorPids = new Pid[count];
        for (int i = count; i >= 1; i--) {
            String actorId = baseId + "-" + i;
            actorPids[i-1] = register(actorClass, actorId);
        }

        // Connect the actors
        for (int i = 0; i < count - 1; i++) {
            Actor<?> actor = getActor(actorPids[i]);
            actor.withNext(actorPids[i+1]);
        }

        // Return the first actor in the chain
        return actorPids[0];
    }

    /**
     * Returns an unmodifiable view of all actors in the system.
     * 
     * @return A map of actor IDs to actors
     */
    public Map<String, Actor<?>> getActors() {
        return Map.copyOf(actors);
    }

    /**
     * Returns the actor with the specified ID, or null if no such actor exists.
     * 
     * @param pid The PID of the actor to get
     * @return The actor with the specified ID, or null if no such actor exists
     */
    public Actor<?> getActor(Pid pid) {
        return actors.get(pid.actorId());
    }

    public ActorSystem() {
        this.actors = new ConcurrentHashMap<>();
        this.delayScheduler = Executors.newSingleThreadScheduledExecutor();
    }

    /**
     * Registers a child actor with the specified parent.
     * 
     * @param <T> The type of the child actor
     * @param actorClass The class of the child actor
     * @param actorId The ID for the child actor
     * @param parent The parent actor
     * @return The PID of the created child actor
     */
    public <T extends Actor<?>> Pid registerChild(Class<T> actorClass, String actorId, Actor<?> parent) {
        try {
            T actor = actorClass.getDeclaredConstructor(ActorSystem.class, String.class).newInstance(this, actorId);
            actors.put(actorId, actor);
            parent.addChild(actor);
            actor.start();
            return new Pid(actorId, this);
        } catch (InvocationTargetException | InstantiationException | IllegalAccessException |
                 NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Registers a child actor with the specified parent and an auto-generated ID.
     * 
     * @param <T> The type of the child actor
     * @param actorClass The class of the child actor
     * @param parent The parent actor
     * @return The PID of the created child actor
     */
    public <T extends Actor<?>> Pid registerChild(Class<T> actorClass, Actor<?> parent) {
        try {
            T actor = actorClass.getDeclaredConstructor(ActorSystem.class).newInstance(this);
            String actorId = actor.getActorId();
            actors.put(actorId, actor);
            parent.addChild(actor);
            actor.start();
            return new Pid(actorId, this);
        } catch (InvocationTargetException | InstantiationException | IllegalAccessException |
                 NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
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

    public <Message> Pid register(Receiver<Message> receiver, String actorId) {
        Actor<Message> actor = new Actor<>(this) {
            private Receiver<Message> currentReceiver = receiver;

            @Override
            protected void receive(Message o) {
                currentReceiver = currentReceiver.accept(o);
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

    @SuppressWarnings("unchecked")
    <Message> void routeMessage(String actorId, Message message, Long delay, TimeUnit timeUnit) {
        Actor<Message> actor = (Actor<Message>) actors.get(actorId);
        if (actor != null) {
            delayScheduler.schedule(() -> {
                actor.tell(message);
            }, delay, timeUnit);
        } else {
            System.out.println(STR."Actor not found: \{actorId}");
        }
    }

}
