package com.cajunsystems.builder;

import com.cajunsystems.Actor;
import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.SupervisionStrategy;
import com.cajunsystems.config.BackpressureConfig;
import com.cajunsystems.mailbox.config.MailboxProvider;
import com.cajunsystems.config.ResizableMailboxConfig;
import com.cajunsystems.config.ThreadPoolFactory;
import com.cajunsystems.handler.StatefulHandler;
import com.cajunsystems.internal.StatefulHandlerActor;
import com.cajunsystems.persistence.BatchedMessageJournal;
import com.cajunsystems.persistence.RetryStrategy;
import com.cajunsystems.persistence.SnapshotStore;
import com.cajunsystems.persistence.PersistenceTruncationConfig;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Builder for creating stateful actors with a fluent API.
 * 
 * @param <State> The type of the actor's state
 * @param <Message> The type of messages this actor processes
 */
public class StatefulActorBuilder<State, Message> {

    private final ActorSystem system;
    private final StatefulHandler<State, Message> handler;
    private final Class<? extends StatefulHandler<State, Message>> handlerClass;
    private final State initialState;
    private String id;
    private String idTemplate;
    private IdStrategy idStrategy;
    private BackpressureConfig backpressureConfig;
    private ResizableMailboxConfig mailboxConfig;
    private Actor<?> parent;
    private BatchedMessageJournal<Message> messageJournal;
    private SnapshotStore<State> snapshotStore;
    private boolean customPersistence = false;
    private PersistenceTruncationConfig truncationConfig;
    private SupervisionStrategy supervisionStrategy;
    private ThreadPoolFactory threadPoolFactory;
    private MailboxProvider<Message> mailboxProvider;
    private RetryStrategy retryStrategy;
    private Consumer<Throwable> errorHook;

    /**
     * Creates a new StatefulActorBuilder with the specified system, handler, and initial state.
     *
     * @param system The actor system
     * @param handler The handler to delegate to
     * @param handlerClass The handler class (for ID generation)
     * @param initialState The initial state
     */
    public StatefulActorBuilder(ActorSystem system, StatefulHandler<State, Message> handler,
                               Class<? extends StatefulHandler<State, Message>> handlerClass,
                               State initialState) {
        this.system = system;
        this.handler = handler;
        this.handlerClass = handlerClass;
        this.initialState = initialState;
        this.mailboxConfig = new ResizableMailboxConfig();
        // Don't set default ID here - will be generated at spawn time
    }
    
    /**
     * Sets the explicit ID for the actor (highest priority).
     * This overrides any template or strategy configuration.
     *
     * @param id The ID for the actor
     * @return This builder for method chaining
     */
    public StatefulActorBuilder<State, Message> withId(String id) {
        this.id = id;
        this.idTemplate = null;
        this.idStrategy = null;
        return this;
    }

    /**
     * Sets the ID template for the actor (second priority).
     * Template can include placeholders: {seq}, {uuid}, {timestamp}, {nano}, {class}, etc.
     * <p>
     * Examples:
     * <ul>
     *   <li>{@code "user:{seq}"} → {@code "user:1"}, {@code "user:2"}, etc.</li>
     *   <li>{@code "{class}:{seq}"} → {@code "user:1"}, {@code "order:1"}, etc.</li>
     *   <li>{@code "session:{timestamp}"} → {@code "session:1701234567890"}</li>
     * </ul>
     *
     * @param template The ID template with placeholders
     * @return This builder for method chaining
     */
    public StatefulActorBuilder<State, Message> withIdTemplate(String template) {
        this.idTemplate = template;
        this.id = null;
        this.idStrategy = null;
        return this;
    }

    /**
     * Sets the ID strategy for the actor (third priority).
     * <p>
     * Built-in strategies: {@link IdStrategy#CLASS_BASED_SEQUENTIAL},
     * {@link IdStrategy#UUID}, {@link IdStrategy#CLASS_BASED_UUID}, etc.
     *
     * @param strategy The ID generation strategy
     * @return This builder for method chaining
     */
    public StatefulActorBuilder<State, Message> withIdStrategy(IdStrategy strategy) {
        this.idStrategy = strategy;
        this.id = null;
        this.idTemplate = null;
        return this;
    }
    
    /**
     * Sets the backpressure configuration for the actor.
     * 
     * @param backpressureConfig The backpressure configuration
     * @return This builder for method chaining
     */
    public StatefulActorBuilder<State, Message> withBackpressureConfig(BackpressureConfig backpressureConfig) {
        this.backpressureConfig = backpressureConfig;
        return this;
    }
    
    /**
     * Sets the mailbox configuration for the actor.
     * 
     * @param mailboxConfig The mailbox configuration
     * @return This builder for method chaining
     */
    public StatefulActorBuilder<State, Message> withMailboxConfig(ResizableMailboxConfig mailboxConfig) {
        this.mailboxConfig = mailboxConfig;
        return this;
    }
    
    /**
     * Sets the parent actor for this actor.
     * 
     * @param parent The parent actor
     * @return This builder for method chaining
     */
    public StatefulActorBuilder<State, Message> withParent(Actor<?> parent) {
        this.parent = parent;
        return this;
    }

    /**
     * Sets the parent actor for this actor by its {@link Pid}.
     * <p>
     * This avoids the {@code system.getActor(pid)} round-trip at the call site: since
     * {@code spawn()} hands callers a {@code Pid}, wiring a supervision hierarchy can now stay
     * declarative (e.g. {@code .withParent(parentPid)}) instead of resolving the {@code Actor}
     * reference manually.
     *
     * @param parentPid The PID of the parent actor
     * @return This builder for method chaining
     * @throws IllegalArgumentException if no actor is registered for the given PID
     */
    public StatefulActorBuilder<State, Message> withParent(Pid parentPid) {
        if (parentPid == null) {
            this.parent = null;
            return this;
        }
        Actor<?> resolved = system.getActor(parentPid);
        if (resolved == null) {
            throw new IllegalArgumentException("No actor registered for parent PID: " + parentPid.actorId());
        }
        this.parent = resolved;
        return this;
    }

    /**
     * Sets a custom retry strategy for the actor's persistence operations.
     * <p>
     * Previously this could only be configured post-spawn via {@code system.getActor(pid)} and a
     * cast, which races with asynchronous state initialization. Configuring it on the builder
     * applies it before the actor starts.
     *
     * @param retryStrategy The retry strategy to use
     * @return This builder for method chaining
     */
    public StatefulActorBuilder<State, Message> withRetryStrategy(RetryStrategy retryStrategy) {
        this.retryStrategy = retryStrategy;
        return this;
    }

    /**
     * Sets an error hook to be notified when exceptions occur while processing messages.
     * <p>
     * Like {@link #withRetryStrategy(RetryStrategy)}, configuring this on the builder applies it
     * before the actor starts, avoiding a post-spawn cast that races with state initialization.
     *
     * @param errorHook The error hook to invoke with exceptions
     * @return This builder for method chaining
     */
    public StatefulActorBuilder<State, Message> withErrorHook(Consumer<Throwable> errorHook) {
        this.errorHook = errorHook;
        return this;
    }
    
    /**
     * Sets custom persistence components for the actor.
     * 
     * @param messageJournal The message journal to use
     * @param snapshotStore The snapshot store to use
     * @return This builder for method chaining
     */
    public StatefulActorBuilder<State, Message> withPersistence(
            BatchedMessageJournal<Message> messageJournal,
            SnapshotStore<State> snapshotStore) {
        this.messageJournal = messageJournal;
        this.snapshotStore = snapshotStore;
        this.customPersistence = true;
        return this;
    }
    
    /**
     * Sets the supervision strategy for the actor.
     * 
     * @param strategy The supervision strategy to use
     * @return This builder for method chaining
     */
    public StatefulActorBuilder<State, Message> withSupervisionStrategy(SupervisionStrategy strategy) {
        this.supervisionStrategy = strategy;
        return this;
    }
    
    /**
     * Sets the thread pool factory for the actor.
     * If not specified, the actor will use the default virtual thread-based implementation.
     * 
     * @param threadPoolFactory The thread pool factory to use
     * @return This builder for method chaining
     */
    public StatefulActorBuilder<State, Message> withThreadPoolFactory(ThreadPoolFactory threadPoolFactory) {
        this.threadPoolFactory = threadPoolFactory;
        return this;
    }
    
    /**
     * Sets the mailbox provider for the actor.
     * If not specified, the actor will use the system's default mailbox provider.
     * 
     * @param mailboxProvider The mailbox provider to use
     * @return This builder for method chaining
     */
    public StatefulActorBuilder<State, Message> withMailboxProvider(MailboxProvider<Message> mailboxProvider) {
        this.mailboxProvider = mailboxProvider;
        return this;
    }

    /**
     * Configures automatic persistence truncation behavior for this actor.
     * If not specified, a default synchronous truncation configuration will be used.
     *
     * @param truncationConfig The truncation configuration to use
     * @return This builder for method chaining
     */
    public StatefulActorBuilder<State, Message> withPersistenceTruncation(PersistenceTruncationConfig truncationConfig) {
        this.truncationConfig = truncationConfig;
        return this;
    }
    
    /**
     * Creates and starts the actor with the configured settings.
     *
     * @return The PID of the created actor
     */
    public Pid spawn() {
        // Generate final ID based on priority
        String finalId = generateActorId();

        StatefulHandlerActor<State, Message> actor;

        ThreadPoolFactory tpfToUse = (this.threadPoolFactory != null)
                                       ? this.threadPoolFactory
                                       : system.getThreadPoolFactory();
        MailboxProvider<Message> mpToUse = (this.mailboxProvider != null)
                                           ? this.mailboxProvider
                                           : system.getMailboxProvider();
        ResizableMailboxConfig mbConfigToUse = (this.mailboxConfig != null)
                                                ? this.mailboxConfig
                                                : new ResizableMailboxConfig(); // Or pass null and let Actor constructor use system.getMailboxConfig()

        if (customPersistence) {
            actor = new StatefulHandlerActor<>(
                    system,
                    finalId,       // Use generated ID
                    handler,
                    initialState,
                    messageJournal,
                    snapshotStore,
                    backpressureConfig,
                    mbConfigToUse, // Use effective mailbox config
                    tpfToUse,      // Use effective TPF
                    mpToUse        // Use effective MP
            );
        } else {
            actor = new StatefulHandlerActor<>(
                    system,
                    finalId,       // Use generated ID
                    handler,
                    initialState,
                    backpressureConfig,
                    mbConfigToUse, // Use effective mailbox config
                    tpfToUse,      // Use effective TPF
                    mpToUse        // Use effective MP
            );
        }

        // Apply per-actor truncation configuration if provided
        if (truncationConfig != null) {
            actor.setTruncationConfig(truncationConfig);
        }

        // Apply persistence retry/error configuration before the actor starts
        if (retryStrategy != null) {
            actor.withRetryStrategy(retryStrategy);
        }
        if (errorHook != null) {
            actor.withErrorHook(errorHook);
        }

        if (parent != null) {
            parent.addChild(actor);
            actor.setParent(parent);
        }

        if (supervisionStrategy != null) {
            actor.withSupervisionStrategy(supervisionStrategy);
        }

        system.registerActor(actor);
        actor.start();

        return actor.self();
    }

    /**
     * Creates and starts the actor, then blocks until its state has finished initializing
     * (snapshot load + journal replay) or the timeout elapses.
     * <p>
     * A persistent actor's first message otherwise pays the full cold-start recovery cost, so a
     * plain {@code ask} with an ordinary timeout can fail on the very first call. Awaiting
     * readiness here removes that sharp edge for callers that want a ready-to-use actor.
     *
     * @param timeout The maximum time to wait for state initialization
     * @return The PID of the created actor
     * @throws IllegalStateException if the state does not initialize within the timeout
     */
    public Pid spawnAndAwaitReady(Duration timeout) {
        Pid pid = spawn();
        Actor<?> actor = system.getActor(pid);
        if (actor instanceof StatefulHandlerActor<?, ?> statefulActor) {
            boolean ready;
            try {
                ready = statefulActor.waitForStateInitialization(timeout.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // Clean up the started actor before throwing: the caller never receives the PID,
                // so leaving it running/registered would leak an actor it cannot reference.
                stopQuietly(actor);
                throw new IllegalStateException(
                        "Interrupted while awaiting readiness of actor " + pid.actorId(), e);
            }
            if (!ready) {
                stopQuietly(actor);
                throw new IllegalStateException(
                        "Actor " + pid.actorId() + " did not become ready within " + timeout);
            }
        }
        return pid;
    }

    /**
     * Stops an actor while swallowing any secondary failure, used to clean up after a failed
     * {@link #spawnAndAwaitReady(Duration)} so the original cause is not masked.
     */
    private static void stopQuietly(Actor<?> actor) {
        try {
            actor.stop();
        } catch (RuntimeException stopFailure) {
            // Best-effort cleanup; do not mask the readiness failure being thrown by the caller.
        }
    }

    /**
     * Generate actor ID based on configuration priority:
     * 1. Explicit ID (.withId())
     * 2. ID Template (.withIdTemplate())
     * 3. ID Strategy (.withIdStrategy())
     * 4. System default strategy
     * 5. Fallback to UUID
     */
    private String generateActorId() {
        String baseId = generateBaseId();
        return applyHierarchicalPrefix(baseId);
    }

    /**
     * Generate base ID (without hierarchical prefix from parent).
     */
    private String generateBaseId() {
        // Priority 1: Explicit ID
        if (id != null) {
            return id;
        }

        // Priority 2: Template
        if (idTemplate != null) {
            String parentId = parent != null ? parent.getActorId() : null;
            IdTemplateProcessor processor = new IdTemplateProcessor(
                system, handlerClass, parentId
            );
            return processor.process(idTemplate);
        }

        // Priority 3: Strategy
        if (idStrategy != null) {
            String parentId = parent != null ? parent.getActorId() : null;
            IdStrategy.IdGenerationContext ctx = new IdStrategy.IdGenerationContext(
                system, handlerClass, parentId
            );
            return idStrategy.generateId(ctx);
        }

        // Priority 4: System default strategy
        IdStrategy defaultStrategy = system.getDefaultIdStrategy();
        if (defaultStrategy != null) {
            String parentId = parent != null ? parent.getActorId() : null;
            IdStrategy.IdGenerationContext ctx = new IdStrategy.IdGenerationContext(
                system, handlerClass, parentId
            );
            return defaultStrategy.generateId(ctx);
        }

        // Fallback: UUID (legacy behavior)
        return UUID.randomUUID().toString();
    }

    /**
     * Apply hierarchical prefix if actor has parent.
     */
    private String applyHierarchicalPrefix(String baseId) {
        if (parent == null) {
            return baseId;
        }

        String parentId = parent.getActorId();

        // If explicit ID already contains parent prefix, don't add again
        if (id != null && id.startsWith(parentId + "/")) {
            return id;
        }

        return parentId + "/" + baseId;
    }
}
