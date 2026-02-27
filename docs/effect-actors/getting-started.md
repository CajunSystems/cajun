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
- [`EffectActorExample.java`](../../lib/src/test/java/examples/EffectActorExample.java) — runnable examples
