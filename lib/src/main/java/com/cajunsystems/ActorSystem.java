package com.cajunsystems;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.Duration;
import java.util.function.Consumer;

import com.cajunsystems.backpressure.*;
import com.cajunsystems.builder.ActorBuilder;
import com.cajunsystems.builder.StatefulActorBuilder;
import com.cajunsystems.config.BackpressureConfig;
import com.cajunsystems.config.MailboxConfig;
import com.cajunsystems.config.MailboxProvider;
import com.cajunsystems.config.DefaultMailboxProvider;
import com.cajunsystems.config.ThreadPoolFactory;
import com.cajunsystems.handler.Handler;
import com.cajunsystems.handler.StatefulHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
     * Adapter method to convert a com.cajunsystems.Receiver to an ActorSystem.Receiver
     * 
     * @param <Message> The type of messages
     * @param cajunReceiver The com.cajunsystems.Receiver to adapt
     * @return An ActorSystem.Receiver that calls the cajunReceiver.accept method
     */
    public static <Message> Receiver<Message> adaptReceiver(com.cajunsystems.Receiver<Message> cajunReceiver) {
        return cajunReceiver::accept;
    }

    /**
     * Message structure for the ask pattern that carries the original request and the reply address.
     */
    public record AskPayload<RequestMessage>(RequestMessage message, String replyTo) {}
    
    /**
     * Internal wrapper for messages that includes sender context.
     * This is used to pass sender information through the mailbox.
     */
    record MessageWithSender<T>(T message, String sender) {}

    /**
     * Functional actor implementation that delegates message handling to a function.
     */
    private static class FunctionalActor<Message> extends Actor<Message> {
        private final Receiver<Message> receiver;
        
        public FunctionalActor(ActorSystem system, String id, Receiver<Message> receiver) {
            super(system, 
                  id, 
                  system.getBackpressureConfig(), 
                  system.getMailboxConfig(), 
                  system.getThreadPoolFactory(), 
                  system.getMailboxProvider());
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
    private final BackpressureConfig backpressureConfig;
    private final MailboxConfig mailboxConfig;
    private final MailboxProvider<?> mailboxProvider;
    private final SystemBackpressureMonitor backpressureMonitor;

    // Promise-based ask pattern support
    private final ConcurrentHashMap<String, CompletableFuture<Object>> pendingAskPromises;

    // Keep-alive mechanism for virtual threads
    private final Thread keepAliveThread;
    private final CountDownLatch shutdownLatch;

    /**
     * Creates a new ActorSystem with the default configuration.
     */
    public ActorSystem() {
        this(new ThreadPoolFactory(), null, new MailboxConfig(), new DefaultMailboxProvider<>());
    }

    /**
     * Creates a new ActorSystem with the specified shared executor configuration.
     * 
     * @param useSharedExecutor Whether to use a shared executor for all actors
     */
    public ActorSystem(boolean useSharedExecutor) {
        this(new ThreadPoolFactory().setUseSharedExecutor(useSharedExecutor), null, new MailboxConfig(), new DefaultMailboxProvider<>());
    }

    /**
     * Creates a new ActorSystem with the specified thread pool configuration.
     * 
     * @param threadPoolConfig The thread pool configuration
     */
    public ActorSystem(ThreadPoolFactory threadPoolConfig) {
        this(threadPoolConfig, null, new MailboxConfig(), new DefaultMailboxProvider<>());
    }

    /**
     * Creates a new ActorSystem with the specified thread pool configuration and backpressure configuration.
     * 
     * @param threadPoolConfig The thread pool configuration
     * @param backpressureConfig The backpressure configuration
     */
    public ActorSystem(ThreadPoolFactory threadPoolConfig, BackpressureConfig backpressureConfig) {
        this(threadPoolConfig, backpressureConfig, new MailboxConfig(), new DefaultMailboxProvider<>());
    }
    
    /**
     * Creates a new ActorSystem with the specified thread pool configuration, backpressure configuration, and mailbox configuration.
     * This constructor now delegates to the primary constructor that includes MailboxProvider.
     * 
     * @param threadPoolConfig The thread pool configuration
     * @param backpressureConfig The backpressure configuration
     * @param mailboxConfig The mailbox configuration
     */
    public ActorSystem(ThreadPoolFactory threadPoolConfig, BackpressureConfig backpressureConfig, MailboxConfig mailboxConfig) {
        this(threadPoolConfig, backpressureConfig, mailboxConfig, new DefaultMailboxProvider<>());
    }

    /**
     * Primary constructor for ActorSystem.
     * 
     * @param threadPoolConfig The thread pool configuration
     * @param backpressureConfig The backpressure configuration
     * @param mailboxConfig The mailbox configuration
     * @param mailboxProvider The mailbox provider implementation
     */
    public ActorSystem(ThreadPoolFactory threadPoolConfig,
                       BackpressureConfig backpressureConfig,
                       MailboxConfig mailboxConfig,
                       MailboxProvider<?> mailboxProvider) {
        this.actors = new ConcurrentHashMap<>();
        this.threadPoolConfig = threadPoolConfig != null ? threadPoolConfig : new ThreadPoolFactory();
        this.backpressureConfig = backpressureConfig; // Allow null to disable backpressure
        this.mailboxConfig = mailboxConfig != null ? mailboxConfig : new MailboxConfig();
        this.mailboxProvider = mailboxProvider != null ? mailboxProvider : new DefaultMailboxProvider<>();

        // Create the delay scheduler based on configuration
        this.delayScheduler = this.threadPoolConfig.createScheduledExecutorService("actor-system");
        this.pendingDelayedMessages = new ConcurrentHashMap<>();
        this.pendingAskPromises = new ConcurrentHashMap<>();
        
        // Create a shared executor for all actors if enabled
        if (this.threadPoolConfig.isUseSharedExecutor()) {
            this.sharedExecutor = this.threadPoolConfig.createExecutorService("shared-actor");
        } else {
            this.sharedExecutor = null;
        }
        
        // Initialize the backpressure monitor only if backpressure is configured
        this.backpressureMonitor = this.backpressureConfig != null ? new SystemBackpressureMonitor(this) : null;
        
        // Initialize keep-alive mechanism
        // Virtual threads are always daemon threads, so we need a non-daemon platform thread
        // to keep the JVM alive until shutdown() is called
        this.shutdownLatch = new CountDownLatch(1);
        this.keepAliveThread = new Thread(() -> {
            try {
                logger.debug("Keep-alive thread started, waiting for shutdown signal...");
                shutdownLatch.await(); // Block until shutdown() is called
                logger.debug("Keep-alive thread received shutdown signal, exiting...");
            } catch (InterruptedException e) {
                logger.debug("Keep-alive thread interrupted");
                Thread.currentThread().interrupt();
            }
        }, "actor-system-keepalive");
        this.keepAliveThread.setDaemon(false); // Non-daemon to keep JVM alive
        this.keepAliveThread.start();
        
        logger.debug("ActorSystem created with {} scheduler threads, thread pool type: {}", 
                this.threadPoolConfig.getSchedulerThreads(), this.threadPoolConfig.getExecutorType());
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
    /* package */ <T extends Actor<?>> Pid registerChild(Class<T> actorClass, String actorId, Actor<?> parent) {
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
    /* package */ <T extends Actor<?>> Pid registerChild(Class<T> actorClass, Actor<?> parent) {
        return registerChild(actorClass, generateActorId(), parent);
    }

    /**
     * Registers a new actor with this system, using the default configuration.
     * 
     * @param <T> The type of actor to register
     * @param actorClass The class of the actor to register
     * @param actorId The unique ID to assign to this actor
     * @return The PID of the registered actor
     */
    public <T extends Actor<?>> Pid register(Class<T> actorClass, String actorId) {
        return register(actorClass, actorId, null, this.mailboxConfig);
    }
    
    /**
     * Registers a new actor with this system, with optional backpressure support.
     *
     * @param <T> The type of actor to register
     * @param actorClass The class of the actor to register
     * @param actorId The unique ID to assign to this actor
     * @param enableBackpressure Whether to enable backpressure support for this actor
     * @return The PID of the registered actor
     * @deprecated Use {@link #register(Class, String, BackpressureConfig)} instead
     */
    @Deprecated
    /* package */ <T extends Actor<?>> Pid register(Class<T> actorClass, String actorId, boolean enableBackpressure) {
        // Convert boolean to BackpressureConfig for backward compatibility
        BackpressureConfig config = enableBackpressure ? backpressureConfig : null;
        return register(actorClass, actorId, config, this.mailboxConfig);
    }
    
    /**
     * Registers a new actor with this system, with optional backpressure support.
     *
     * @param <T> The type of actor to register
     * @param actorClass The class of the actor to register
     * @param actorId The unique ID to assign to this actor
     * @param backpressureConfig The backpressure configuration, or null to disable backpressure
     * @return The PID of the registered actor
     */
    public <T extends Actor<?>> Pid register(Class<T> actorClass, String actorId, BackpressureConfig backpressureConfig) {
        return register(actorClass, actorId, backpressureConfig, this.mailboxConfig);
    }
    
    /**
     * Registers a new actor with this system, with optional backpressure support and custom mailbox configuration.
     *
     * @param <T> The type of actor to register
     * @param actorClass The class of the actor to register
     * @param actorId The unique ID to assign to this actor
     * @param backpressureConfig The backpressure configuration, or null to disable backpressure
     * @param mailboxConfig The mailbox configuration
     * @return The PID of the registered actor
     */
    public <T extends Actor<?>> Pid register(Class<T> actorClass, String actorId, BackpressureConfig backpressureConfig, MailboxConfig mailboxConfig) {
        try {
            // Create the actor instance using reflection
            // The Actor constructor signature is expected to be:
            // (ActorSystem, String, BackpressureConfig, MailboxConfig, ThreadPoolFactory, MailboxProvider)
            Constructor<T> constructor = actorClass.getConstructor(
                ActorSystem.class, 
                String.class, 
                BackpressureConfig.class, 
                MailboxConfig.class,
                ThreadPoolFactory.class,    // Added
                MailboxProvider.class       // Added
            );
            T actor = constructor.newInstance(
                this, 
                actorId, 
                backpressureConfig, 
                mailboxConfig,
                this.threadPoolConfig,    // Pass system's ThreadPoolFactory
                this.mailboxProvider      // Pass system's MailboxProvider
            );
            
            // Register the actor with this system
            actors.put(actorId, actor);
            
            // Start the actor
            actor.start();
            
            return actor.getPid();
        } catch (NoSuchMethodException e) {
            // Try to find a constructor that takes just the system and ID
            try {
                Constructor<T> constructor = actorClass.getConstructor(ActorSystem.class, String.class);
                T actor = constructor.newInstance(this, actorId);
                actors.put(actorId, actor);
                actor.start();
                return actor.getPid();
            } catch (Exception ex) {
                throw new RuntimeException("Failed to create actor of type " + actorClass.getName(), ex);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to create actor of type " + actorClass.getName(), e);
        }
    }
    
    /**
     * Registers a new actor with this system, with custom backpressure configuration.
     *
     * @param <T> The type of actor to register
     * @param actorClass The class of the actor to register
     * @param actorId The unique ID to assign to this actor
     * @param enableBackpressure Whether to enable backpressure support for this actor
     * @param initialCapacity The initial capacity of the mailbox (only used if backpressure is enabled)
     * @param maxCapacity The maximum capacity the mailbox can grow to (only used if backpressure is enabled)
     * @return The PID of the registered actor
     * @deprecated Use {@link #register(Class, String, BackpressureConfig, MailboxConfig)} instead
     */
    @Deprecated
    /* package */ <T extends Actor<?>> Pid register(Class<T> actorClass, String actorId, boolean enableBackpressure, 
                                             int initialCapacity, int maxCapacity) {
        // Convert boolean to BackpressureConfig for backward compatibility
        BackpressureConfig config = enableBackpressure ? backpressureConfig : null;
        MailboxConfig customMailboxConfig = new MailboxConfig()
            .setInitialCapacity(initialCapacity)
            .setMaxCapacity(maxCapacity);
        return register(actorClass, actorId, config, customMailboxConfig);
    }
    
    /**
     * Registers a new actor with this system, with custom backpressure configuration.
     *
     * @param <T> The type of actor to register
     * @param actorClass The class of the actor to register
     * @param actorId The unique ID to assign to this actor
     * @param backpressureConfig The backpressure configuration, or null to disable backpressure
     * @param initialCapacity The initial capacity of the mailbox
     * @param maxCapacity The maximum capacity the mailbox can grow to
     * @return The PID of the registered actor
     * @deprecated Use {@link #register(Class, String, BackpressureConfig, MailboxConfig)} instead
     */
    @Deprecated
    public <T extends Actor<?>> Pid register(Class<T> actorClass, String actorId, BackpressureConfig backpressureConfig, 
                                             int initialCapacity, int maxCapacity) {
        MailboxConfig customMailboxConfig = new MailboxConfig()
            .setInitialCapacity(initialCapacity)
            .setMaxCapacity(maxCapacity);
        return register(actorClass, actorId, backpressureConfig, customMailboxConfig);
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
    protected <Message> Pid register(Receiver<Message> receiver, String actorId) {
        Actor<Message> actor = new FunctionalActor<>(this, actorId, receiver);
        actors.put(actorId, actor);
        actor.start();
        return actor.self();
    }

    /**
     * Registers an actor with the specified com.cajunsystems.Receiver and ID.
     * This is an adapter method to support the com.cajunsystems.Receiver interface.
     * 
     * @param <Message> The type of messages the actor will handle
     * @param cajunReceiver The com.cajunsystems.Receiver for the actor
     * @param actorId The ID for the actor
     * @return The PID of the created actor
     */
    protected <Message> Pid register(com.cajunsystems.Receiver<Message> cajunReceiver, String actorId) {
        return register(adaptReceiver(cajunReceiver), actorId);
    }

    /**
     * Registers an actor with the specified class and an auto-generated ID.
     * 
     * @param <T> The type of the actor
     * @param actorClass The class of the actor
     * @return The PID of the created actor
     */
    protected <T extends Actor<?>> Pid register(Class<T> actorClass) {
        return register(actorClass, generateActorId());
    }
    
    /**
     * Creates a builder for a new actor with the specified handler class.
     * This is the preferred way to create actors using the new interface-based approach.
     * 
     * @param <Message> The type of messages the actor will handle
     * @param handlerClass The class of the handler to use
     * @return A builder for configuring and creating the actor
     */
    public <Message> ActorBuilder<Message> actorOf(Class<? extends Handler<Message>> handlerClass) {
        try {
            Handler<Message> handler = handlerClass.getDeclaredConstructor().newInstance();
            return new ActorBuilder<Message>(this, handler);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create handler of type " + handlerClass.getName(), e);
        }
    }
    
    /**
     * Creates a builder for a new actor with the specified handler instance.
     * 
     * @param <Message> The type of messages the actor will handle
     * @param handler The handler instance to use
     * @return A builder for configuring and creating the actor
     */
    public <Message> ActorBuilder<Message> actorOf(Handler<Message> handler) {
        return new ActorBuilder<Message>(this, handler);
    }
    
    /**
     * Creates a builder for a new stateful actor with the specified handler class and initial state.
     * This is the preferred way to create stateful actors using the new interface-based approach.
     * 
     * @param <State> The type of the actor's state
     * @param <Message> The type of messages the actor will handle
     * @param handlerClass The class of the handler to use
     * @param initialState The initial state for the actor
     * @return A builder for configuring and creating the stateful actor
     */
    public <State, Message> StatefulActorBuilder<State, Message> statefulActorOf(
            Class<? extends StatefulHandler<State, Message>> handlerClass,
            State initialState) {
        try {
            StatefulHandler<State, Message> handler = handlerClass.getDeclaredConstructor().newInstance();
            return new StatefulActorBuilder<State, Message>(this, handler, initialState);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create handler of type " + handlerClass.getName(), e);
        }
    }
    
    /**
     * Creates a builder for a new stateful actor with the specified handler instance and initial state.
     * 
     * @param <State> The type of the actor's state
     * @param <Message> The type of messages the actor will handle
     * @param handler The handler instance to use
     * @param initialState The initial state for the actor
     * @return A builder for configuring and creating the stateful actor
     */
    public <State, Message> StatefulActorBuilder<State, Message> statefulActorOf(
            StatefulHandler<State, Message> handler, 
            State initialState) {
        return new StatefulActorBuilder<State, Message>(this, handler, initialState);
    }
    
    /**
     * Registers an actor with this system.
     * This method is used by the builder classes.
     * 
     * @param actor The actor to register
     */
    public void registerActor(Actor<?> actor) {
        actors.put(actor.getActorId(), actor);
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
     * Stops an actor identified by its Pid.
     * 
     * @param pid The Pid of the actor to stop
     */
    public void stopActor(Pid pid) {
        Actor<?> actor = getActor(pid);
        if (actor != null) {
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
     * Gets the backpressure configuration for this actor system.
     * 
     * @return The backpressure configuration
     */
    public BackpressureConfig getBackpressureConfig() {
        return backpressureConfig;
    }
    
    /**
     * Gets the backpressure monitor for this actor system.
     * The monitor provides centralized access to actor backpressure functionality.
     * 
     * @return The system backpressure monitor
     */
    public SystemBackpressureMonitor getBackpressureMonitor() {
        return backpressureMonitor;
    }
    
    /**
     * Creates a builder for configuring backpressure on an actor identified by PID.
     * This provides a modern, fluent API for backpressure configuration.
     *
     * @param <T> The type of messages processed by the actor
     * @param pid The PID of the actor to configure
     * @return A builder for fluent backpressure configuration
     */
    public <T> BackpressureBuilder<T> configureBackpressure(Pid pid) {
        return backpressureMonitor.configureBackpressure(pid);
    }
    
    /**
     * Gets comprehensive status information about an actor's backpressure system.
     *
     * @param pid The PID of the actor to get status for
     * @return Detailed backpressure status information
     */
    public BackpressureStatus getBackpressureStatus(Pid pid) {
        return backpressureMonitor.getBackpressureStatus(pid);
    }
    
    /**
     * Gets whether backpressure is active for an actor.
     *
     * @param pid The PID of the actor to check
     * @return true if backpressure is active, false otherwise
     */
    public boolean isBackpressureActive(Pid pid) {
        return backpressureMonitor.isBackpressureActive(pid);
    }
    
    /**
     * Gets the current backpressure state of an actor.
     *
     * @param pid The PID of the actor to get the state for
     * @return The current backpressure state
     */
    public BackpressureState getCurrentBackpressureState(Pid pid) {
        return backpressureMonitor.getCurrentBackpressureState(pid);
    }
    
    /**
     * Gets the time an actor has been in its current backpressure state.
     *
     * @param pid The PID of the actor to check
     * @return The duration the actor has been in its current state
     */
    public Duration getTimeInCurrentBackpressureState(Pid pid) {
        return backpressureMonitor.getTimeInCurrentBackpressureState(pid);
    }
    
    /**
     * Sends a message to an actor with customizable backpressure options.
     *
     * @param <T> The type of messages processed by the actor
     * @param pid The PID of the actor to send the message to
     * @param message The message to send
     * @param options Options for handling backpressure
     * @return true if the message was accepted, false otherwise
     */
    public <T> boolean tellWithOptions(Pid pid, T message, BackpressureSendOptions options) {
        return backpressureMonitor.tellWithOptions(pid, message, options);
    }
    
    /**
     * Sets a callback to be notified of backpressure events for a specific actor.
     *
     * @param pid The PID of the actor to set the callback for
     * @param callback The callback to invoke with event information
     */
    public void setBackpressureCallback(Pid pid, Consumer<BackpressureEvent> callback) {
        backpressureMonitor.setBackpressureCallback(pid, callback);
    }
    
    /**
     * Gets the mailbox configuration for this actor system.
     * 
     * @return The mailbox configuration
     */
    public MailboxConfig getMailboxConfig() {
        return mailboxConfig;
    }

    /**
     * Gets the mailbox provider for this actor system with a specific type parameter.
     * This method performs an unchecked cast, which is safe because MailboxProvider
     * implementations are typically stateless factories.
     *
     * @param <T> The message type
     * @return The mailbox provider cast to the specified type
     */
    @SuppressWarnings("unchecked")
    public <T> MailboxProvider<T> getMailboxProvider() {
        return (MailboxProvider<T>) mailboxProvider;
    }

    /**
     * Shuts down all actors in the system.
     * This method stops all actors, waits for any persistence operations to complete,
     * and then clears the actor registry and shuts down the thread pools.
     */
    /**
     * Generates a unique actor ID using a UUID.
     * 
     * @return A string representation of a UUID to be used as an actor ID
     */
    public String generateActorId() {
        return UUID.randomUUID().toString();
    }
    
    public void shutdown() {
        logger.info("Shutting down all actors in the system");
        
        // Get a copy of all actor IDs to avoid ConcurrentModificationException
        List<String> actorIds = new ArrayList<>(actors.keySet());
        
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            // Submit shutdown task for each actor
            for (String actorId : actorIds) {
                scope.fork(() -> {
                    Actor<?> actor = actors.get(actorId);
                    if (actor != null && actor.isRunning()) {
                        try {
                            actor.stop();
                            logger.debug("Actor {} shut down successfully", actorId);
                        } catch (Exception e) {
                            logger.warn("Error shutting down actor {}", actorId, e);
                            throw e; // Propagate exception to the scope
                        }
                    }
                    return null;
                });
            }
            
            try {
                // Wait for all tasks to complete or fail
                scope.join();
                // Ensure no failures occurred
                scope.throwIfFailed(e -> new RuntimeException("Error during actor system shutdown", e));
                
                // Clear the actor registry
                actors.clear();

                // Cancel any pending ask promises
                for (CompletableFuture<Object> promise : pendingAskPromises.values()) {
                    promise.completeExceptionally(new IllegalStateException("Actor system shutting down"));
                }
                pendingAskPromises.clear();

                // Cancel any scheduled messages
                for (ScheduledFuture<?> future : pendingDelayedMessages.values()) {
                    future.cancel(true);
                }
                pendingDelayedMessages.clear();
                
                // Shutdown the delay scheduler
                safeShutdownScheduler(delayScheduler);

                // Shutdown the shared executor if it exists
                safeShutdownScheduler(sharedExecutor);
                
                // Signal the keep-alive thread to exit
                shutdownLatch.countDown();
                
                // Wait for keep-alive thread to finish (with timeout)
                try {
                    if (!keepAliveThread.join(Duration.ofSeconds(2))) {
                        logger.warn("Keep-alive thread did not exit within timeout");
                        keepAliveThread.interrupt();
                    }
                } catch (InterruptedException e) {
                    logger.warn("Interrupted while waiting for keep-alive thread");
                    Thread.currentThread().interrupt();
                }

                logger.info("Actor system shut down successfully");
            } catch (InterruptedException e) {
                logger.error("Actor system shutdown was interrupted", e);
                Thread.currentThread().interrupt();
                throw new RuntimeException("Actor system shutdown was interrupted", e);
            }
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
     * Completes a pending ask promise with a response.
     * This is called when an actor sends a reply to a promise ID.
     *
     * @param promiseId The ID of the promise to complete
     * @param response The response to complete the promise with
     * @return true if the promise was found and completed, false otherwise
     */
    boolean completeAskPromise(String promiseId, Object response) {
        CompletableFuture<Object> promise = pendingAskPromises.remove(promiseId);
        if (promise != null) {
            promise.complete(response);
            return true;
        }
        return false;
    }

    /**
     * Routes a message to an actor by ID.
     * Automatically unwraps AskPayload messages and wraps them with sender context.
     *
     * @param <Message> The type of the message
     * @param actorId The ID of the actor to route the message to
     * @param message The message to route
     */
    <Message> void routeMessage(String actorId, Message message) {
        // Check if this is a reply to a promise (ask pattern)
        if (actorId.startsWith("ask-promise-")) {
            if (completeAskPromise(actorId, message)) {
                return; // Promise completed successfully
            }
            // If promise not found, it might have timed out - log and continue
            logger.debug("Promise {} not found (may have timed out), ignoring reply", actorId);
            return;
        }

        Actor<?> actor = actors.get(actorId);
        if (actor != null) {
            try {
                // Check if the message is an AskPayload and unwrap it
                if (message instanceof AskPayload<?>) {
                    AskPayload<?> askPayload = (AskPayload<?>) message;
                    Object unwrappedMessage = askPayload.message();
                    String replyTo = askPayload.replyTo();

                    // Wrap the unwrapped message with sender context
                    MessageWithSender<Object> wrappedMessage = new MessageWithSender<>(unwrappedMessage, replyTo);

                    @SuppressWarnings("unchecked")
                    Actor<MessageWithSender<Object>> typedActor = (Actor<MessageWithSender<Object>>) actor;
                    typedActor.tell(wrappedMessage);
                } else {
                    // Normal message routing without sender context
                    @SuppressWarnings("unchecked")
                    Actor<Message> typedActor = (Actor<Message>) actor;
                    typedActor.tell(message);
                }
            } catch (ClassCastException e) {
                logger.warn("Failed to route message to actor {}: Message type mismatch", actorId, e);
            }
        } else {
            logger.warn("Failed to route message to actor {}: Actor not found", actorId);
        }
    }
    
    /**
     * Sends a message to an actor.
     * 
     * @param <T> The type of the message
     * @param pid The PID of the actor to send the message to
     * @param message The message to send
     */
    public <T> void tell(Pid pid, T message) {
        routeMessage(pid.actorId(), message);
    }
    
    /**
     * Sends a message to an actor after a delay.
     * 
     * @param <T> The type of the message
     * @param pid The PID of the actor to send the message to
     * @param message The message to send
     * @param delay The delay amount
     * @param timeUnit The time unit for the delay
     */
    public <T> void tell(Pid pid, T message, long delay, TimeUnit timeUnit) {
        routeMessage(pid.actorId(), message, delay, timeUnit);
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
        String taskId = generateActorId();
        ScheduledFuture<?> future = delayScheduler.schedule(() -> {
            pendingDelayedMessages.remove(taskId);
            routeMessage(actorId, message);
        }, delay, timeUnit);
        pendingDelayedMessages.put(taskId, future);
    }

    /**
     * Sends a message to the target actor and returns a CompletableFuture that will be completed with the reply.
     * This implementation uses a lightweight promise-based approach instead of spawning a temporary reply actor,
     * eliminating the race condition where a reply could be sent before the reply actor is ready.
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

        // Generate a unique promise ID for this ask request
        String promiseId = "ask-promise-" + generateActorId();

        // Create and register the promise BEFORE sending the message to avoid race condition
        CompletableFuture<Object> promise = new CompletableFuture<>();
        pendingAskPromises.put(promiseId, promise);

        // Schedule a timeout to remove and fail the promise
        ScheduledFuture<?> timeoutFuture = delayScheduler.schedule(() -> {
            CompletableFuture<Object> timedOutPromise = pendingAskPromises.remove(promiseId);
            if (timedOutPromise != null) {
                timedOutPromise.completeExceptionally(
                    new TimeoutException("Timeout waiting for response from " + target.actorId()));
            }
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);

        // Chain the promise to the result future with type casting and error handling
        promise.whenComplete((response, error) -> {
            // Cancel the timeout
            timeoutFuture.cancel(false);

            if (error != null) {
                result.completeExceptionally(error);
            } else {
                try {
                    result.complete((ResponseMessage) response);
                } catch (ClassCastException e) {
                    result.completeExceptionally(
                        new IllegalArgumentException("Received response of unexpected type: " +
                            (response != null ? response.getClass().getName() : "null"), e));
                }
            }
        });

        try {
            // Check if target actor exists
            Actor<?> targetActor = getActor(target);
            if (targetActor != null) {
                // Create the ask payload with the message and promise ID
                AskPayload<RequestMessage> askMessage = new AskPayload<>(message, promiseId);

                // Send the message - the promise is already registered, so no race condition
                routeMessage(target.actorId(), askMessage);
            } else {
                // Target actor not found - remove promise and complete exceptionally
                pendingAskPromises.remove(promiseId);
                timeoutFuture.cancel(false);
                result.completeExceptionally(
                    new IllegalArgumentException("Target actor not found: " + target.actorId()));
            }
        } catch (Exception e) {
            // Error sending message - remove promise and complete exceptionally
            pendingAskPromises.remove(promiseId);
            timeoutFuture.cancel(false);
            result.completeExceptionally(e);
        }

        return result;
    }
}
