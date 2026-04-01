# Roux Effect Reference

Cajun uses the [**Roux**](https://github.com/CajunSystems/roux) library as its Effect type.
Roux provides a lightweight, 2-param monadic effect `Effect<E extends Throwable, A>` designed
for Java 21+ virtual threads. This document is a practical reference for using Roux Effects
inside Cajun actors.

> **Full Roux documentation**: https://github.com/CajunSystems/roux

---

## Dependency

Roux is a transitive dependency of Cajun — you do **not** need to add it separately.
It is available as `com.cajunsystems:roux:0.2.2` in the Cajun BOM.

---

## Type Signature

```
Effect<E extends Throwable, A>
  │                          └── the success value type
  └── the error type (must extend Throwable)
```

`E` is the type of error the effect can fail with. In most Cajun actors, `E` is
`RuntimeException`. For actors that need to distinguish checked exceptions,
any `Throwable` subtype is valid.

---

## Factory Methods

| Method | Description |
|--------|-------------|
| `Effect.succeed(value)` | An effect that always succeeds with `value` |
| `Effect.fail(error)` | An effect that always fails with `error` |
| `Effect.suspend(() -> value)` | Defer a computation (lazily evaluated) |
| `Effect.unit()` | Succeed with `Unit` (void equivalent) |
| `Effect.runnable(runnable)` | Wrap a `Runnable` (succeeds with `Unit`) |
| `Effect.sleep(Duration)` | Suspend the virtual thread for a duration |
| `Effect.when(condition, effect)` | Run `effect` only if `condition` is `true` |
| `Effect.unless(condition, effect)` | Run `effect` only if `condition` is `false` |

```java
// Always succeed
Effect<RuntimeException, Integer> one = Effect.succeed(1);

// Always fail
Effect<RuntimeException, Integer> boom = Effect.fail(new RuntimeException("oops"));

// Defer (safe to do I/O here — runs on a virtual thread)
Effect<RuntimeException, String> deferred = Effect.suspend(() -> fetchFromDatabase());

// Succeed with no value
Effect<RuntimeException, Unit> log = Effect.runnable(() -> logger.info("processed"));
```

---

## Transforming Values — `map` and `flatMap`

```java
// map: transform the success value
Effect<RuntimeException, String> asString =
    Effect.<RuntimeException, Integer>succeed(42)
        .map(n -> "value=" + n);

// flatMap: chain effects
Effect<RuntimeException, String> chained =
    Effect.<RuntimeException, Integer>succeed(1)
        .flatMap(n -> Effect.succeed("count=" + (n + 1)));
```

---

## Error Handling — `catchAll`, `orElse`, `attempt`

```java
// catchAll: recover from any error
Effect<RuntimeException, String> recovered =
    Effect.<RuntimeException, String>fail(new RuntimeException("down"))
        .catchAll(err -> Effect.succeed("fallback"));

// orElse: use a different effect on failure
Effect<RuntimeException, String> withFallback = primary.orElse(secondary);

// attempt: convert failure into Either<E, A> so you can inspect it
Effect<Throwable, Either<RuntimeException, String>> inspectable =
    riskyEffect.attempt();
```

---

## Side Effects — `tap` and `tapError`

`tap` and `tapError` let you observe values without changing them.
The effect chain continues unchanged — errors are not swallowed.

```java
Effect<RuntimeException, OrderState> processed =
    computeOrder()
        .tap(state -> logger.info("order processed: {}", state))
        .tapError(err -> metrics.recordError(err));
```

---

## Retrying

```java
// Simple: retry up to 3 times immediately
effect.retry(3);

// With delay: exponential backoff starting at 100 ms
effect.retryWithDelay(3, Duration.ofMillis(100));

// Custom policy (implement RetryPolicy)
effect.retry(myPolicy);
```

---

## Concurrency — `fork` and `zipPar`

```java
// Fork: run an effect in the background and get a Fiber handle
Effect<Throwable, Fiber<RuntimeException, String>> fiber = effect.fork();

// zipPar: run two effects in parallel and combine results
Effect<Throwable, Integer> sum =
    Effect.<RuntimeException, Integer>succeed(10)
        .zipPar(Effect.succeed(20), Integer::sum);
```

---

## Timeouts

```java
Effect<Throwable, String> withTimeout =
    slowEffect.timeout(Duration.ofSeconds(5));
```

---

## Running Effects

In Cajun actors you **never call `unsafeRun()` yourself** — the actor runtime does it.
Return `Effect<E, State>` from `StatefulHandler.receive()` and the runtime handles
execution on the actor's virtual thread.

For tests or standalone code outside an actor, use:

```java
import com.cajunsystems.roux.runtime.DefaultEffectRuntime;

try (DefaultEffectRuntime runtime = DefaultEffectRuntime.create()) {
    String result = runtime.unsafeRun(myEffect);
}
```

`DefaultEffectRuntime` implements `AutoCloseable` — always use it in a try-with-resources
block to avoid leaking its internal executor.

---

## Creating Actors from Effects — `fromEffect`

For simple actors where a named class would be ceremony, use
`ActorSystem.fromEffect()` with an `EffectBehavior` lambda:

```java
import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.roux.Effect;

sealed interface CounterMsg permits Increment, Decrement, Reset, GetCount {}
record Increment(int amount)  implements CounterMsg {}
record Decrement(int amount)  implements CounterMsg {}
record Reset()                 implements CounterMsg {}
record GetCount(Pid replyTo)  implements CounterMsg {}

ActorSystem system = new ActorSystem();

Pid counter = system.fromEffect(
    (CounterMsg msg, Integer count, ActorContext ctx) -> switch (msg) {
        case Increment i -> Effect.succeed(count + i.amount());
        case Decrement d -> Effect.succeed(count - d.amount());
        case Reset r     -> Effect.succeed(0);
        case GetCount gc -> Effect.suspend(() -> {
            ctx.tell(gc.replyTo(), count);
            return count; // state unchanged
        });
    },
    0   // initial state
).withId("counter").spawn();
```

`fromEffect` returns the same `StatefulActorBuilder` as `statefulActorOf`, so
you can chain `.withPersistence()`, `.withMiddleware()`, `.withBackpressureConfig()`,
etc. on it.

> **Effect-based actors are still full actors.**
> A `fromEffect` actor has its own mailbox, runs on a dedicated virtual thread,
> processes messages one at a time in arrival order, and participates in backpressure,
> supervision, and persistence exactly like any other actor. The only difference is
> that the behavior is expressed as a Roux Effect lambda instead of a named class.

**When to use `fromEffect` vs `statefulActorOf`**

| Situation | Use |
|-----------|-----|
| Simple behavior, no lifecycle hooks | `fromEffect` + lambda |
| Need `preStart` / `postStop` / `onError` | `statefulActorOf` + `StatefulHandler` class |
| Want to reuse handler across tests | `statefulActorOf` + named class |
| Prototyping / exploratory | `fromEffect` + lambda |

The `EffectBehavior` functional interface (`com.cajunsystems.handler.EffectBehavior`)
is the lambda target. If you later need to promote it to a full handler, call
`.asHandler()` on it to get a `StatefulHandler` instance.

---

## Stateless Actors from Effects — `fromEffect(StatelessEffectBehavior)`

When an actor carries **no state** between messages (it just executes side effects),
use the stateless overload of `fromEffect`:

```java
import com.cajunsystems.handler.StatelessEffectBehavior;
import com.cajunsystems.roux.Effect;

sealed interface LogMsg permits Log, Flush {}
record Log(String text)  implements LogMsg {}
record Flush()           implements LogMsg {}

Pid logger = system.fromEffect(
    (LogMsg msg, ActorContext ctx) -> switch (msg) {
        case Log(var text) -> Effect.runnable(() -> System.out.println("[LOG] " + text));
        case Flush()       -> Effect.runnable(() -> System.out.flush());
    }
).withId("logger").spawn();
```

The returned builder is a full `ActorBuilder`, so you can chain `.withId()`,
`.withBackpressureConfig()`, `.withThreadPoolFactory()`, etc.

The Effect is executed **synchronously** on the actor's message-processing thread
for each incoming message; its produced value is discarded.

**When to use stateless `fromEffect`**

| Situation | Use |
|-----------|-----|
| Actor only performs I/O / side effects, no state needed | `fromEffect(StatelessEffectBehavior)` |
| Actor needs state between messages | `fromEffect(EffectBehavior, initialState)` |
| Need lifecycle hooks | `actorOf(Handler)` / `statefulActorOf(StatefulHandler, state)` |

The `StatelessEffectBehavior` functional interface (`com.cajunsystems.handler.StatelessEffectBehavior`)
is the lambda target. You can also call `.asHandler()` on it to get a `Handler` instance
directly usable with `actorOf(handler)`.

### Capabilities in Stateless Effects

Roux [capabilities](https://github.com/CajunSystems/roux) let you inject contextual values into
effects (e.g. a test clock, a random source, or any user-defined service). Pass a
`CapabilityHandler` as a second argument to `fromEffect`:

```java
import com.cajunsystems.roux.capability.Capability;
import com.cajunsystems.roux.capability.CapabilityHandler;

// 1. Define a capability
record MyClock() implements Capability<Long> {}

// 2. Build a handler that resolves it
CapabilityHandler<Capability<?>> capHandler =
    CapabilityHandler.builder()
        .on(MyClock.class, cap -> System.currentTimeMillis())
        .build();

// 3. Use the capability inside the effect
Pid actor = system.<RuntimeException, LogMsg>fromEffect(
    (msg, ctx) -> switch (msg) {
        case Log(var text) -> {
            Effect<RuntimeException, Long> ts = Effect.from(new MyClock());
            yield ts.flatMap(now -> Effect.runnable(() ->
                System.out.println("[" + now + "] " + text)));
        }
        case Flush() -> Effect.unit();
    },
    capHandler  // <-- capability handler injected here
).withId("timed-logger").spawn();
```

In tests you can supply a deterministic handler:
```java
CapabilityHandler<Capability<?>> testHandler =
    CapabilityHandler.builder()
        .on(MyClock.class, cap -> 1_000_000L)  // fixed timestamp
        .build();
```

You can also inject capabilities into the stateless `asHandler()` adapter directly,
without going through `ActorSystem.fromEffect`:

```java
StatelessEffectBehavior<RuntimeException, LogMsg> behavior = (msg, ctx) -> ...;

// Uses default runtime (no capability handler)
Handler<LogMsg> plain    = behavior.asHandler();

// Uses the supplied capability handler
Handler<LogMsg> withCaps = behavior.asHandler(capHandler);

Pid actor = system.actorOf(withCaps).withId("capped").spawn();
```

---

## Full Example: Counter Actor (class-based)

```java
import com.cajunsystems.ActorContext;
import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.handler.StatefulHandler;
import com.cajunsystems.roux.Effect;

// Message types
sealed interface CounterMsg permits Increment, Decrement, Reset, GetCount {}
record Increment(int amount)   implements CounterMsg {}
record Decrement(int amount)   implements CounterMsg {}
record Reset()                  implements CounterMsg {}
record GetCount(Pid replyTo)   implements CounterMsg {}

// Handler — return Roux Effect<E, State> from receive()
public class CounterHandler implements StatefulHandler<RuntimeException, Integer, CounterMsg> {

    @Override
    public Effect<RuntimeException, Integer> receive(
            CounterMsg msg, Integer count, ActorContext ctx) {
        return switch (msg) {
            case Increment i -> Effect.succeed(count + i.amount());
            case Decrement d -> Effect.succeed(count - d.amount());
            case Reset r     -> Effect.succeed(0);
            case GetCount gc -> Effect.suspend(() -> {
                ctx.tell(gc.replyTo(), count);
                return count; // state unchanged
            });
        };
    }
}

// Spawn
ActorSystem system = new ActorSystem();
Pid counter = system.statefulActorOf(new CounterHandler(), 0)
    .withId("counter")
    .spawn();

system.tell(counter, new Increment(5));
system.tell(counter, new Increment(3));
```

---

## Effect in Middleware

When using [BehaviorMiddleware](behavior_middleware_guide.md), the pipeline works with
`Effect<E, LoopStep<State>>`. The base behavior wraps `handler.receive()` automatically:

```java
// Base behavior (assembled automatically by StatefulHandlerActor):
(msg, state, ctx) -> handler.receive(msg, state, ctx).map(LoopStep::continue_)

// Middleware can intercept, transform, or recover:
(next) -> (msg, state, ctx) ->
    next.step(msg, state, ctx)
        .tap(step -> metrics.record(step))
        .catchAll(err -> Effect.succeed(LoopStep.restart(initialState)));
```

---

## Quick API Cheat-Sheet

```
Effect<E, A>
├── Factory
│   ├── succeed(A)                → Effect<E, A>
│   ├── fail(E)                   → Effect<E, A>
│   ├── suspend(() -> A)          → Effect<E, A>
│   ├── unit()                    → Effect<E, Unit>
│   ├── runnable(Runnable)        → Effect<E, Unit>
│   ├── sleep(Duration)           → Effect<E, Unit>
│   ├── when(bool, Effect)        → Effect<E, Unit>
│   └── unless(bool, Effect)      → Effect<E, Unit>
├── Transform
│   ├── .map(A -> B)              → Effect<E, B>
│   ├── .flatMap(A -> Effect<E,B>)→ Effect<E, B>
│   └── .mapError(E -> E2)        → Effect<E2, A>
├── Observe (no-op to the chain)
│   ├── .tap(Consumer<A>)         → Effect<E, A>
│   └── .tapError(Consumer<E>)    → Effect<E, A>
├── Error recovery
│   ├── .catchAll(E -> Effect<E,A>)→ Effect<E, A>
│   ├── .orElse(Effect<E,A>)      → Effect<E, A>
│   └── .attempt()                → Effect<Throwable, Either<E,A>>
├── Retry
│   ├── .retry(int)               → Effect<E, A>
│   └── .retryWithDelay(int, dur) → Effect<Throwable, A>
├── Concurrency
│   ├── .fork()                   → Effect<Throwable, Fiber<E,A>>
│   └── .zipPar(Effect<E,B>, fn)  → Effect<Throwable, C>
└── Timeout
    └── .timeout(Duration)        → Effect<Throwable, A>
```

---

## Links

- **Roux GitHub**: https://github.com/CajunSystems/roux
- **Cajun Behavior Pipeline Guide**: [behavior_middleware_guide.md](behavior_middleware_guide.md)
- **StatefulHandler interface**: [`com.cajunsystems.handler.StatefulHandler`](../lib/src/main/java/com/cajunsystems/handler/StatefulHandler.java)
