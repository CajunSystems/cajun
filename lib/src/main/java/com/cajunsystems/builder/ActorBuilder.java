package com.cajunsystems.builder;

import com.cajunsystems.Actor;
import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.SupervisionStrategy;
import com.cajunsystems.config.BackpressureConfig;
import com.cajunsystems.mailbox.config.MailboxProvider;
import com.cajunsystems.config.ResizableMailboxConfig;
import com.cajunsystems.config.ThreadPoolFactory;
import com.cajunsystems.handler.Handler;
import com.cajunsystems.internal.HandlerActor;

import java.util.UUID;

/**
 * Builder for creating actors with a fluent API.
 * 
 * @param <Message> The type of messages this actor processes
 */
public class ActorBuilder<Message> {

    private final ActorSystem system;
    private final Handler<Message> handler;
    private final Class<? extends Handler<Message>> handlerClass;
    private String id;
    private String idTemplate;
    private IdStrategy idStrategy;
    private BackpressureConfig backpressureConfig;
    private ResizableMailboxConfig mailboxConfig;
    private Actor<?> parent;
    private SupervisionStrategy supervisionStrategy;
    private ThreadPoolFactory threadPoolFactory;
    private MailboxProvider<Message> mailboxProvider;

    /**
     * Creates a new ActorBuilder with the specified system and handler.
     *
     * @param system The actor system
     * @param handler The handler to delegate to
     * @param handlerClass The handler class (for ID generation)
     */
    public ActorBuilder(ActorSystem system, Handler<Message> handler, Class<? extends Handler<Message>> handlerClass) {
        this.system = system;
        this.handler = handler;
        this.handlerClass = handlerClass;
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
    public ActorBuilder<Message> withId(String id) {
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
    public ActorBuilder<Message> withIdTemplate(String template) {
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
    public ActorBuilder<Message> withIdStrategy(IdStrategy strategy) {
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
    public ActorBuilder<Message> withBackpressureConfig(BackpressureConfig backpressureConfig) {
        this.backpressureConfig = backpressureConfig;
        return this;
    }
    
    /**
     * Sets the mailbox configuration for the actor.
     * 
     * @param mailboxConfig The mailbox configuration
     * @return This builder for method chaining
     */
    public ActorBuilder<Message> withMailboxConfig(ResizableMailboxConfig mailboxConfig) {
        this.mailboxConfig = mailboxConfig;
        return this;
    }
    
    /**
     * Sets the parent actor for this actor.
     * 
     * @param parent The parent actor
     * @return This builder for method chaining
     */
    public ActorBuilder<Message> withParent(Actor<?> parent) {
        this.parent = parent;
        return this;
    }
    
    /**
     * Sets the supervision strategy for the actor.
     * 
     * @param supervisionStrategy The supervision strategy to use
     * @return This builder for method chaining
     */
    public ActorBuilder<Message> withSupervisionStrategy(SupervisionStrategy supervisionStrategy) {
        this.supervisionStrategy = supervisionStrategy;
        return this;
    }
    
    /**
     * Sets the thread pool factory for the actor.
     * If not specified, the actor will use the default virtual thread-based implementation.
     * 
     * @param threadPoolFactory The thread pool factory to use
     * @return This builder for method chaining
     */
    public ActorBuilder<Message> withThreadPoolFactory(ThreadPoolFactory threadPoolFactory) {
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
    public ActorBuilder<Message> withMailboxProvider(MailboxProvider<Message> mailboxProvider) {
        this.mailboxProvider = mailboxProvider;
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

        ThreadPoolFactory tpfToUse = (this.threadPoolFactory != null)
                                       ? this.threadPoolFactory
                                       : system.getThreadPoolFactory();
        MailboxProvider<Message> mpToUse = (this.mailboxProvider != null)
                                           ? this.mailboxProvider
                                           : system.getMailboxProvider();

        // Ensure mailboxConfig is initialized if not set, Actor constructor expects non-null or will use system default
        ResizableMailboxConfig mbConfigToUse = (this.mailboxConfig != null)
                                                ? this.mailboxConfig
                                                : new ResizableMailboxConfig(); // Or pass null and let Actor constructor use system.getMailboxConfig()

        HandlerActor<Message> actor = new HandlerActor<>(
                system,
                finalId,            // Use generated ID
                handler,
                backpressureConfig, // Can be null, Actor constructor handles it
                mbConfigToUse,      // Pass potentially defaulted ResizableMailboxConfig
                tpfToUse,           // Pass effective ThreadPoolFactory
                mpToUse             // Pass effective MailboxProvider
        );

        if (supervisionStrategy != null) {
            actor.withSupervisionStrategy(supervisionStrategy);
        }

        if (parent != null) {
            parent.addChild(actor);
            actor.setParent(parent);
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
