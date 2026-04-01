# Behavior Pipeline & Middleware Guide

This document describes the **Phase 2 actor loop refactor** — the complete migration of
`StatefulHandlerActor`'s message-processing loop to a composable Roux `Effect` pipeline.

---

## Overview

Before Phase 2, each message was processed by calling `handler.receive()` directly and
running the returned `Effect<E, State>` — a thin imperative wrapper with no lifecycle
control and no composable cross-cutting concerns.

After Phase 2, every message-processing step is described by an **`ActorBehavior`** that
returns `Effect<E, LoopStep<State>>`. A `LoopStep` carries both the new state *and* a
lifecycle directive (continue, stop, or restart). Cross-cutting concerns are added by
stacking **`BehaviorMiddleware`** instances around the base behavior.

```
message ──► [outermost middleware] ──► … ──► [innermost middleware] ──► base behavior
                                                                         (handler.receive)
            ◄──────────────────────────── Effect<E, LoopStep<State>> ◄──
```

---

## Core Abstractions

### `LoopStep<State>` — lifecycle directive

```java
public sealed interface LoopStep<State>
        permits LoopStep.Continue, LoopStep.Stop, LoopStep.Restart {

    State state();   // new actor state after this step

    record Continue<State>(State state) implements LoopStep<State> {}
    record Stop<State>   (State state) implements LoopStep<State> {}
    record Restart<State>(State state) implements LoopStep<State> {}

    // factory helpers
    static <S> LoopStep<S> continue_(S state) { return new Continue<>(state); }
    static <S> LoopStep<S> stop(S state)       { return new Stop<>(state);    }
    static <S> LoopStep<S> restart(S state)    { return new Restart<>(state); }

    // predicates
    default boolean isContinue() { return this instanceof Continue; }
    default boolean isStop()     { return this instanceof Stop;     }
    default boolean isRestart()  { return this instanceof Restart;  }
}
```

| Variant | Actor effect |
|---------|-------------|
| `Continue` | Update state, keep running |
| `Stop`     | Update state, gracefully stop the actor |
| `Restart`  | Update state, restart the actor (re-runs `preStart`) |

### `ActorBehavior<E, State, Message>` — one processing step

```java
@FunctionalInterface
public interface ActorBehavior<E extends Throwable, State, Message> {

    Effect<E, LoopStep<State>> step(Message message, State state, ActorContext context);

    /** Fluent composition helper */
    default ActorBehavior<E, State, Message> withMiddleware(
            BehaviorMiddleware<E, State, Message> middleware) {
        return middleware.wrap(this);
    }
}
```

### `BehaviorMiddleware<E, State, Message>` — decorator

```java
@FunctionalInterface
public interface BehaviorMiddleware<E extends Throwable, State, Message> {
    ActorBehavior<E, State, Message> wrap(ActorBehavior<E, State, Message> next);
}
```

---

## Composing a Pipeline

### Via `StatefulActorBuilder.withMiddleware()`

```java
Pid pid = system.statefulActorOf(MyHandler.class, initialState)
    .withMiddleware(new LoggingMiddleware<>("order-processor"))
    .withMiddleware(new MetricsMiddleware<>("order-processor"))
    .withMiddleware(RetryMiddleware.withExponentialBackoff(3, Duration.ofMillis(50)))
    .spawn();
```

Middlewares are applied **in addition order**:

- The *first* added middleware is the innermost wrapper (closest to `handler.receive`).
- The *last* added middleware is the outermost (executed first, finishes last).

Execution order for the example above:

```
message → RetryMiddleware → MetricsMiddleware → LoggingMiddleware → handler.receive()
```

### Via `ActorBehavior.withMiddleware()` (direct composition)

```java
ActorBehavior<RuntimeException, Integer, String> pipeline =
    baseBehavior
        .withMiddleware(new LoggingMiddleware<>(logger))
        .withMiddleware(metrics);
```

---

## Built-In Supervision Strategies

`SupervisionStrategies` provides factory methods that wrap an existing `ActorBehavior`
and intercept errors produced by the Roux `Effect`. These can be used as middleware or
composed directly.

| Strategy | Error handling |
|----------|---------------|
| `withResume(behavior)` | Swallows the error, returns `Continue` with the *current* state |
| `withRestart(behavior, initialState)` | Swallows the error, returns `Restart` with the *initial* state |
| `withStop(behavior)` | Swallows the error, returns `Stop` with the *current* state |
| `withDecision(behavior, fn)` | Calls a `BiFunction<E, State, LoopStep<State>>` to decide per-exception |

### Example — per-exception decision

```java
ActorBehavior<Exception, Integer, String> supervised =
    SupervisionStrategies.withDecision(baseBehavior, (err, state) ->
        err instanceof java.io.IOException
            ? LoopStep.restart(0)      // transient: restart from clean state
            : LoopStep.stop(state));   // permanent: stop the actor
```

### Using supervision as middleware

```java
Integer initialState = 0;
Pid pid = system.statefulActorOf(MyHandler.class, initialState)
    .withMiddleware(
        next -> SupervisionStrategies.withDecision(next, (err, state) ->
            err instanceof TransientException
                ? LoopStep.restart(initialState)
                : LoopStep.stop(state)))
    .spawn();
```

---

## Built-In Middlewares

### `LoggingMiddleware`

Logs `RECV`, `DONE`, and `ERROR` events around each step using SLF4J.

```java
// From an actor name (creates a dedicated logger)
new LoggingMiddleware<>("payment-processor")

// From an existing logger
new LoggingMiddleware<>(LoggerFactory.getLogger(MyHandler.class))
```

Log output:

```
[payment-processor] RECV msg=ProcessPayment{id=42}
[payment-processor] DONE step=Continue state=PaymentState{…}
```

### `MetricsMiddleware`

Records in-process counters and optionally emits `StepEvent` records to an external sink.

```java
MetricsMiddleware<RuntimeException, MyState, MyMsg> metrics =
    new MetricsMiddleware<>("my-actor");

// Optionally pass a Consumer<StepEvent> for external export
MetricsMiddleware<RuntimeException, MyState, MyMsg> metricsWithSink =
    new MetricsMiddleware<>("my-actor", event -> myMetricsSystem.record(event));
```

Access live counters via `metrics.handle()`:

```java
MetricsMiddleware.MetricsHandle handle = metrics.handle();
handle.processedCount();   // messages processed successfully
handle.errorCount();       // messages that produced an Effect failure
handle.stopCount();        // steps that returned LoopStep.Stop
handle.restartCount();     // steps that returned LoopStep.Restart
handle.totalNanos();       // total wall-clock time across all steps
handle.averageLatencyNanos(); // totalNanos / processedCount
```

`StepEvent` record fields: `actorId`, `messageClass`, `outcome` (`"Continue"`, `"Stop"`,
`"Restart"`, `"Error"`), `latencyNanos`, `timestamp`.

### `RetryMiddleware`

Retries failed effects up to a configurable maximum, with optional exponential backoff
and a predicate to filter which errors should be retried.

```java
// Simple: up to 3 attempts, no delay
new RetryMiddleware<>(3)

// With exponential backoff: 3 attempts, starting at 50 ms, then 100 ms
RetryMiddleware.withExponentialBackoff(3, Duration.ofMillis(50))

// Only retry IOException (give up immediately for other errors)
RetryMiddleware.withPredicate(3, err -> err instanceof java.io.IOException)
```

**Implementation note**: delays use `Thread.sleep()` on the actor's virtual thread, which
suspends the thread without blocking a platform thread.

### `DeadLetterMiddleware`

Taps into failures and forwards the message, actor ID, error, and timestamp to a handler.
The error is **not swallowed** — it propagates up the pipeline after the tap.

```java
// Custom consumer (e.g. persist to a dead-letter queue)
List<DeadLetterMiddleware.DeadLetter<MyMsg>> dlq = new ArrayList<>();
new DeadLetterMiddleware<>(dlq::add)

// Forward to a dedicated actor
new DeadLetterMiddleware<>(DeadLetterMiddleware.toActor(deadLetterPid))

// Log to SLF4J
new DeadLetterMiddleware<>(DeadLetterMiddleware.toLogger())
```

`DeadLetter` record fields: `actorId`, `message`, `error`, `timestamp`.

---

## End-to-End Example

```java
// Message types
sealed interface OrderMsg {}
record ProcessOrder(String orderId, double amount) implements OrderMsg {}
record CancelOrder(String orderId)                 implements OrderMsg {}

// Handler — returns Effect<E, State>; LoopStep is added automatically
public class OrderHandler implements StatefulHandler<Exception, OrderState, OrderMsg> {
    @Override
    public Effect<Exception, OrderState> receive(OrderMsg msg, OrderState state, ActorContext ctx) {
        return switch (msg) {
            case ProcessOrder o -> Effect.succeed(state.withOrder(o.orderId(), o.amount()));
            case CancelOrder  c -> Effect.succeed(state.withCancelled(c.orderId()));
        };
    }
}

// Dead-letter queue
List<DeadLetterMiddleware.DeadLetter<OrderMsg>> dlq = new ArrayList<>();

// Metrics handle (retained for monitoring)
var metrics = new MetricsMiddleware<Exception, OrderState, OrderMsg>("orders");

// Spawn with full pipeline
OrderState initial = OrderState.empty();
Pid orders = system.statefulActorOf(new OrderHandler(), OrderHandler.class, initial)
    // innermost: log individual steps
    .withMiddleware(new LoggingMiddleware<>("orders"))
    // next: collect metrics
    .withMiddleware(metrics)
    // next: retry transient failures up to 3×
    .withMiddleware(RetryMiddleware.withExponentialBackoff(3, Duration.ofMillis(50)))
    // outermost: capture irrecoverable failures
    .withMiddleware(new DeadLetterMiddleware<>(dlq::add))
    .spawn();

// Later — query counters
System.out.println("processed: " + metrics.handle().processedCount());
System.out.println("errors:    " + metrics.handle().errorCount());
System.out.println("dlq size:  " + dlq.size());
```

Execution order per message:
```
message → DeadLetterMiddleware → RetryMiddleware → MetricsMiddleware → LoggingMiddleware → handler
```

---

## How `StatefulHandler.receive()` becomes an `ActorBehavior`

`StatefulHandler.receive()` returns `Effect<E, State>`. The internal pipeline wraps this
automatically as the *base behavior*:

```java
// Built inside StatefulHandlerActor
ActorBehavior<E, State, Message> base =
    (msg, state, ctx) -> handler.receive(msg, state, ctx).map(LoopStep::continue_);
```

This means:
- Handlers that want to **stop** can call `ctx.stop()` inside the effect, which calls
  `ActorContext.stop()` on the actor directly.
- Handlers that need explicit `Stop` or `Restart` semantics should use
  `SupervisionStrategies` in the middleware stack rather than returning special values
  from `receive()`.

---

## Relation to the Legacy `SupervisionStrategy` Enum

The system still supports the per-actor `SupervisionStrategy` enum configured via
`actor.withSupervisionStrategy(strategy)`. This is the *system-level* supervision that
kicks in when an uncaught exception propagates past all middleware. It is independent of
and orthogonal to the functional supervision provided by `SupervisionStrategies` static
helpers.

| | `SupervisionStrategy` enum | `SupervisionStrategies` static helpers |
|---|---|---|
| **Scope** | System-level; acts on raw exceptions | Effect-level; acts on `Effect` failures |
| **Location** | `actor.withSupervisionStrategy(...)` | `BehaviorMiddleware` in the pipeline |
| **Granularity** | Per-actor | Per-middleware, per-exception, per-step |
| **Composability** | Single strategy | Stackable with other middleware |

For `StatefulHandler`-based actors, the Effect-level supervision is the recommended
approach as it is fully composable and testable without a running actor system.

---

## Testing Middleware Without an ActorSystem

All middleware runs synchronously on Roux effects and can be tested with
`DefaultEffectRuntime.create()` — no actor system or threads required:

```java
import com.cajunsystems.roux.runtime.DefaultEffectRuntime;

ActorContext ctx = Mockito.mock(ActorContext.class);
Mockito.when(ctx.getActorId()).thenReturn("test");

ActorBehavior<RuntimeException, Integer, String> base =
    (msg, state, c) -> Effect.succeed(LoopStep.continue_(state + 1));

ActorBehavior<RuntimeException, Integer, String> pipeline =
    base.withMiddleware(new LoggingMiddleware<>("test"))
        .withMiddleware(new MetricsMiddleware<>("test"));

LoopStep<Integer> result = DefaultEffectRuntime.create()
    .unsafeRun(pipeline.step("hello", 0, ctx));

assertEquals(1, result.state());
assertTrue(result.isContinue());
```

See `BehaviorMiddlewareTest` and `SupervisionStrategiesTest` in the test sources for
comprehensive examples.

---

## Package Layout

```
com.cajunsystems.loop/
├── LoopStep.java              # Sealed interface: Continue / Stop / Restart
├── ActorBehavior.java         # @FunctionalInterface for one processing step
├── BehaviorMiddleware.java    # @FunctionalInterface for middleware decorators
├── SupervisionStrategies.java # Static helpers: withResume, withRestart, withStop, withDecision
└── middleware/
    ├── LoggingMiddleware.java      # SLF4J logging per step
    ├── MetricsMiddleware.java      # In-process counters + optional event sink
    ├── RetryMiddleware.java        # Configurable retry with backoff and predicate
    └── DeadLetterMiddleware.java   # Tap failures to a handler / actor / logger
```
