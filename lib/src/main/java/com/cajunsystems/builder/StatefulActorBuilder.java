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
import com.cajunsystems.loop.BehaviorMiddleware;
import com.cajunsystems.persistence.BatchedMessageJournal;
import com.cajunsystems.persistence.SnapshotStore;
import com.cajunsystems.persistence.PersistenceTruncationConfig;
import com.cajunsystems.roux.capability.Capability;
import com.cajunsystems.roux.capability.CapabilityHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Builder for creating stateful actors with a fluent API.
 *
 * @param <E>       the error type of the handler's {@link com.cajunsystems.roux.Effect}
 * @param <State>   the type of the actor's state
 * @param <Message> the type of messages this actor processes
 */
public class StatefulActorBuilder<E extends Throwable, State, Message> {

    private final ActorSystem system;
    private final StatefulHandler<E, State, Message> handler;
    private final Class<? extends StatefulHandler<E, State, Message>> handlerClass;
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
    private CapabilityHandler<Capability<?>> capabilityHandler;
    private final List<BehaviorMiddleware<E, State, Message>> middlewares = new ArrayList<>();

    /**
     * Creates a new StatefulActorBuilder with the specified system, handler, and initial state.
     *
     * @param system The actor system
     * @param handler The handler to delegate to
     * @param handlerClass The handler class (for ID generation)
     * @param initialState The initial state
     */
    public StatefulActorBuilder(ActorSystem system, StatefulHandler<E, State, Message> handler,
                               Class<? extends StatefulHandler<E, State, Message>> handlerClass,
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
    public StatefulActorBuilder<E, State, Message> withId(String id) {
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
    public StatefulActorBuilder<E, State, Message> withIdTemplate(String template) {
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
    public StatefulActorBuilder<E, State, Message> withIdStrategy(IdStrategy strategy) {
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
    public StatefulActorBuilder<E, State, Message> withBackpressureConfig(BackpressureConfig backpressureConfig) {
        this.backpressureConfig = backpressureConfig;
        return this;
    }
    
    /**
     * Sets the mailbox configuration for the actor.
     * 
     * @param mailboxConfig The mailbox configuration
     * @return This builder for method chaining
     */
    public StatefulActorBuilder<E, State, Message> withMailboxConfig(ResizableMailboxConfig mailboxConfig) {
        this.mailboxConfig = mailboxConfig;
        return this;
    }
    
    /**
     * Sets the parent actor for this actor.
     * 
     * @param parent The parent actor
     * @return This builder for method chaining
     */
    public StatefulActorBuilder<E, State, Message> withParent(Actor<?> parent) {
        this.parent = parent;
        return this;
    }
    
    /**
     * Sets custom persistence components for the actor.
     * 
     * @param messageJournal The message journal to use
     * @param snapshotStore The snapshot store to use
     * @return This builder for method chaining
     */
    public StatefulActorBuilder<E, State, Message> withPersistence(
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
    public StatefulActorBuilder<E, State, Message> withSupervisionStrategy(SupervisionStrategy strategy) {
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
    public StatefulActorBuilder<E, State, Message> withThreadPoolFactory(ThreadPoolFactory threadPoolFactory) {
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
    public StatefulActorBuilder<E, State, Message> withMailboxProvider(MailboxProvider<Message> mailboxProvider) {
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
    public StatefulActorBuilder<E, State, Message> withPersistenceTruncation(PersistenceTruncationConfig truncationConfig) {
        this.truncationConfig = truncationConfig;
        return this;
    }

    /**
     * Sets the Roux capability handler for this actor.
     *
     * <p>When a capability handler is supplied, each message's effect is executed with
     * {@code unsafeRunWithHandler(effect, capabilityHandler)} instead of the plain
     * {@code unsafeRun(effect)}, allowing Roux capabilities (e.g. a custom {@code Clock},
     * {@code Random}, or any user-defined capability) to be injected at the actor level.
     *
     * @param capabilityHandler the handler used to resolve capabilities in the actor's effects
     * @return this builder for method chaining
     */
    public StatefulActorBuilder<E, State, Message> withCapabilityHandler(
            CapabilityHandler<Capability<?>> capabilityHandler) {
        this.capabilityHandler = capabilityHandler;
        return this;
    }

    /**
     * Adds a {@link BehaviorMiddleware} to the actor's behavior pipeline.
     *
     * <p>Middlewares are applied in the order they are added: the first added
     * middleware is the innermost wrapper around the base behavior, and the last
     * added is the outermost.  Execution order (outermost first) is therefore the
     * reverse of addition order:
     * <pre>
     * Added order:   mw1, mw2, mw3
     * Exec order:    mw3 → mw2 → mw1 → baseBehavior
     * </pre>
     *
     * <p>Example:
     * <pre>{@code
     * system.statefulActorOf(MyHandler.class, initial)
     *     .withMiddleware(new LoggingMiddleware<>("payment"))
     *     .withMiddleware(new MetricsMiddleware<>("payment"))
     *     .withMiddleware(RetryMiddleware.withExponentialBackoff(3, Duration.ofMillis(50)))
     *     .spawn();
     * }</pre>
     *
     * @param middleware the middleware to add
     * @return this builder for method chaining
     */
    public StatefulActorBuilder<E, State, Message> withMiddleware(
            BehaviorMiddleware<E, State, Message> middleware) {
        this.middlewares.add(middleware);
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

        StatefulHandlerActor<E, State, Message> actor;

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
                    finalId,
                    handler,
                    initialState,
                    messageJournal,
                    snapshotStore,
                    backpressureConfig,
                    mbConfigToUse,
                    tpfToUse,
                    mpToUse,
                    List.copyOf(middlewares)
            );
        } else {
            actor = new StatefulHandlerActor<>(
                    system,
                    finalId,
                    handler,
                    initialState,
                    backpressureConfig,
                    mbConfigToUse,
                    tpfToUse,
                    mpToUse,
                    List.copyOf(middlewares)
            );
        }

        // Apply per-actor truncation configuration if provided
        if (truncationConfig != null) {
            actor.setTruncationConfig(truncationConfig);
        }

        // Apply capability handler if provided
        if (capabilityHandler != null) {
            actor.setCapabilityHandler(capabilityHandler);
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
