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

## Full Example: Counter Actor

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
