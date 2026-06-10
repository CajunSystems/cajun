package com.cajunsystems.functional;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.roux.Effect;

import java.util.function.Function;

/**
 * Static extension-method-style helpers for spawning Roux effect-based actors
 * on an {@link ActorSystem}.
 *
 * <p>Convenience wrappers around {@link EffectActorBuilder}. Use
 * {@link #effectActorOf} when you need to configure the builder further (e.g.,
 * set a capability handler), or the {@code spawnEffectActor} variants for
 * one-liner spawning.
 *
 * <p>Usage:
 * <pre>{@code
 * import static com.cajunsystems.functional.ActorSystemEffectExtensions.*;
 *
 * Pid pid = spawnEffectActor(system, "greeter",
 *     (String msg) -> Effect.suspend(() -> {
 *         System.out.println("Hello, " + msg + "!");
 *         return Unit.unit();
 *     }));
 *
 * pid.tell("world");
 * }</pre>
 */
public final class ActorSystemEffectExtensions {

    private ActorSystemEffectExtensions() {}

    /**
     * Returns an {@link EffectActorBuilder} for the given handler.
     * Use the builder to set a capability handler, actor ID, or other options
     * before calling {@link EffectActorBuilder#spawn()}.
     *
     * @param system  the actor system
     * @param handler maps each message to an {@link Effect} to execute
     * @return a builder for further configuration
     */
    public static <E extends Throwable, Message, A>
    EffectActorBuilder<E, Message, A> effectActorOf(
            ActorSystem system,
            Function<Message, Effect<E, A>> handler) {
        return new EffectActorBuilder<>(system, handler);
    }

    /**
     * Spawns an effect-based actor with a system-generated ID.
     *
     * @param system  the actor system
     * @param handler maps each message to an {@link Effect} to execute
     * @return the {@link Pid} of the spawned actor
     */
    public static <E extends Throwable, Message, A>
    Pid spawnEffectActor(
            ActorSystem system,
            Function<Message, Effect<E, A>> handler) {
        return new EffectActorBuilder<>(system, handler).spawn();
    }

    /**
     * Spawns an effect-based actor with the given ID.
     *
     * @param system   the actor system
     * @param actorId  the actor's ID
     * @param handler  maps each message to an {@link Effect} to execute
     * @return the {@link Pid} of the spawned actor
     */
    public static <E extends Throwable, Message, A>
    Pid spawnEffectActor(
            ActorSystem system,
            String actorId,
            Function<Message, Effect<E, A>> handler) {
        return new EffectActorBuilder<>(system, handler).withId(actorId).spawn();
    }
}
