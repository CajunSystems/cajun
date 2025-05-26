package systems.cajun.builder;

import systems.cajun.Actor;
import systems.cajun.ActorSystem;
import systems.cajun.Pid;
import systems.cajun.config.BackpressureConfig;
import systems.cajun.config.ResizableMailboxConfig;
import systems.cajun.handler.StatefulHandler;
import systems.cajun.internal.StatefulHandlerActor;
import systems.cajun.persistence.BatchedMessageJournal;
import systems.cajun.persistence.SnapshotStore;
import systems.cajun.runtime.persistence.PersistenceFactory;

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
        
        system.registerActor(actor);
        actor.start();
        
        return actor.self();
    }
}
