package systems.cajun;

import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import systems.cajun.config.ThreadPoolFactory;

/**
 * ActorSystem is the main entry point for creating and managing actors.
 */
public class ActorSystem {

    private static final Logger logger = LoggerFactory.getLogger(ActorSystem.class);

    /**
     * Functional interface for receiving and processing messages.
     * 
     * @param <Message> The type of messages this receiver will process
     */
    @FunctionalInterface
    public interface Receiver<Message> {
        void receive(Message message);
    }

    /**
     * Adapter method to convert a systems.cajun.Receiver to an ActorSystem.Receiver
     * 
     * @param <Message> The type of messages
     * @param cajunReceiver The systems.cajun.Receiver to adapt
     * @return An ActorSystem.Receiver that calls the cajunReceiver.accept method
     */
    public static <Message> Receiver<Message> adaptReceiver(systems.cajun.Receiver<Message> cajunReceiver) {
        return message -> cajunReceiver.accept(message);
    }

    /**
     * Message structure for the ask pattern that carries the original request and the reply address.
     */
    public record AskPayload<RequestMessage>(RequestMessage message, String replyTo) {}

    /**
     * Functional actor implementation that delegates message handling to a function.
     */
    private static class FunctionalActor<Message> extends Actor<Message> {
        private final Receiver<Message> receiver;
        
        public FunctionalActor(ActorSystem system, String id, Receiver<Message> receiver) {
            super(system, id);
            this.receiver = receiver;
        }

        @Override
        protected void receive(Message message) {
            receiver.receive(message);
        }
    }

    private final ConcurrentHashMap<String, Actor<?>> actors;
    private final ScheduledExecutorService delayScheduler;
    private final ExecutorService sharedExecutor;
    private final ConcurrentHashMap<String, ScheduledFuture<?>> pendingDelayedMessages;
    private final ThreadPoolFactory threadPoolConfig;

    /**
     * Creates a new ActorSystem with the default configuration.
     */
    public ActorSystem() {
        this(new ThreadPoolFactory());
    }

    /**
     * Creates a new ActorSystem with the specified shared executor configuration.
     * 
     * @param useSharedExecutor Whether to use a shared executor for all actors
     */
    public ActorSystem(boolean useSharedExecutor) {
        this(new ThreadPoolFactory().setUseSharedExecutor(useSharedExecutor));
    }

    /**
     * Creates a new ActorSystem with the specified thread pool configuration.
     * 
     * @param threadPoolConfig The thread pool configuration
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
        if (count < 1) {
            throw new IllegalArgumentException("Chain count must be at least 1");
        }
        
        if (!ChainedActor.class.isAssignableFrom(actorClass)) {
            throw new IllegalArgumentException("Chain actors must extend ChainedActor");
        }
        
        // Create the actors in the chain
        List<Pid> pids = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            String actorId = baseId + "-" + i;
            Pid pid = register(actorClass, actorId);
            pids.add(pid);
        }
        
        // Connect the actors in the chain
        for (int i = 0; i < count - 1; i++) {
            ChainedActor<?> current = (ChainedActor<?>) getActor(pids.get(i));
            current.withNext(pids.get(i + 1));
        }
        
        return pids.get(0); // Return the first actor in the chain
    }

    /**
     * Returns an unmodifiable view of all actors in the system.
     * 
     * @return A map of actor IDs to actors
     */
    public Map<String, Actor<?>> getActors() {
        return Collections.unmodifiableMap(actors);
    }

    /**
     * Returns the actor with the specified ID, or Optional.empty() if no such actor exists.
     * 
     * @param pid The PID of the actor to get
     * @return An Optional containing the actor, or Optional.empty() if not found
     */
    public Optional<Actor<?>> getActorOptional(Pid pid) {
        return Optional.ofNullable(actors.get(pid.actorId()));
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
            T actor = actorClass.getDeclaredConstructor(ActorSystem.class, String.class)
                    .newInstance(this, actorId);
            actors.put(actorId, actor);
            parent.addChild(actor);
            actor.start();
            return actor.self();
        } catch (InstantiationException | IllegalAccessException | 
                InvocationTargetException | NoSuchMethodException e) {
            logger.error("Error registering child actor {}", actorId, e);
            throw new RuntimeException("Error registering child actor: " + e.getMessage(), e);
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
        return registerChild(actorClass, UUID.randomUUID().toString(), parent);
    }

    /**
     * Registers an actor with the specified class and ID.
     * 
     * @param <T> The type of the actor
     * @param actorClass The class of the actor
     * @param actorId The ID for the actor
     * @return The PID of the created actor
     */
    public <T extends Actor<?>> Pid register(Class<T> actorClass, String actorId) {
        try {
            T actor = actorClass.getDeclaredConstructor(ActorSystem.class, String.class)
                    .newInstance(this, actorId);
            actors.put(actorId, actor);
            actor.start();
            return actor.self();
        } catch (InstantiationException | IllegalAccessException | 
                InvocationTargetException | NoSuchMethodException e) {
            logger.error("Error registering actor {}", actorId, e);
            throw new RuntimeException("Error registering actor: " + e.getMessage(), e);
        }
    }

    /**
     * Registers an actor with the specified behavior function and ID.
     * This is a simpler API for creating actors with functional behavior.
     * 
     * @param <Message> The type of messages the actor will handle
     * @param receiver The behavior function for the actor
     * @param actorId The ID for the actor
     * @return The PID of the created actor
     */
    public <Message> Pid register(Receiver<Message> receiver, String actorId) {
        Actor<Message> actor = new FunctionalActor<>(this, actorId, receiver);
        actors.put(actorId, actor);
        actor.start();
        return actor.self();
    }

    /**
     * Registers an actor with the specified systems.cajun.Receiver and ID.
     * This is an adapter method to support the systems.cajun.Receiver interface.
     * 
     * @param <Message> The type of messages the actor will handle
     * @param cajunReceiver The systems.cajun.Receiver for the actor
     * @param actorId The ID for the actor
     * @return The PID of the created actor
     */
    public <Message> Pid register(systems.cajun.Receiver<Message> cajunReceiver, String actorId) {
        return register(adaptReceiver(cajunReceiver), actorId);
    }

    /**
     * Registers an actor with the specified class and an auto-generated ID.
     * 
     * @param <T> The type of the actor
     * @param actorClass The class of the actor
     * @return The PID of the created actor
     */
    public <T extends Actor<?>> Pid register(Class<T> actorClass) {
        return register(actorClass, UUID.randomUUID().toString());
    }

    /**
     * Shuts down and removes the actor with the specified ID.
     * 
     * @param actorId The ID of the actor to shut down
     */
    public void shutdown(String actorId) {
        Actor<?> actor = actors.remove(actorId);
        if (actor != null && actor.isRunning()) {
            actor.stop();
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
     * This method stops all actors, waits for any persistence operations to complete,
     * and then clears the actor registry and shuts down the thread pools.
     */
    public void shutdown() {
        logger.info("Shutting down all actors in the system");
        
        // Get a copy of all actor IDs to avoid ConcurrentModificationException
        List<String> actorIds = new ArrayList<>(actors.keySet());
        
        // Create a list to store futures for parallel shutdown
        List<CompletableFuture<Void>> shutdownFutures = new ArrayList<>();
        
        // Shutdown each actor in parallel
        for (String actorId : actorIds) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                Actor<?> actor = actors.get(actorId);
                if (actor != null && actor.isRunning()) {
                    try {
                        actor.stop();
                        logger.debug("Actor {} shut down successfully", actorId);
                    } catch (Exception e) {
                        logger.warn("Error shutting down actor {}", actorId, e);
                    }
                }
            });
            shutdownFutures.add(future);
        }
        
        // Wait for all shutdowns to complete
        CompletableFuture<Void> allShutdowns = CompletableFuture.allOf(
                shutdownFutures.toArray(new CompletableFuture[0]));
        
        try {
            // Wait for all actors to shut down
            allShutdowns.join();
            
            // Clear the actor registry
            actors.clear();
            
            // Cancel any scheduled messages
            for (ScheduledFuture<?> future : pendingDelayedMessages.values()) {
                future.cancel(true);
            }
            pendingDelayedMessages.clear();
            
            // Shutdown the delay scheduler
            safeShutdownScheduler(delayScheduler);

            // Shutdown the shared executor if it exists
            safeShutdownScheduler(sharedExecutor);

            logger.info("Actor system shut down successfully");
        } catch (Exception e) {
            logger.error("Error during actor system shutdown", e);
            throw new RuntimeException("Error during actor system shutdown: " + e.getMessage(), e);
        }
    }

    private void safeShutdownScheduler(ExecutorService scheduler) {
        if (delayScheduler != null && !delayScheduler.isShutdown()) {
            delayScheduler.shutdown();
            try {
                if (!delayScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    delayScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                delayScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Routes a message to the actor with the specified ID.
     * 
     * @param <Message> The type of the message
     * @param actorId The ID of the actor to route the message to
     * @param message The message to route
     */
    <Message> void routeMessage(String actorId, Message message) {
        Actor<?> actor = actors.get(actorId);
        if (actor != null) {
            try {
                @SuppressWarnings("unchecked")
                Actor<Message> typedActor = (Actor<Message>) actor;
                typedActor.tell(message);
            } catch (ClassCastException e) {
                logger.warn("Failed to route message to actor {}: Message type mismatch", actorId, e);
            }
        } else {
            logger.warn("Failed to route message to actor {}: Actor not found", actorId);
        }
    }

    /**
     * Routes a message to the actor with the specified ID after a delay.
     * 
     * @param <Message> The type of the message
     * @param actorId The ID of the actor to route the message to
     * @param message The message to route
     * @param delay The delay amount
     * @param timeUnit The time unit for the delay
     */
    <Message> void routeMessage(String actorId, Message message, Long delay, TimeUnit timeUnit) {
        String taskId = UUID.randomUUID().toString();
        ScheduledFuture<?> future = delayScheduler.schedule(() -> {
            pendingDelayedMessages.remove(taskId);
            routeMessage(actorId, message);
        }, delay, timeUnit);
        pendingDelayedMessages.put(taskId, future);
    }

    /**
     * Sends a message to the target actor and returns a CompletableFuture that will be completed with the reply.
     * 
     * @param target The Pid of the target actor.
     * @param message The message to send.
     * @param timeout The maximum time to wait for a reply.
     * @param <RequestMessage> The type of the request message.
     * @param <ResponseMessage> The type of the expected response message.
     * @return A CompletableFuture that will be completed with the response or an exception if a timeout occurs.
     */
    @SuppressWarnings("unchecked")
    public <RequestMessage, ResponseMessage> CompletableFuture<ResponseMessage> ask(
            Pid target, RequestMessage message, Duration timeout) {
        
        CompletableFuture<ResponseMessage> result = new CompletableFuture<>();
        AtomicBoolean completed = new AtomicBoolean(false);
        String replyActorId = "reply-" + UUID.randomUUID().toString();
        
        // Schedule a timeout
        ScheduledFuture<?> timeoutFuture = delayScheduler.schedule(() -> {
            if (completed.compareAndSet(false, true)) {
                result.completeExceptionally(
                    new TimeoutException("Timeout waiting for response from " + target.actorId()));
                shutdown(replyActorId);
            }
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);

        // Create a temporary actor to receive the response
        Actor<Object> responseActor = new Actor<Object>(this, replyActorId) {
            @Override
            protected void receive(Object response) {
                if (completed.compareAndSet(false, true)) {
                    try {
                        result.complete((ResponseMessage) response);
                    } catch (ClassCastException e) {
                        result.completeExceptionally(
                            new IllegalArgumentException("Received response of unexpected type: " + 
                                response.getClass().getName(), e));
                    }
                    
                    // Cancel the timeout
                    timeoutFuture.cancel(false);
                    
                    // Stop this temporary actor
                    stop();
                }
            }

            @Override
            protected void postStop() {
                // Cancel timeout if not already cancelled
                timeoutFuture.cancel(false);
                
                // If the future is not completed yet, complete it exceptionally
                if (!result.isDone() && completed.compareAndSet(false, true)) {
                    result.completeExceptionally(new IllegalStateException("Actor stopped before receiving response"));
                }
                
                // Remove this temporary actor from the system
                actors.remove(replyActorId);
            }
        };

        // Register the response actor
        actors.put(replyActorId, responseActor);
        responseActor.start();

        try {
            // Send the message with replyTo field
            Actor<?> targetActor = getActor(target);
            if (targetActor != null) {
                // Create the ask payload with the message and reply actor ID
                AskPayload<RequestMessage> askMessage = new AskPayload<>(message, replyActorId);
                
                // Use the more general routeMessage method which handles type casting properly
                routeMessage(target.actorId(), askMessage);
            } else {
                // Target actor not found
                result.completeExceptionally(
                    new IllegalArgumentException("Target actor not found: " + target.actorId()));
                responseActor.stop();
            }
        } catch (Exception e) {
            result.completeExceptionally(e);
            responseActor.stop();
        }

        return result;
    }
}
