package com.cajunsystems.handler;

import com.cajunsystems.ActorContext;
import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.runtime.DefaultEffectRuntime;

/**
 * A functional interface describing a <em>stateless</em> actor's message-handling behavior
 * in terms of a Roux {@link Effect}.
 *
 * <p>Use this with {@link com.cajunsystems.ActorSystem#fromEffect(StatelessEffectBehavior)} to
 * define fire-and-forget actors inline, without the ceremony of a named handler class:
 *
 * <pre>{@code
 * Pid logger = system.fromEffect(
 *     (LogMsg msg, ActorContext ctx) -> switch (msg) {
 *         case Log(var text) -> Effect.suspend(() -> {
 *             System.out.println("[LOG] " + text);
 *             return null;
 *         });
 *         case Shutdown()    -> Effect.suspend(() -> { ctx.stop(); return null; });
 *     }
 * ).withId("logger").spawn();
 * }</pre>
 *
 * <p>The actor is <em>stateless</em> — no state is threaded between invocations.
 * The Effect is executed purely for its side effects; its produced value (if any) is
 * discarded.  For actors that need to carry state between messages, use
 * {@link EffectBehavior} with {@link com.cajunsystems.ActorSystem#fromEffect(EffectBehavior, Object)}.
 *
 * <p>For actors that also need lifecycle hooks ({@code preStart}, {@code postStop},
 * {@code onError}), implement {@link Handler} directly and use
 * {@link com.cajunsystems.ActorSystem#actorOf(Handler)} instead.
 *
 * @param <E>       the error type (must extend {@link Throwable})
 * @param <Message> the type of messages this behavior handles
 */
@FunctionalInterface
public interface StatelessEffectBehavior<E extends Throwable, Message> {

    /**
     * Processes a message and returns an effect describing the work to perform.
     * The effect is run for its side effects; any produced value is ignored.
     *
     * @param message the message to process
     * @param context the actor context
     * @return an effect representing the side-effectful computation
     */
    Effect<E, ?> receive(Message message, ActorContext context);

    /**
     * Adapts this behavior to a {@link Handler}, enabling it to be used
     * with {@link com.cajunsystems.builder.ActorBuilder} and all its options
     * (backpressure, middleware, supervision, etc.).
     *
     * <p>A fresh {@link DefaultEffectRuntime#createDirect()} runtime is used to execute each
     * effect synchronously on the actor's calling thread. For I/O-heavy effects that benefit
     * from a custom capability handler, use
     * {@link #asHandler(com.cajunsystems.roux.capability.CapabilityHandler)} instead.
     *
     * @return a {@code Handler} delegating to this behavior
     */
    default Handler<Message> asHandler() {
        DefaultEffectRuntime runtime = DefaultEffectRuntime.createDirect();
        return (msg, ctx) -> {
            Effect<E, ?> effect = receive(msg, ctx);
            if (effect != null) {
                try {
                    runtime.unsafeRun(effect);
                } catch (Throwable t) {
                    throw new RuntimeException("Effect failed", t);
                }
            }
        };
    }

    /**
     * Adapts this behavior to a {@link Handler} that runs each effect with the supplied
     * capability handler, enabling Roux capabilities (e.g. a custom {@code Clock},
     * {@code Random}, or any user-defined capability) to be injected at the actor level.
     *
     * <pre>{@code
     * Pid actor = system.fromEffect(
     *     (MyMsg msg, ActorContext ctx) -> Effect.from(Clock.INSTANCE).flatMap(clock -> ...),
     *     myCapabilityHandler   // handles Clock.INSTANCE → real wall-clock
     * ).withId("my-actor").spawn();
     * }</pre>
     *
     * @param capabilityHandler the handler used to resolve capabilities requested by the effect
     * @return a {@code Handler} delegating to this behavior with the given capability handler
     */
    default Handler<Message> asHandler(
            com.cajunsystems.roux.capability.CapabilityHandler<
                com.cajunsystems.roux.capability.Capability<?>> capabilityHandler) {
        DefaultEffectRuntime runtime = DefaultEffectRuntime.createDirect();
        return (msg, ctx) -> {
            Effect<E, ?> effect = receive(msg, ctx);
            if (effect != null) {
                try {
                    runtime.unsafeRunWithHandler(effect, capabilityHandler);
                } catch (Throwable t) {
                    throw new RuntimeException("Effect failed", t);
                }
            }
        };
    }
}
