package systems.cajun;

import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import systems.cajun.config.ThreadPoolFactory;

public class ActorSystem {

    private static final Logger logger = LoggerFactory.getLogger(ActorSystem.class);

    /**
     * Represents a message wrapper for the ask pattern, including the original message
     * and the Pid to which the reply should be sent.
     * @param <RequestMessage> The type of the original message.
     * @param message The original message.
     * @param replyTo The Pid of the actor expecting the reply.
     */
    public record AskPayload<RequestMessage>(RequestMessage message, Pid replyTo) {}

    /**
     * Internal message to signal a timeout for an ask operation.
     */
    private static final class AskTimeoutMessage {}

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
     * This method stops all actors, waits for any persistence operations to complete,
     * and then clears the actor registry and shuts down the thread pools.
     */
    public void shutdown() {
        // Create a copy of the actors map to avoid concurrent modification issues
        Map<String, Actor<?>> actorsCopy = new HashMap<>(actors);
        
        // First, collect all StatefulActors to track their persistence shutdown
        List<CompletableFuture<Void>> persistenceShutdownFutures = new ArrayList<>();
        for (Actor<?> actor : actorsCopy.values()) {
            if (actor instanceof StatefulActor<?,?> statefulActor) {
                persistenceShutdownFutures.add(statefulActor.getPersistenceShutdownFuture());
            }
        }

        // Stop all actors
        logger.info("Stopping all actors");
        for (Actor<?> actor : actorsCopy.values()) {
            if (actor.isRunning()) {
                actor.stop();
            }
        }
        
        // Wait for all persistence operations to complete
        if (!persistenceShutdownFutures.isEmpty()) {
            logger.info("Waiting for {} StatefulActor persistence operations to complete", 
                    persistenceShutdownFutures.size());
            try {
                // Create a combined future that completes when all persistence operations are done
                CompletableFuture<Void> allPersistenceComplete = 
                        CompletableFuture.allOf(persistenceShutdownFutures.toArray(new CompletableFuture[0]));
                
                // Wait for all persistence operations to complete with a timeout
                allPersistenceComplete.get(30, TimeUnit.SECONDS);
                logger.info("All StatefulActor persistence operations completed successfully");
            } catch (InterruptedException e) {
                logger.warn("Interrupted while waiting for persistence operations to complete");
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                logger.warn("Error during persistence shutdown: {}", e.getMessage());
            } catch (TimeoutException e) {
                logger.warn("Timed out waiting for persistence operations to complete");
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
            logger.debug("Shutting down shared executor");
            sharedExecutor.shutdown();
            try {
                int timeoutSeconds = threadPoolConfig.getActorShutdownTimeoutSeconds();
                if (!sharedExecutor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
                    logger.warn("Shared executor did not terminate in time, forcing shutdown");
                    sharedExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                logger.warn("Interrupted while waiting for shared executor to terminate");
                sharedExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        logger.info("Actor system shutdown complete");
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
    public <RequestMessage, ResponseMessage> CompletableFuture<ResponseMessage> ask(Pid target, RequestMessage message, Duration timeout) {
        CompletableFuture<ResponseMessage> future = new CompletableFuture<>();
        String replyActorId = "temp-reply-" + UUID.randomUUID().toString();

        // Create a temporary actor to handle the reply and timeout
        Actor<Object> replyActor = new Actor<>(this, replyActorId) { // Actor type changed to Object
            @Override
            protected void receive(Object receivedMessage) { // Message type changed to Object
                if (!future.isDone()) { // Process only if future is not already completed
                    if (receivedMessage instanceof AskTimeoutMessage) {
                        future.completeExceptionally(new TimeoutException("Ask operation timed out after " + timeout.toMillis() + " ms for actor " + target.actorId()));
                    } else {
                        try {
                            @SuppressWarnings("unchecked")
                            ResponseMessage reply = (ResponseMessage) receivedMessage;
                            future.complete(reply);
                        } catch (ClassCastException e) {
                            logger.warn("Temporary reply actor {} for target {} received unexpected message type: {}",
                                    replyActorId, target.actorId(), receivedMessage.getClass().getName(), e);
                            // Explicitly create the RuntimeException with the ClassCastException as its cause
                            RuntimeException runtimeException = new RuntimeException(
                                "Internal error: Ask pattern reply actor received unexpected message type.",
                                e // The caught ClassCastException
                            );
                            future.completeExceptionally(runtimeException);
                        }
                    }
                }
                // Stop the temporary actor after processing the message (either reply or timeout)
                // or if an error occurs, or if future was already done.
                // The actual unregistration and final future completion (if needed) is in postStop.
                this.stop();
            }

            @Override
            protected void postStop() {
                // If the actor is stopped and the future isn't done (e.g., external stop, or unexpected shutdown)
                // complete the future exceptionally. This ensures the future is always resolved.
                if (!future.isDone()) {
                    future.completeExceptionally(new TimeoutException("Ask operation for actor " + target.actorId() + " ended before a reply was received or completed. Reply actor ID: " + replyActorId));
                }
                actors.remove(replyActorId); // Unregister after stopping, ensuring cleanup
            }
        };

        actors.put(replyActorId, replyActor);
        replyActor.start();
        Pid replyToPid = replyActor.self();

        // Send the AskPayload to the target actor
        AskPayload<RequestMessage> askMessage = new AskPayload<>(message, replyToPid);
        target.tell(askMessage);

        // Schedule a timeout message to be sent to the reply actor using the system's delayed message routing
        this.routeMessage(replyActorId, new AskTimeoutMessage(), timeout.toMillis(), TimeUnit.MILLISECONDS);

        // The CompletableFuture will be completed either by the replyActor receiving a response,
        // a timeout message, or by its postStop logic if stopped prematurely.
        return future;
    }

}
