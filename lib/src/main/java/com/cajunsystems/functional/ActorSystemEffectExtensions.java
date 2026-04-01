package com.cajunsystems.functional;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;

/**
 * Extension methods for ActorSystem to support Effect-based actors.
 *
 * @deprecated Use {@link com.cajunsystems.ActorSystem#statefulActorOf} directly with a
 *     {@link com.cajunsystems.handler.StatefulHandler} that returns the Roux
 *     {@link com.cajunsystems.roux.Effect Effect&lt;E, State&gt;} from its {@code receive()} method.
 *     This class will be removed in a future release.
 */
@Deprecated(since = "0.5.0", forRemoval = true)
public final class ActorSystemEffectExtensions {
    
    private ActorSystemEffectExtensions() {
        // Utility class, no instantiation
    }
    
    /**
     * Creates a builder for an effect-based actor.
     * 
     * @param system The actor system
     * @param effect The effect that defines the actor's behavior
     * @param initialState The initial state of the actor
     * @return A builder for configuring and spawning the actor
     */
    public static <State, Message, Result> EffectActorBuilder<State, Message, Result> fromEffect(
        ActorSystem system,
        Effect<State, Message, Result> effect,
        State initialState
    ) {
        return new EffectActorBuilder<>(system, effect, initialState);
    }
    
    /**
     * Creates and immediately spawns an effect-based actor with an auto-generated ID.
     * 
     * @param system The actor system
     * @param effect The effect that defines the actor's behavior
     * @param initialState The initial state of the actor
     * @return The PID of the spawned actor
     */
    public static <State, Message, Result> Pid spawnFromEffect(
        ActorSystem system,
        Effect<State, Message, Result> effect,
        State initialState
    ) {
        return fromEffect(system, effect, initialState).spawn();
    }
    
    /**
     * Creates and immediately spawns an effect-based actor with a specific ID.
     * 
     * @param system The actor system
     * @param effect The effect that defines the actor's behavior
     * @param initialState The initial state of the actor
     * @param actorId The ID for the actor
     * @return The PID of the spawned actor
     */
    public static <State, Message, Result> Pid spawnFromEffect(
        ActorSystem system,
        Effect<State, Message, Result> effect,
        State initialState,
        String actorId
    ) {
        return fromEffect(system, effect, initialState)
            .withId(actorId)
            .spawn();
    }
}
