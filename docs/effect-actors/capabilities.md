# Capabilities: Declaring and Composing Dependencies

## What Is a Capability?

A **Capability** is a pure data value describing an operation to perform. It says *what* you want
done, not *how* to do it. The implementation lives in a separate `CapabilityHandler`.

```
Capability<R>         →  sealed interface, declares an operation returning R
CapabilityHandler<C>  →  implements handle(C cap): R
```

This separation means you can swap behaviour (e.g. test double vs. production handler) without
changing your effect logic.

---

## The Built-In LogCapability

`LogCapability` ships with Cajun — four variants, all returning `Unit`:

```java
sealed interface LogCapability extends Capability<Unit>
        permits LogCapability.Info, LogCapability.Debug,
                LogCapability.Warn, LogCapability.Error { ... }
```

`ConsoleLogHandler` implements `CapabilityHandler<LogCapability>` and writes to stdout/stderr.
Call `.widen()` to get a `CapabilityHandler<Capability<?>>` compatible with `Effect.generate()`:

```java
CapabilityHandler<Capability<?>> logHandler = new ConsoleLogHandler().widen();

Pid actor = new EffectActorBuilder<>(system,
    (String msg) -> Effect.generate(ctx -> {
        ctx.perform(new LogCapability.Info("received: " + msg));
        // actor logic...
        return Unit.unit();
    }, logHandler)
).withId("my-actor").spawn();
```

---

## Two Ways to Use Capabilities in Effects

| Approach | Method | Handler location |
|----------|--------|-----------------|
| Baked-in | `Effect.generate(ctx -> ..., handler)` | Inside the effect |
| Injected | `Effect.from(cap)` + `.withCapabilityHandler(h)` | At spawn time |

### Approach 1: `Effect.generate()` — Handler Baked In

Use when the handler is fixed per actor. Mix multiple capability types in one block via a
composed handler (see [Composing handlers](#composing-multiple-handlers)):

```java
Pid actor = new EffectActorBuilder<>(system,
    (Request req) -> Effect.generate(ctx -> {
        ctx.perform(new LogCapability.Debug("start: " + req));
        Boolean isValid = ctx.perform(new ValidationCapability.HasMinLength(req.text(), 5));
        // ...
        return Unit.unit();
    }, composedHandler)   // handles both LogCapability and ValidationCapability
).withId("worker").spawn();
```

### Approach 2: `Effect.from()` + `withCapabilityHandler()` — Handler Injected

Use when the handler should be swappable (e.g., for testing). `Effect.from(cap)` creates an effect
that defers handler resolution to spawn time. The builder calls `unsafeRunWithHandler(effect, h)`:

```java
// Same effect function — used in both tests below
Function<OrderEvent, Effect<RuntimeException, Unit>> notifyEffect =
    event -> Effect.<RuntimeException, Unit>from(new NotifyCapability.Send(
                    "Order " + event.orderId() + " is now " + event.status(),
                    event.customerEmail()))
            .flatMap(__ -> Effect.suspend(() -> { /* ... */ return Unit.unit(); }));

// Production: writes to stdout
Pid prod = new EffectActorBuilder<>(system, notifyEffect)
    .withCapabilityHandler(new ConsoleNotifyHandler().widen())
    .withId("prod-notifier").spawn();

// Test: captures notifications in a list
CapturingNotifyHandler capturer = new CapturingNotifyHandler();
Pid test = new EffectActorBuilder<>(system, notifyEffect)
    .withCapabilityHandler(capturer.widen())
    .withId("test-notifier").spawn();

// After test: assert on capturer.captured
```

---

## Defining Custom Capabilities

Extend `Capability<R>` where `R` is the return type. All variants in a sealed interface
must return the **same** `R`:

```java
// All variants return Boolean
sealed interface ValidationCapability extends Capability<Boolean>
        permits ValidationCapability.IsNonEmpty, ValidationCapability.HasMinLength {
    record IsNonEmpty(String value)        implements ValidationCapability {}
    record HasMinLength(String value, int min) implements ValidationCapability {}
}

// All variants return Unit (side effect only)
sealed interface MetricsCapability extends Capability<Unit>
        permits MetricsCapability.Increment, MetricsCapability.Record {
    record Increment(String counter)            implements MetricsCapability {}
    record Record(String metric, double value)  implements MetricsCapability {}
}
```

---

## Implementing a CapabilityHandler

Match on each variant. The `(R)` cast and `@SuppressWarnings("unchecked")` annotation are required
by the generic handler interface — the cast is safe because the sealed switch is exhaustive:

```java
static class ValidationHandler implements CapabilityHandler<ValidationCapability> {
    @Override
    @SuppressWarnings("unchecked")
    public <R> R handle(ValidationCapability capability) {
        return (R) switch (capability) {
            case ValidationCapability.IsNonEmpty v  -> !v.value().isEmpty();
            case ValidationCapability.HasMinLength v -> v.value().length() >= v.min();
        };
    }
}
```

Capture the return value inside `Effect.generate()` by assigning to the correct type:

```java
Effect.generate(ctx -> {
    Boolean valid = ctx.perform(new ValidationCapability.HasMinLength(text, 5));
    // use valid...
    return Unit.unit();
}, new ValidationHandler().widen())
```

---

## Stateful Handlers

Handlers are plain Java objects — they can hold state. Hold a reference before passing to the
actor so you can read accumulated state from your test:

```java
static class MetricsHandler implements CapabilityHandler<MetricsCapability> {
    final ConcurrentHashMap<String, AtomicInteger> counters = new ConcurrentHashMap<>();
    final ConcurrentHashMap<String, Double> gauges = new ConcurrentHashMap<>();

    @Override
    @SuppressWarnings("unchecked")
    public <R> R handle(MetricsCapability capability) {
        return (R) switch (capability) {
            case MetricsCapability.Increment inc -> {
                counters.computeIfAbsent(inc.counter(), k -> new AtomicInteger(0))
                        .incrementAndGet();
                yield Unit.unit();
            }
            case MetricsCapability.Record rec -> {
                gauges.put(rec.metric(), rec.value());
                yield Unit.unit();
            }
        };
    }
}

// Hold reference before passing to the actor
MetricsHandler mh = new MetricsHandler();
Pid actor = new EffectActorBuilder<>(system,
    (ProcessItem item) -> Effect.generate(ctx -> {
        ctx.perform(new MetricsCapability.Increment("items.processed"));
        ctx.perform(new MetricsCapability.Record("last.length", (double) item.key().length()));
        // ...
        return Unit.unit();
    }, mh.widen())
).withId("metrics-actor").spawn();

// After test:
assertEquals(3, mh.counters.get("items.processed").get());
```

---

## Composing Multiple Handlers

`CapabilityHandler.compose()` combines handlers into one dispatcher. Each `ctx.perform()` call
is routed to the correct handler based on the capability's runtime type:

```java
// compose() accepts raw (non-widened) handlers — no .widen() needed on inputs
CapabilityHandler<Capability<?>> combined = CapabilityHandler.compose(
    new ValidationHandler(),
    new MetricsHandler(),
    new ConsoleLogHandler()
);

// All three capability types usable in a single Effect.generate block
Effect.generate(ctx -> {
    Boolean valid  = ctx.perform(new ValidationCapability.HasMinLength(text, 5));
    ctx.perform(new MetricsCapability.Increment(valid ? "valid" : "invalid"));
    ctx.perform(new LogCapability.Info("processed: " + text + " → " + valid));
    return Unit.unit();
}, combined)
```

---

## Handler API Summary

| Method | Returns | Use |
|--------|---------|-----|
| `handler.widen()` | `CapabilityHandler<Capability<?>>` | Required before `generate()` or `withCapabilityHandler()` |
| `handler.widen().orElse(other)` | Combined handler | Chain two handlers; first matching handles |
| `CapabilityHandler.compose(h1, h2, ...)` | `CapabilityHandler<Capability<?>>` | Combine N handlers by capability type dispatch |

---

## See Also

- [`EffectCapabilityExample.java`](../../lib/src/test/java/examples/EffectCapabilityExample.java)
  — `ValidationCapability`, `MetricsCapability`, `compose()`
- [`EffectTestableCapabilityExample.java`](../../lib/src/test/java/examples/EffectTestableCapabilityExample.java)
  — `NotifyCapability` with swappable `ConsoleNotifyHandler` / `CapturingNotifyHandler`
- [Patterns Catalogue](patterns.md)
