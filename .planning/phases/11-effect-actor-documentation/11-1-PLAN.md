# Phase 11, Plan 1: Getting Started + Capabilities Guides

## Objective
Create the `docs/effect-actors/` directory and write two foundational guides:
- `getting-started.md` — first effect actor, core API, `Effect.suspend/succeed/generate/from`
- `capabilities.md` — `Capability<R>`, `CapabilityHandler`, custom capabilities, `compose()`

These two docs are the entry point for Cajun library users encountering the Roux-native API.

## Context

### What the old docs reference (stale — do not use)
`docs/effect_monad_guide.md` and `docs/effect_monad_api.md` describe the **deleted** old API:
`Effect<State,Error,Result>`, `ThrowableEffect`, `Effect.modify()`, `Effect.match()`,
`Effect.tell()`, `fromEffect(system, effect, state)`, etc. These types were removed in Phase 3.
The new guides must reference **only Roux API**.

### Confirmed Roux API surface (from examples)
```
Effect<E, A>                           — computation succeeding with A, failing with E
Effect.suspend(() -> value)            — lazy, no capability
Effect.succeed(value)                  — already-succeeded effect
Effect.fail(error)                     — already-failed effect
Effect.generate(ctx -> value, handler) — lazy with capability handler baked in
Effect.from(capability)                — single-capability effect, handler injected separately

.flatMap(a -> effect)                  — chain effects
.map(a -> b)                           — transform result
.catchAll(err -> effect)               — recover from failure
.mapError(err -> err2)                 — transform error type
.attempt()                             — materialise as Either<E,A>, widens E to Throwable
.orElse(effect)                        — fallback on failure

EffectActorBuilder<E, Message, A>
  new EffectActorBuilder<>(system, handler)
  .withId("id")
  .withCapabilityHandler(CapabilityHandler<Capability<?>>)
  .spawn()

ActorSystemEffectExtensions.spawnEffectActor(system, handler)
ActorSystemEffectExtensions.spawnEffectActor(system, "id", handler)

Capability<R>                          — sealed interface, pure data
CapabilityHandler<C>
  .widen() → CapabilityHandler<Capability<?>>    — required before generate()/withCapabilityHandler()
  .orElse(other) → combined handler
  CapabilityHandler.compose(h1, h2, ...) → CapabilityHandler<Capability<?>>  (no widen needed)

LogCapability (sealed, all Capability<Unit>):
  new LogCapability.Info(String)
  new LogCapability.Debug(String)
  new LogCapability.Warn(String)
  new LogCapability.Error(String)

ConsoleLogHandler implements CapabilityHandler<LogCapability>
  .widen() → CapabilityHandler<Capability<?>>

Unit.unit()                            — return value when no meaningful result
```

### File locations
- New: `docs/effect-actors/getting-started.md`
- New: `docs/effect-actors/capabilities.md`
- Existing examples for cross-linking: `lib/src/test/java/examples/`

---

## Tasks

### Task 1 — Create docs/effect-actors/ directory and write getting-started.md

```markdown
# Getting Started with Effect Actors

## Overview

Cajun's effect actor model lets you express message handling as **Roux Effect pipelines** —
functional descriptions of what an actor should do, executed through the actor system's executor.

Two building blocks:

| Building block | Role |
|----------------|------|
| `EffectActorBuilder<E, Message, A>` | Spawns an actor whose handler returns an `Effect` |
| `Effect<E, A>` | Describes a computation: succeeds with `A`, fails with `E` |

The actor system handles scheduling and thread management. You write the logic.

---

## Your First Effect Actor

The simplest form uses `Effect.suspend()` — a lazy computation with no capability dependency:

```java
import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.functional.EffectActorBuilder;
import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.data.Unit;

ActorSystem system = new ActorSystem();

Pid greeter = new EffectActorBuilder<>(
    system,
    (String name) -> Effect.suspend(() -> {
        System.out.println("Hello, " + name + "!");
        return Unit.unit();
    })
).withId("greeter").spawn();

greeter.tell("Cajun");   // prints: Hello, Cajun!

system.shutdown();
```

`Effect.suspend(supplier)` wraps a lambda that executes when the actor processes the message.
Return `Unit.unit()` for effects with no meaningful result value.

---

## One-Liner Spawn

`spawnEffectActor` is a convenience shortcut for simple actors:

```java
import static com.cajunsystems.functional.ActorSystemEffectExtensions.spawnEffectActor;

Pid actor = spawnEffectActor(system,
    (Integer n) -> Effect.suspend(() -> {
        System.out.println("Got: " + n);
        return Unit.unit();
    }));
```

Pass an explicit ID as the second argument if you need one:

```java
Pid actor = spawnEffectActor(system, "echo-actor",
    (Integer n) -> Effect.suspend(() -> {
        result.set(n * 2);
        latch.countDown();
        return Unit.unit();
    }));
```

---

## Chaining Steps with flatMap

`Effect.succeed(value)` creates an already-succeeded effect. Use `.flatMap()` to chain steps:

```java
Pid pipeline = spawnEffectActor(system,
    (Integer n) -> Effect.<RuntimeException, Integer>succeed(n)
            .flatMap(x -> Effect.succeed(x * 2))        // double it
            .flatMap(x -> Effect.suspend(() -> {
                System.out.println("Result: " + x);
                return Unit.unit();
            })));

pipeline.tell(10);  // prints: Result: 20
```

`.map(fn)` transforms the result without introducing a new effect:

```java
Effect.<RuntimeException, Integer>succeed(10)
    .map(x -> x * 2)     // → 20, no new Effect needed
    .flatMap(x -> ...)
```

---

## Adding Logging with LogCapability

To log inside an effect, use `LogCapability` with `Effect.generate()`:

```java
import com.cajunsystems.functional.capabilities.ConsoleLogHandler;
import com.cajunsystems.functional.capabilities.LogCapability;
import com.cajunsystems.roux.capability.Capability;
import com.cajunsystems.roux.capability.CapabilityHandler;

CapabilityHandler<Capability<?>> logHandler = new ConsoleLogHandler().widen();

Pid counter = new EffectActorBuilder<>(
    system,
    (Integer n) -> Effect.generate(ctx -> {
        ctx.perform(new LogCapability.Info("Processing: " + n));
        // actor logic here...
        ctx.perform(new LogCapability.Debug("Done processing " + n));
        return Unit.unit();
    }, logHandler)
).withId("counter").spawn();
```

`Effect.generate(generator, handler)` takes a lambda receiving a `GeneratorContext`. Call
`ctx.perform(capability)` to execute a capability via the handler.

**LogCapability variants:**

| Capability | Output destination |
|------------|-------------------|
| `new LogCapability.Info("message")` | stdout |
| `new LogCapability.Debug("message")` | stdout |
| `new LogCapability.Warn("message")` | stdout |
| `new LogCapability.Error("message")` | stderr |

Always call `.widen()` on a handler before passing it to `Effect.generate()` or
`withCapabilityHandler()` — this widens `CapabilityHandler<LogCapability>` to
`CapabilityHandler<Capability<?>>` which the builder expects.

---

## Effect API Quick Reference

| Expression | Description |
|------------|-------------|
| `Effect.suspend(() -> value)` | Lazy effect; no capability required |
| `Effect.succeed(value)` | Already-succeeded effect |
| `Effect.fail(error)` | Already-failed effect |
| `Effect.generate(ctx -> ..., handler)` | Lazy effect with capability; handler baked in |
| `Effect.from(capability)` | Single-capability effect; handler injected at spawn time |
| `.flatMap(a -> effect)` | Chain effects sequentially |
| `.map(a -> b)` | Transform the result value |
| `.catchAll(err -> effect)` | Recover from any failure |
| `.mapError(err -> err2)` | Transform the error type |
| `.orElse(effect)` | Fallback effect on failure |
| `.attempt()` | Materialise errors as `Either<E,A>`; widens E to Throwable |
| `Unit.unit()` | Return value for effects with no meaningful result |

---

## Next Steps

- [Capabilities Guide](capabilities.md) — custom capabilities, composing handlers, test doubles
- [Patterns Catalogue](patterns.md) — error handling, retry, pipeline, fan-out
- [`EffectActorExample.java`](../lib/src/test/java/examples/EffectActorExample.java) — runnable examples
```

Commit:
```bash
mkdir -p docs/effect-actors
git add docs/effect-actors/getting-started.md
git commit -m "docs(11-1): add effect-actors/getting-started.md"
```

---

### Task 2 — Write docs/effect-actors/capabilities.md

```markdown
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

- [`EffectCapabilityExample.java`](../lib/src/test/java/examples/EffectCapabilityExample.java)
  — `ValidationCapability`, `MetricsCapability`, `compose()`
- [`EffectTestableCapabilityExample.java`](../lib/src/test/java/examples/EffectTestableCapabilityExample.java)
  — `NotifyCapability` with swappable `ConsoleNotifyHandler` / `CapturingNotifyHandler`
- [Patterns Catalogue](patterns.md)
```

Commit:
```bash
git add docs/effect-actors/capabilities.md
git commit -m "docs(11-1): add effect-actors/capabilities.md"
```

---

### Task 3 — Verify links resolve

Check that all relative links in both files point to real paths:

```bash
# getting-started.md links to:
ls docs/effect-actors/capabilities.md              # ✓ created in Task 2
ls docs/effect-actors/patterns.md                  # will exist after Plan 11-2
ls lib/src/test/java/examples/EffectActorExample.java

# capabilities.md links to:
ls lib/src/test/java/examples/EffectCapabilityExample.java
ls lib/src/test/java/examples/EffectTestableCapabilityExample.java
ls docs/effect-actors/patterns.md
```

The `patterns.md` links won't resolve until Plan 11-2 — that's expected at this stage.

No commit for this task — verification only.

---

## Potential deviations and fixes

**If the `docs/effect-actors/` directory creation fails:**
```bash
mkdir -p docs/effect-actors
```

**If link paths are wrong** (e.g., relative path from docs/effect-actors/ to lib/):
The relative path from `docs/effect-actors/` to `lib/src/test/java/examples/` is
`../../lib/src/test/java/examples/`. Update links accordingly if needed.

---

## Verification

- [ ] `docs/effect-actors/` directory created
- [ ] `docs/effect-actors/getting-started.md` written — covers `Effect.suspend`, `Effect.succeed`, `Effect.generate`, `flatMap`, `LogCapability`
- [ ] `docs/effect-actors/capabilities.md` written — covers `Capability<R>`, `CapabilityHandler`, custom capabilities, stateful handlers, `compose()`
- [ ] All example file references point to real files

## Success Criteria

Both docs are complete, accurate to the Roux API, and cross-reference each other and the existing
example files. No references to the deleted old API (`Effect<State,Error,Result>`, `ThrowableEffect`,
`Effect.modify()`, etc.).

## Output
- Created: `docs/effect-actors/getting-started.md`
- Created: `docs/effect-actors/capabilities.md`
