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
    private String id;
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
     */
    public ActorBuilder(ActorSystem system, Handler<Message> handler) {
        this.system = system;
        this.handler = handler;
        this.id = UUID.randomUUID().toString();
        this.mailboxConfig = new ResizableMailboxConfig();
    }
    
    /**
     * Sets the ID for the actor.
     * 
     * @param id The ID for the actor
     * @return This builder for method chaining
     */
    public ActorBuilder<Message> withId(String id) {
        this.id = id;
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
                id, 
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
}
