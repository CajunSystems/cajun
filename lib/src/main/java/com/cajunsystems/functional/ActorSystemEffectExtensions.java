package com.cajunsystems.functional;

import com.cajunsystems.ActorSystem;

/**
 * Extension methods for ActorSystem to support Effect-based actors.
 * This class provides static methods that can be used to create effect-based actors.
 * 
 * <p>Usage:
 * <pre>{@code
 * import static com.cajunsystems.functional.ActorSystemEffectExtensions.*;
 * 
 * Effect<Integer, Msg, Void> effect = ...;
 * Pid actor = fromEffect(system, effect, 0)
 *     .withId("my-actor")
 *     .spawn();
 * }</pre>
 * 
 * <p>Note: In Java, we can't add extension methods directly to classes like in Kotlin or C#,
 * so we provide static methods that take the ActorSystem as the first parameter.
 * A future version might integrate these directly into ActorSystem.
 */
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
    public static <State, Message, Result> com.cajunsystems.Pid spawnFromEffect(
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
    public static <State, Message, Result> com.cajunsystems.Pid spawnFromEffect(
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
