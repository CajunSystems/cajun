package com.cajunsystems.builder;

import com.cajunsystems.Actor;
import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.SupervisionStrategy;
import com.cajunsystems.config.BackpressureConfig;
import com.cajunsystems.config.ResizableMailboxConfig;
import com.cajunsystems.handler.StatefulHandler;
import com.cajunsystems.internal.StatefulHandlerActor;
import com.cajunsystems.persistence.BatchedMessageJournal;
import com.cajunsystems.persistence.SnapshotStore;

import java.util.UUID;

/**
 * Builder for creating stateful actors with a fluent API.
 * 
 * @param <State> The type of the actor's state
 * @param <Message> The type of messages this actor processes
 */
public class StatefulActorBuilder<State, Message> {
    
    private final ActorSystem system;
    private final StatefulHandler<State, Message> handler;
    private final State initialState;
    private String id;
    private BackpressureConfig backpressureConfig;
    private ResizableMailboxConfig mailboxConfig;
    private Actor<?> parent;
    private BatchedMessageJournal<Message> messageJournal;
    private SnapshotStore<State> snapshotStore;
    private boolean customPersistence = false;
    private SupervisionStrategy supervisionStrategy;
    
    /**
     * Creates a new StatefulActorBuilder with the specified system, handler, and initial state.
     * 
     * @param system The actor system
     * @param handler The handler to delegate to
     * @param initialState The initial state
     */
    public StatefulActorBuilder(ActorSystem system, StatefulHandler<State, Message> handler, State initialState) {
        this.system = system;
        this.handler = handler;
        this.initialState = initialState;
        this.id = UUID.randomUUID().toString();
        this.mailboxConfig = new ResizableMailboxConfig();
    }
    
    /**
     * Sets the ID for the actor.
     * 
     * @param id The ID for the actor
     * @return This builder for method chaining
     */
    public StatefulActorBuilder<State, Message> withId(String id) {
        this.id = id;
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
     * Creates and starts the actor with the configured settings.
     * 
     * @return The PID of the created actor
     */
    public Pid spawn() {
        StatefulHandlerActor<State, Message> actor;
        
        if (customPersistence) {
            actor = new StatefulHandlerActor<>(
                    system,
                    id,
                    handler,
                    initialState,
                    messageJournal,
                    snapshotStore,
                    backpressureConfig,
                    mailboxConfig);
        } else {
            actor = new StatefulHandlerActor<>(
                    system,
                    id,
                    handler,
                    initialState,
                    backpressureConfig,
                    mailboxConfig);
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
}
