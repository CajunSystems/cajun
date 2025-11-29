package com.cajunsystems.functional;

import com.cajunsystems.ActorContext;
import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.handler.Handler;
import com.cajunsystems.handler.StatefulHandler;

/**
 * Builder for creating actors based on the Effect monad.
 * Provides a fluent API for configuring and spawning effect-based actors.
 * 
 * <p>Example usage:
 * <pre>{@code
 * Effect<Integer, CounterMsg, Void> counterEffect = Effect.match()
 *     .when(Increment.class, (state, msg, ctx) -> 
 *         Effect.modify(s -> s + msg.amount()))
 *     .when(GetCount.class, (state, msg, ctx) ->
 *         Effect.tell(msg.replyTo(), state))
 *     .build();
 * 
 * Pid counter = system.effectActorOf(counterEffect, 0)
 *     .withId("counter")
 *     .spawn();
 * }</pre>
 * 
 * @param <State> The type of the actor's state
 * @param <Message> The type of messages the actor processes
 * @param <Result> The type of result produced by the effect (typically Void)
 */
public class EffectActorBuilder<State, Message, Result> {
    
    private final ActorSystem system;
    private final Effect<State, Message, Result> effect;
    private final State initialState;
    private String actorId;
    private boolean persistence = true;
    
    /**
     * Creates a new builder for an effect-based actor.
     * 
     * @param system The actor system
     * @param effect The effect that defines the actor's behavior
     * @param initialState The initial state of the actor
     */
    public EffectActorBuilder(ActorSystem system, Effect<State, Message, Result> effect, State initialState) {
        this.system = system;
        this.effect = effect;
        this.initialState = initialState;
    }
    
    /**
     * Sets the ID for the actor.
     * 
     * @param id The actor ID
     * @return This builder for chaining
     */
    public EffectActorBuilder<State, Message, Result> withId(String id) {
        this.actorId = id;
        return this;
    }
    
    /**
     * Sets whether persistence should be enabled for the actor.
     * 
     * @param enabled Whether to enable persistence
     * @return This builder for chaining
     */
    public EffectActorBuilder<State, Message, Result> withPersistence(boolean enabled) {
        this.persistence = enabled;
        return this;
    }
    
    /**
     * Spawns the actor with the configured effect and initial state.
     * If persistence is enabled, spawns a StatefulActor. Otherwise, spawns a regular Actor.
     * 
     * @return The PID of the spawned actor
     */
    public Pid spawn() {
        if (persistence) {
            // Spawn StatefulActor with persistence
            return spawnStatefulActor();
        } else {
            // Spawn regular Actor without persistence
            return spawnRegularActor();
        }
    }
    
    /**
     * Spawns a regular (non-persistent) actor that maintains state in memory.
     */
    private Pid spawnRegularActor() {
        // Create a wrapper that maintains state in memory
        final State[] currentState = (State[]) new Object[]{initialState};
        
        Handler<Message> handler = new Handler<Message>() {
            @Override
            public void receive(Message message, ActorContext context) {
                EffectResult<State, Result> result = effect.run(currentState[0], message, context);
                
                // Log failures
                if (result.isFailure()) {
                    result.error().ifPresent(error -> 
                        context.getLogger().error("Effect execution failed", error)
                    );
                }
                
                // Update the state
                currentState[0] = result.state();
            }
        };
        
        var builder = system.actorOf(handler);
        
        if (actorId != null) {
            builder = builder.withId(actorId);
        }
        
        return builder.spawn();
    }
    
    /**
     * Spawns a StatefulActor with persistence enabled.
     */
    private Pid spawnStatefulActor() {
        StatefulHandler<State, Message> handler = new StatefulHandler<State, Message>() {
            @Override
            public State receive(Message message, State state, ActorContext context) {
                EffectResult<State, Result> result = effect.run(state, message, context);
                
                // Log failures
                if (result.isFailure()) {
                    result.error().ifPresent(error -> 
                        context.getLogger().error("Effect execution failed", error)
                    );
                }
                
                // Return the new state
                return result.state();
            }
        };
        
        var builder = system.statefulActorOf(handler, initialState);
        
        if (actorId != null) {
            builder = builder.withId(actorId);
        }
        
        return builder.spawn();
    }
    
    /**
     * Spawns the actor and returns both the PID and a handle to the builder for further configuration.
     * This is useful when you want to keep a reference to the builder for later use.
     * 
     * @return The PID of the spawned actor
     */
    public Pid build() {
        return spawn();
    }
}
