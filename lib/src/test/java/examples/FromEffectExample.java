package examples;

import com.cajunsystems.ActorContext;
import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.handler.EffectBehavior;
import com.cajunsystems.loop.middleware.LoggingMiddleware;
import com.cajunsystems.loop.middleware.MetricsMiddleware;
import com.cajunsystems.roux.Effect;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Demonstrates {@link com.cajunsystems.ActorSystem#fromEffect} — creating
 * stateful actors from Roux Effect lambdas without a named handler class.
 *
 * <p>Key points:
 * <ul>
 *   <li>Effect-based actors are <em>still full actors</em>: they have a mailbox,
 *       run on a virtual thread, support backpressure and persistence, and can
 *       participate in supervision hierarchies.</li>
 *   <li>{@code fromEffect} returns the same {@code StatefulActorBuilder} as
 *       {@code statefulActorOf} — all builder options (middleware, persistence,
 *       backpressure, ID strategies, ...) are available.</li>
 *   <li>Use {@link com.cajunsystems.handler.EffectBehavior#asHandler()} to promote
 *       a lambda to a full {@code StatefulHandler} when you need lifecycle hooks.</li>
 * </ul>
 */
public class FromEffectExample {

    // -------------------------------------------------------------------------
    // Shared message types
    // -------------------------------------------------------------------------

    sealed interface CounterMsg permits Increment, Decrement, Reset, GetCount {}
    record Increment(int amount) implements CounterMsg {}
    record Decrement(int amount) implements CounterMsg {}
    record Reset()               implements CounterMsg {}
    record GetCount(Pid replyTo) implements CounterMsg {}

    // -------------------------------------------------------------------------
    // Example 1 — inline lambda, minimal syntax
    // -------------------------------------------------------------------------

    static void inlineLambdaExample(ActorSystem system) throws InterruptedException {
        System.out.println("\n=== Example 1: inline lambda ===");

        AtomicInteger received = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(1);

        Pid printer = system.actorOf((Object msg, ActorContext ctx) -> {
            System.out.println("Counter value: " + msg);
            received.set((int) msg);
            latch.countDown();
        }).withId("printer").spawn();

        // fromEffect accepts an EffectBehavior lambda — no class needed
        Pid counter = system.fromEffect(
            (CounterMsg msg, Integer count, ActorContext ctx) -> switch (msg) {
                case Increment i -> Effect.succeed(count + i.amount());
                case Decrement d -> Effect.succeed(count - d.amount());
                case Reset r     -> Effect.succeed(0);
                case GetCount g  -> Effect.suspend(() -> {
                    ctx.tell(g.replyTo(), count);
                    return count;
                });
            },
            0
        ).withId("counter-inline").spawn();

        // Effect-based actors have a mailbox — messages are queued and processed
        // one at a time on a dedicated virtual thread, just like any other actor.
        counter.tell(new Increment(10));
        counter.tell(new Increment(5));
        counter.tell(new Decrement(3));
        counter.tell(new GetCount(printer));

        latch.await(5, TimeUnit.SECONDS);
        System.out.println("Final count: " + received.get()); // 12
    }

    // -------------------------------------------------------------------------
    // Example 2 — named EffectBehavior variable, reused across actors
    // -------------------------------------------------------------------------

    static void namedBehaviorExample(ActorSystem system) throws InterruptedException {
        System.out.println("\n=== Example 2: named EffectBehavior, reused ===");

        // Define behavior once, spawn multiple actors from it
        EffectBehavior<RuntimeException, Integer, CounterMsg> counterBehavior =
            (msg, count, ctx) -> switch (msg) {
                case Increment i -> Effect.succeed(count + i.amount());
                case Decrement d -> Effect.succeed(count - d.amount());
                case Reset r     -> Effect.succeed(0);
                case GetCount g  -> Effect.suspend(() -> {
                    ctx.tell(g.replyTo(), count);
                    return count;
                });
            };

        // Each spawned actor has its own mailbox and independent state —
        // they share the behavior definition, not any runtime state.
        Pid counterA = system.fromEffect(counterBehavior, 0).withId("counter-a").spawn();
        Pid counterB = system.fromEffect(counterBehavior, 100).withId("counter-b").spawn();

        CountDownLatch latch = new CountDownLatch(2);
        AtomicInteger resultA = new AtomicInteger();
        AtomicInteger resultB = new AtomicInteger();

        Pid collectorA = system.actorOf((Object msg, ActorContext ctx) -> {
            resultA.set((int) msg);
            latch.countDown();
        }).withId("col-a").spawn();

        Pid collectorB = system.actorOf((Object msg, ActorContext ctx) -> {
            resultB.set((int) msg);
            latch.countDown();
        }).withId("col-b").spawn();

        counterA.tell(new Increment(5));
        counterB.tell(new Increment(5));
        counterA.tell(new GetCount(collectorA));
        counterB.tell(new GetCount(collectorB));

        latch.await(5, TimeUnit.SECONDS);
        System.out.println("Counter A: " + resultA.get()); // 5
        System.out.println("Counter B: " + resultB.get()); // 105
    }

    // -------------------------------------------------------------------------
    // Example 3 — fromEffect + middleware (full builder API available)
    // -------------------------------------------------------------------------

    static void withMiddlewareExample(ActorSystem system) throws InterruptedException {
        System.out.println("\n=== Example 3: fromEffect + middleware ===");

        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger result = new AtomicInteger();

        Pid observer = system.actorOf((Object msg, ActorContext ctx) -> {
            result.set((int) msg);
            latch.countDown();
        }).withId("obs-mw").spawn();

        // fromEffect returns StatefulActorBuilder — withMiddleware is available
        Pid counter = system.<RuntimeException, Integer, CounterMsg>fromEffect(
            (msg, count, ctx) -> switch (msg) {
                case Increment i -> Effect.succeed(count + i.amount());
                case Decrement d -> Effect.succeed(count - d.amount());
                case Reset r     -> Effect.succeed(0);
                case GetCount g  -> Effect.suspend(() -> {
                    ctx.tell(g.replyTo(), count);
                    return count;
                });
            },
            0
        )
        .withId("counter-mw")
        .withMiddleware(new LoggingMiddleware<>("counter-mw"))
        .withMiddleware(new MetricsMiddleware<>("counter-mw"))
        .spawn();

        counter.tell(new Increment(7));
        counter.tell(new GetCount(observer));

        latch.await(5, TimeUnit.SECONDS);
        System.out.println("Counter with middleware: " + result.get()); // 7
    }

    // -------------------------------------------------------------------------
    // Example 4 — asHandler() promotion
    // -------------------------------------------------------------------------

    static void asHandlerPromotionExample(ActorSystem system) throws InterruptedException {
        System.out.println("\n=== Example 4: asHandler() promotion ===");

        // Start as a lambda
        EffectBehavior<RuntimeException, Integer, CounterMsg> behavior =
            (msg, count, ctx) -> switch (msg) {
                case Increment i -> Effect.succeed(count + i.amount());
                case Decrement d -> Effect.succeed(count - d.amount());
                case Reset r     -> Effect.succeed(0);
                case GetCount g  -> Effect.suspend(() -> {
                    ctx.tell(g.replyTo(), count);
                    return count;
                });
            };

        // Promote to StatefulHandler to add lifecycle hooks
        var handler = new com.cajunsystems.handler.StatefulHandler<RuntimeException, Integer, CounterMsg>() {
            @Override
            public com.cajunsystems.roux.Effect<RuntimeException, Integer> receive(
                    CounterMsg msg, Integer state, ActorContext ctx) {
                return behavior.receive(msg, state, ctx);
            }

            @Override
            public Integer preStart(Integer state, ActorContext ctx) {
                System.out.println("Counter starting with state: " + state);
                return state;
            }

            @Override
            public void postStop(Integer state, ActorContext ctx) {
                System.out.println("Counter stopped at: " + state);
            }
        };

        // Or more concisely, use asHandler() for delegation without extra hooks:
        // StatefulHandler<...> simple = behavior.asHandler();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger result = new AtomicInteger();

        Pid observer = system.actorOf((Object msg, ActorContext ctx) -> {
            result.set((int) msg);
            latch.countDown();
        }).withId("obs-promoted").spawn();

        Pid counter = system.statefulActorOf(handler, 0)
            .withId("counter-promoted")
            .spawn();

        counter.tell(new Increment(99));
        counter.tell(new GetCount(observer));

        latch.await(5, TimeUnit.SECONDS);
        System.out.println("Promoted handler result: " + result.get()); // 99
    }

    // -------------------------------------------------------------------------
    // main
    // -------------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        ActorSystem system = new ActorSystem();
        try {
            inlineLambdaExample(system);
            namedBehaviorExample(system);
            withMiddlewareExample(system);
            asHandlerPromotionExample(system);
        } finally {
            system.shutdown();
        }
    }
}
