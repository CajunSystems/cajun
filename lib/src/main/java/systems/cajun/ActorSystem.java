package systems.cajun;

import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import systems.cajun.config.ThreadPoolFactory;

public class ActorSystem {

    private static final Logger logger = LoggerFactory.getLogger(ActorSystem.class);

    private final ConcurrentHashMap<String, Actor<?>> actors;
    private final ScheduledExecutorService delayScheduler;
    private final ExecutorService sharedExecutor;
    private final ConcurrentHashMap<String, ScheduledFuture<?>> pendingDelayedMessages;
    private final ThreadPoolFactory threadPoolConfig;
    
    /**
     * Creates a chain of actors and connects them in sequence.
     * 
     * @param <T> The type of actors in the chain
     * @param actorClass The class of actors to create
     * @param baseId The base ID for the actors (will be appended with "-1", "-2", etc.)
     * @param count The number of actors to create
     * @return The PID of the first actor in the chain
     * @throws IllegalArgumentException if the actor class does not extend ChainedActor
     */
    public <T extends Actor<?>> Pid createActorChain(Class<T> actorClass, String baseId, int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("Actor chain count must be positive");
        }
        
        // Verify that the actor class extends ChainedActor
        if (!ChainedActor.class.isAssignableFrom(actorClass)) {
            throw new IllegalArgumentException("Actor class must extend ChainedActor for chaining");
        }

        // Create actors in reverse order (last to first)
        Pid[] actorPids = new Pid[count];
        for (int i = count; i >= 1; i--) {
            String actorId = baseId + "-" + i;
            actorPids[i-1] = register(actorClass, actorId);
        }

        // Connect the actors
        for (int i = 0; i < count - 1; i++) {
            ChainedActor<?> actor = (ChainedActor<?>) getActor(actorPids[i]);
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

    /**
     * Creates a new actor system with default settings.
     */
    public ActorSystem() {
        this(new ThreadPoolFactory());
    }
    
    /**
     * Creates a new actor system with the specified settings.
     * 
     * @param useSharedExecutor Whether to use a shared executor for all actors
     */
    public ActorSystem(boolean useSharedExecutor) {
        this(new ThreadPoolFactory().setUseSharedExecutor(useSharedExecutor));
    }
    
    /**
     * Creates a new actor system with the specified thread pool configuration.
     * 
     * @param threadPoolConfig The thread pool configuration to use
     */
    public ActorSystem(ThreadPoolFactory threadPoolConfig) {
        this.actors = new ConcurrentHashMap<>();
        this.threadPoolConfig = threadPoolConfig;
        
        // Create the delay scheduler based on configuration
        this.delayScheduler = threadPoolConfig.createScheduledExecutorService("actor-system");
        this.pendingDelayedMessages = new ConcurrentHashMap<>();
        
        // Create a shared executor for all actors if enabled
        if (threadPoolConfig.isUseSharedExecutor()) {
            this.sharedExecutor = threadPoolConfig.createExecutorService("shared-actor");
        } else {
            this.sharedExecutor = null;
        }
        
        logger.debug("ActorSystem created with {} scheduler threads, thread pool type: {}", 
                threadPoolConfig.getSchedulerThreads(), threadPoolConfig.getExecutorType());
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

    /**
     * Gets the shared executor for this actor system.
     * 
     * @return The shared executor, or null if shared executors are disabled
     */
    public ExecutorService getSharedExecutor() {
        return sharedExecutor;
    }
    
    /**
     * Gets the thread pool factory for this actor system.
     * 
     * @return The thread pool factory
     */
    public ThreadPoolFactory getThreadPoolFactory() {
        return threadPoolConfig;
    }
    
    /**
     * Shuts down all actors in the system.
     * This method stops all actors and clears the actor registry.
     */
    public void shutdown() {
        // Create a copy of the actors map to avoid concurrent modification issues
        Map<String, Actor<?>> actorsCopy = new HashMap<>(actors);

        // Stop all actors
        for (Actor<?> actor : actorsCopy.values()) {
            if (actor.isRunning()) {
                actor.stop();
            }
        }

        // Clear the actors map
        actors.clear();
        
        // Cancel any pending delayed messages
        logger.debug("Cancelling {} pending delayed messages", pendingDelayedMessages.size());
        for (ScheduledFuture<?> future : pendingDelayedMessages.values()) {
            future.cancel(false);
        }
        pendingDelayedMessages.clear();
        
        // Shutdown the delay scheduler
        logger.debug("Shutting down delay scheduler");
        delayScheduler.shutdown();
        try {
            int timeoutSeconds = threadPoolConfig.getSchedulerShutdownTimeoutSeconds();
            if (!delayScheduler.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
                logger.warn("Delay scheduler did not terminate in time, forcing shutdown");
                delayScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            logger.warn("Interrupted while waiting for delay scheduler to terminate");
            delayScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        // Shutdown the shared executor if it exists
        if (sharedExecutor != null) {
            sharedExecutor.shutdown();
            try {
                int timeoutSeconds = threadPoolConfig.getActorShutdownTimeoutSeconds();
                if (!sharedExecutor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
                    sharedExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                sharedExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    @SuppressWarnings("unchecked")
    <Message> void routeMessage(String actorId, Message message) {
        Actor<Message> actor = (Actor<Message>) actors.get(actorId);
        if (actor != null) {
            actor.tell(message);
        } else {
            System.out.println("Actor not found: " + actorId);
        }
    }

    @SuppressWarnings("unchecked")
    <Message> void routeMessage(String actorId, Message message, Long delay, TimeUnit timeUnit) {
        Actor<Message> actor = (Actor<Message>) actors.get(actorId);
        if (actor != null) {
            String messageId = actorId + "-" + System.nanoTime();
            logger.debug("Scheduling delayed message to actor {}: {} with delay {} {}", 
                    actorId, message.getClass().getSimpleName(), delay, timeUnit);
            
            ScheduledFuture<?> future = delayScheduler.schedule(() -> {
                try {
                    logger.debug("Delivering delayed message to actor {}: {}", actorId, message.getClass().getSimpleName());
                    actor.tell(message);
                    pendingDelayedMessages.remove(messageId);
                } catch (Exception e) {
                    logger.error("Error delivering delayed message to actor {}", actorId, e);
                }
            }, delay, timeUnit);
            
            pendingDelayedMessages.put(messageId, future);
        } else {
            logger.warn("Actor not found for delayed message: {}", actorId);
        }
    }

}
