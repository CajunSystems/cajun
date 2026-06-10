package com.cajunsystems.functional;

import com.cajunsystems.ActorContext;
import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.handler.Handler;
import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.capability.Capability;
import com.cajunsystems.roux.capability.CapabilityHandler;

import java.util.function.Function;

/**
 * Fluent builder for spawning actors whose message handling is expressed as
 * Roux {@link Effect} pipelines.
 *
 * <p>For each message received, the configured {@code handler} function produces
 * an {@link Effect}, which is executed via {@link ActorEffectRuntime} — dispatched
 * through the actor system's executor rather than a fresh virtual-thread pool.
 * The result value {@code A} is discarded after execution.
 *
 * <p>Basic usage:
 * <pre>{@code
 * Pid pid = new EffectActorBuilder<>(system,
 *     (String msg) -> Effect.suspend(() -> {
 *         System.out.println("Got: " + msg);
 *         return Unit.unit();
 *     }))
 *     .withId("my-actor")
 *     .spawn();
 *
 * pid.tell("hello");
 * }</pre>
 *
 * <p>With capabilities:
 * <pre>{@code
 * Pid pid = new EffectActorBuilder<>(system,
 *     (String msg) -> Effect.from(new LogCapability.Info("got: " + msg)))
 *     .withCapabilityHandler(new ConsoleLogHandler().widen())
 *     .spawn();
 * }</pre>
 *
 * @param <E>       the error type of the effects
 * @param <Message> the actor's message type
 * @param <A>       the result type of the effects (discarded after execution)
 */
public class EffectActorBuilder<E extends Throwable, Message, A> {

    private final ActorSystem system;
    private final ActorEffectRuntime runtime;
    private final Function<Message, Effect<E, A>> handler;
    private CapabilityHandler<Capability<?>> capabilityHandler;
    private String actorId;

    /**
     * Creates a builder for an effect-based actor.
     *
     * @param system  the actor system to spawn into
     * @param handler maps each incoming message to an {@link Effect} to execute
     */
    public EffectActorBuilder(ActorSystem system, Function<Message, Effect<E, A>> handler) {
        this.system = system;
        this.runtime = new ActorEffectRuntime(system);
        this.handler = handler;
    }

    /**
     * Sets the actor's ID. If not called, the system generates a unique ID.
     */
    public EffectActorBuilder<E, Message, A> withId(String actorId) {
        this.actorId = actorId;
        return this;
    }

    /**
     * Sets the {@link CapabilityHandler} used when effects contain
     * {@link com.cajunsystems.roux.Effect#from(Capability)} calls.
     *
     * <p>Pass a widened handler: {@code myHandler.widen()} or
     * {@code CapabilityHandler.compose(myHandler)}.
     */
    public EffectActorBuilder<E, Message, A> withCapabilityHandler(
            CapabilityHandler<Capability<?>> capabilityHandler) {
        this.capabilityHandler = capabilityHandler;
        return this;
    }

    /**
     * Spawns the actor and returns its {@link Pid}.
     */
    public Pid spawn() {
        final ActorEffectRuntime rt = this.runtime;
        final Function<Message, Effect<E, A>> h = this.handler;
        final CapabilityHandler<Capability<?>> cap = this.capabilityHandler;

        Handler<Message> wrappedHandler = new Handler<>() {
            @Override
            public void receive(Message message, ActorContext context) {
                Effect<E, A> effect = h.apply(message);
                try {
                    if (cap != null) {
                        rt.unsafeRunWithHandler(effect, cap);
                    } else {
                        rt.unsafeRun(effect);
                    }
                } catch (RuntimeException re) {
                    throw re;
                } catch (Throwable t) {
                    throw new RuntimeException(
                            "Effect execution failed for message: " + message, t);
                }
            }

            @Override
            public void postStop(ActorContext context) {
                rt.close();
            }
        };

        var builder = system.actorOf(wrappedHandler);
        if (actorId != null) {
            builder = builder.withId(actorId);
        }
        return builder.spawn();
    }
}
