<objective>
Update all three docs/effect-actors/ guides to cover the Roux v0.2.x API, replace stale
patterns, and add a migration guide:

1. getting-started.md — expand Quick Reference table with v0.2.x ops; fix stale .widen() note
2. patterns.md — replace manual withRetry() with built-in retry API; add new pattern sections
   (tap/tapError, timeout, parAll/race/traverse, Resource<A>); update Example Index
3. capabilities.md — update handler examples to CapabilityHandler.builder() pattern; add
   MissingCapabilityHandlerException diagnostics; add scoped-fork capability inheritance
4. migration.md (new) — v0.1.0 → v0.2.1 migration guide

No production code changes. Documentation only.
</objective>

<execution_context>
- docs/effect-actors/getting-started.md
- docs/effect-actors/patterns.md
- docs/effect-actors/capabilities.md
- docs/effect-actors/migration.md  (new file)

Key examples to reference:
- lib/src/test/java/examples/EffectRetryExample.java         (Phase 14 — built-in retry)
- lib/src/test/java/examples/EffectErrorHandlingExample.java  (tap/tapError tests)
- lib/src/test/java/examples/EffectTimeoutExample.java        (Phase 14 — timeout fallback)
- lib/src/test/java/examples/EffectParallelExample.java       (Phase 15 — parAll/race/traverse)
- lib/src/test/java/examples/EffectResourceExample.java       (Phase 15 — Resource<A>)
- lib/src/test/java/com/cajunsystems/functional/capabilities/ConsoleLogHandler.java
</execution_context>

<context>
## What changed from v0.1.0 → v0.2.x

### New factory methods
- `Effect.unit()` — alias for `Effect.succeed(Unit.unit())`
- `Effect.runnable(() -> ...)` — side-effect lambda; returns Unit automatically
- `Effect.sleep(Duration)` — suspends for a duration
- `Effect.when(bool, Effect)` / `Effect.unless(bool, Effect)` — conditional execution

### Observe / side-effect operators
- `effect.tap(value -> ...)` — fires on SUCCESS only; passes value through unchanged
- `effect.tapError(err -> ...)` — fires on FAILURE only; re-throws original error unchanged

### Built-in retry
- `effect.retry(n)` — n = ADDITIONAL attempts; 3 total = retry(2); error type stays as E
- `effect.retryWithDelay(n, Duration)` — retry with fixed delay; widens to Throwable
- `effect.retry(RetryPolicy)` — configurable policy (exponential, jitter, max); widens to Throwable
  - `RetryPolicy.immediate().maxAttempts(n)` — immediate retries with cap
  - `RetryPolicy.exponential(initialDelay)` — exponential backoff
- Old approach: manual `withRetry(op, remaining)` recursive catchAll helper (now obsolete)

### Timeout
- `effect.timeout(Duration)` — widens error type to Throwable; throws Roux TimeoutException
- Roux TimeoutException: `com.cajunsystems.roux.exception.TimeoutException` (NOT java.util.concurrent)
- assert with: `thrown.getClass().getName().contains("TimeoutException")`
- `timeout().catchAll(...)` produces `Effect<Throwable,A>` — test methods need `throws Throwable`

### Parallel combinators (Effects.*)
- `Effects.parAll(List<Effect<E,A>>)` — concurrent; results in insertion order; widens to Throwable
- `Effects.race(Effect, Effect)` / `Effects.race(List<Effect>)` — first wins; widens to Throwable
- `Effects.traverse(List<A>, Function<A, Effect<E,B>>)` — sequential; error stays as E (NOT widened)
- `Effects.sequence(List<Effect<E,A>>)` — sequential collect; stays as E

### Resource<A>
- `Resource.make(acquire, release)` — explicit acquire + always-run release
- `Resource.fromCloseable(Effect<E, AutoCloseable>)` — release = .close() automatically
- `resource.use(fn)` — widens to Throwable; release runs on success AND failure
- `Resource.ensuring(effect, finalizer)` — try-finally pattern; widens to Throwable
- `Resource.flatMap()` — simplified; prefer nested use() for dependent composition

### Either gains fold/map/flatMap/swap
- `Either.map(fn)` / `Either.flatMap(fn)` / `Either.fold(leftFn, rightFn)` / `Either.swap()`

### CapabilityHandler.builder() — preferred dispatch pattern
```java
// PREFERRED (since Phase 12 refactor)
private static final CapabilityHandler<Capability<?>> DELEGATE = CapabilityHandler.builder()
    .on(Foo.class, foo -> /* handle foo, return R */)
    .on(Bar.class, bar -> /* handle bar, return R */)
    .build();   // auto-throws UnsupportedOperationException for unregistered types
@Override public <R> R handle(Capability<?> cap) throws Exception { return DELEGATE.handle(cap); }

// OLD (still works but not preferred)
static class Handler implements CapabilityHandler<MyCapability> {
    @Override @SuppressWarnings("unchecked")
    public <R> R handle(MyCapability cap) {
        return (R) switch (cap) { case Foo f -> ...; case Bar b -> ...; };
    }
}
```

### Stateful handler with builder
```java
// Stateful: build delegate in constructor so closures capture instance state
class MetricsHandler implements CapabilityHandler<Capability<?>> {
    private final ConcurrentHashMap<String, AtomicInteger> counters = new ConcurrentHashMap<>();
    private final CapabilityHandler<Capability<?>> delegate;

    MetricsHandler() {
        delegate = CapabilityHandler.builder()
            .on(MetricsCapability.Increment.class, inc -> {
                counters.computeIfAbsent(inc.counter(), k -> new AtomicInteger(0)).incrementAndGet();
                return Unit.unit();
            })
            .build();
    }
    @Override public <R> R handle(Capability<?> cap) throws Exception { return delegate.handle(cap); }
}
```

### MissingCapabilityHandlerException (v0.2.1)
- Thrown when `ctx.perform()` is called for a capability type with no registered handler
- Message includes the concrete capability type name — clear diagnostic
- Cause: passing a raw `ValidationHandler` (typed as `<ValidationCapability>`) to
  `Effect.generate()` / `.withCapabilityHandler()` which expects `<Capability<?>>` — use `.widen()`

### Scoped-fork capability inheritance (v0.2.1 fix)
- Before v0.2.1: forked fibers did NOT inherit capability handlers from parent scope
- v0.2.1 fix: `Effect.scoped()` forks now inherit parent's `ExecutionContext` (handlers included)
- Use `unsafeRunWithHandler(effect, handler)` when running an effect with capabilities
- `Effect.from(cap).forkIn(scope)` — forked fiber can perform capabilities in its scope

## ConsoleLogHandler (post-Phase 12)
ConsoleLogHandler now implements `CapabilityHandler<Capability<?>>` directly (not `<LogCapability>`).
Calling `.widen()` on it is a no-op cast but still required by the API contract.
The doc's sentence "implements CapabilityHandler<LogCapability>" is stale — fix to `Capability<?>`.
</context>

<tasks>

## Task 1 — Update docs/effect-actors/getting-started.md

**File**: `docs/effect-actors/getting-started.md`

Two changes:

### Change 1.A — Expand Quick Reference table

Current table ends at `Unit.unit()`. Add the following rows after `.attempt()`:

```
| `Effect.unit()` | Alias for `Effect.succeed(Unit.unit())` |
| `Effect.runnable(() -> ...)` | Side-effect lambda; returns `Unit` automatically |
| `effect.tap(value -> ...)` | Observe success value; result passes through unchanged |
| `effect.tapError(err -> ...)` | Observe failure; original error re-thrown unchanged |
| `effect.retry(n)` | Retry up to `n` additional times on failure; error type unchanged |
| `effect.retryWithDelay(n, Duration)` | Retry `n` times with fixed delay; widens E to `Throwable` |
| `effect.retry(RetryPolicy)` | Retry with configurable policy (backoff, jitter, cap) |
| `effect.timeout(Duration)` | Fail with `TimeoutException` if deadline exceeded; widens E to `Throwable` |
| `Effects.parAll(List)` | Run effects concurrently; collect results in insertion order; widens to `Throwable` |
| `Effects.race(List)` | Return the first to complete; cancel the rest; widens to `Throwable` |
| `Effects.traverse(List, fn)` | Apply function to each item sequentially; collect results; error stays as `E` |
```

### Change 1.B — Fix stale .widen() note

Current text (line 138-140):
```
Always call `.widen()` on a handler before passing it to `Effect.generate()` or
`withCapabilityHandler()` — this widens `CapabilityHandler<LogCapability>` to
`CapabilityHandler<Capability<?>>` which the builder expects.
```

Replace with:
```
Always call `.widen()` on a handler before passing it to `Effect.generate()` or
`withCapabilityHandler()` — this ensures the handler is typed as
`CapabilityHandler<Capability<?>>` which the builder expects.
```

After editing, verify the file looks correct (read it back).

---

## Task 2 — Update docs/effect-actors/patterns.md

**File**: `docs/effect-actors/patterns.md`

Three changes:

### Change 2.A — Replace manual "Retry with backoff" sub-section

The current "Retry with backoff" sub-section (lines 72-93) shows a manual `withRetry()` helper.
Remove it and replace with built-in retry API:

Remove this block:
```
### Retry with backoff

Build a retry chain with recursive `catchAll`. ...

static Effect<RuntimeException, String> withRetry(Supplier<String> op, int remaining) { ... }

// Usage:
Pid actor = spawnEffectActor(system, ...)

**See**: `EffectErrorHandlingExample.java`, `EffectRetryExample.java`
```

Replace with:
```markdown
### Built-in Retry

Use `.retry(n)` for a fixed number of additional attempts. `n` is additional attempts, not total:
`retry(2)` = 1 initial + 2 retries = 3 total executions:

```java
Effect<RuntimeException, String> flaky = Effect.suspend(() -> {
    if (callCount.incrementAndGet() < 3) throw new RuntimeException("transient failure");
    return "ok";
});

String result = runtime.unsafeRun(flaky.retry(2));  // succeeds on attempt 3
```

For retry with delays, use `.retryWithDelay(n, Duration)` (widens error to `Throwable`):

```java
Effect<RuntimeException, String> withDelay = fetchData.retryWithDelay(3, Duration.ofMillis(100));
```

For configurable policies (exponential backoff, jitter, max attempts):

```java
import com.cajunsystems.roux.RetryPolicy;

Effect<Throwable, String> withPolicy = fetchData.retry(
    RetryPolicy.exponential(Duration.ofMillis(50)).maxAttempts(4)
);
```

**Note**: `.retryWithDelay()` and `.retry(RetryPolicy)` widen the error type to `Throwable` —
the calling method must declare `throws Throwable`.

**See**: `EffectRetryExample.java`
```

### Change 2.B — Add new sections after Section 5 (Fan-Out Dispatcher)

After the `EffectFanOutExample.java` "See" line and before the "Example Index" heading,
add four new sections:

```markdown
---

## 6. Observe Without Altering: tap / tapError

`tap(fn)` fires on success and passes the value through unchanged — use it for logging, metrics,
or audit trails that must not affect the effect result:

```java
Effect<RuntimeException, Order> process =
    Effect.<RuntimeException, Order>suspend(() -> placeOrder(req))
        .tap(order -> logger.info("Order placed: " + order.id()))   // observe, don't change
        .flatMap(order -> Effect.suspend(() -> sendConfirmation(order)));
```

`tapError(fn)` fires on failure and re-throws the original error unchanged — use it to record
failures without suppressing them:

```java
Effect<RuntimeException, String> withMetrics =
    fetchData
        .tapError(err -> metrics.increment("fetch.errors"))    // observe failure
        .catchAll(err -> Effect.succeed("fallback"));           // then recover
```

**See**: `EffectErrorHandlingExample.java`

---

## 7. Timeout with Fallback

`timeout(Duration)` cancels an effect if it exceeds the deadline. The error type widens to
`Throwable` — combine with `catchAll` to provide a fallback:

```java
Effect<RuntimeException, String> slow = Effect.suspend(() -> {
    Thread.sleep(500); return "slow-result";
});

// Widen and recover: test method needs `throws Throwable`
String result = runtime.unsafeRun(
    slow.timeout(Duration.ofMillis(50))
        .catchAll(e -> Effect.succeed("fallback"))
);
// → "fallback" (timeout exceeded)
```

Roux throws its own `com.cajunsystems.roux.exception.TimeoutException` — not
`java.util.concurrent.TimeoutException`. When checking the type, use:

```java
assertTrue(thrown.getClass().getName().contains("TimeoutException"));
```

**Note**: `timeout().catchAll()` produces `Effect<Throwable, A>` — methods calling
`runtime.unsafeRun()` on the result must declare `throws Throwable`.

**See**: `EffectTimeoutExample.java`

---

## 8. Parallel / Sequential Combinators

`Effects.parAll()`, `Effects.race()`, and `Effects.traverse()` process collections of effects.
Choose based on whether concurrency and order matter:

| Combinator | Execution | Error type | Use when |
|------------|-----------|------------|----------|
| `Effects.parAll(List)` | Concurrent | Widens to `Throwable` | All results needed; independent tasks |
| `Effects.race(List)` | Concurrent | Widens to `Throwable` | Any one result sufficient |
| `Effects.traverse(List, fn)` | Sequential | Stays as `E` | Order matters or tasks share state |

```java
// parAll — concurrent fan-out; results in insertion order
List<String> results = runtime.unsafeRun(
    Effects.parAll(List.of(fetchA, fetchB, fetchC))
);

// race — returns the fastest; cancels the rest
String winner = runtime.unsafeRun(Effects.race(List.of(slow, medium, fast)));

// traverse — sequential; error type stays as RuntimeException (not widened)
List<Integer> lengths = runtime.unsafeRun(
    Effects.traverse(List.of("hello", "world"), word ->
        Effect.<RuntimeException, Integer>succeed(word.length()))
);
```

**See**: `EffectParallelExample.java`

---

## 9. Resource Management

`Resource<A>` pairs an acquire effect with a guaranteed-run release. The release always
executes whether the consumer succeeds, fails, or is interrupted — preventing resource leaks:

```java
Resource<Connection> connResource = Resource.make(
    Effect.suspend(() -> pool.acquire()),          // acquire
    conn -> Effect.runnable(conn::close)           // always-run release
);

// resource.use() widens to Throwable — declare throws Throwable
String result = runtime.unsafeRun(
    connResource.use(conn -> Effect.suspend(() -> conn.query("SELECT ...")))
);
// conn.close() runs even if the query throws
```

For `AutoCloseable` resources (streams, readers), use `fromCloseable()` — no release lambda needed:

```java
Resource<InputStream> stream = Resource.fromCloseable(
    Effect.suspend(() -> Files.newInputStream(path))
);
```

For cleanup that doesn't depend on a resource handle, use `Resource.ensuring()` — the functional
try-finally:

```java
// finalizer always runs (widens to Throwable)
Effect<Throwable, String> safe = Resource.ensuring(
    Effect.suspend(() -> riskyOperation()),
    Effect.runnable(() -> counter.decrementAndGet())
);
```

**See**: `EffectResourceExample.java`
```

### Change 2.C — Update Example Index table

Replace the current Example Index table with:

```markdown
## Example Index

| Example file | Patterns covered |
|---|---|
| `EffectActorExample.java` | First actor, `Effect.suspend`, `Effect.succeed`, `flatMap` |
| `EffectErrorHandlingExample.java` | `catchAll`, `orElse`, `mapError`, `attempt`, `tap`, `tapError` |
| `EffectRetryExample.java` | Built-in `retry(n)`, `retryWithDelay()`, `RetryPolicy` |
| `EffectTimeoutExample.java` | `timeout(Duration)`, timeout fallback, actor-always-replies |
| `EffectAskPatternExample.java` | Reply-via-Pid, request-response with effect actor |
| `EffectStatefulCompositionExample.java` | `StatefulHandler` + `EffectActorBuilder` side-by-side |
| `EffectPipelineExample.java` | 4-stage linear pipeline, sink-first wiring |
| `EffectFanOutExample.java` | Dispatcher + worker pool, round-robin fan-out |
| `EffectCapabilityExample.java` | Custom capabilities, stateful handler, `compose()` |
| `EffectTestableCapabilityExample.java` | Swappable handlers, test double pattern |
| `EffectParallelExample.java` | `parAll`, `race`, `traverse` — parallel and sequential combinators |
| `EffectResourceExample.java` | `Resource.make`, `fromCloseable`, `ensuring` — lifecycle guarantees |
```

After editing, verify the file looks correct (read it back).

---

## Task 3 — Update docs/effect-actors/capabilities.md

**File**: `docs/effect-actors/capabilities.md`

Four changes:

### Change 3.A — Fix ConsoleLogHandler description

Line 28: "implements `CapabilityHandler<LogCapability>`"
Change to: "implements `CapabilityHandler<Capability<?>>`"

### Change 3.B — Update "Implementing a CapabilityHandler" section

Current section shows the old switch+cast pattern as the only approach.
Replace the entire section with a version that presents `CapabilityHandler.builder()` as
the preferred approach and keeps the switch pattern as an alternative:

```markdown
## Implementing a CapabilityHandler

### Preferred: CapabilityHandler.builder()

`CapabilityHandler.builder()` registers a handler function per capability type. Unregistered
types throw `UnsupportedOperationException` automatically — satisfying the dispatch contract
required by `compose()` and `orElse()`:

```java
private static final CapabilityHandler<Capability<?>> VALIDATION =
    CapabilityHandler.builder()
        .on(ValidationCapability.IsNonEmpty.class,   v -> !v.value().isEmpty())
        .on(ValidationCapability.HasMinLength.class, v -> v.value().length() >= v.min())
        .build();

// Wrap in a handler class (or use directly with .widen()):
static class ValidationHandler implements CapabilityHandler<Capability<?>> {
    @Override
    public <R> R handle(Capability<?> cap) throws Exception {
        return VALIDATION.handle(cap);
    }
}
```

The delegate is `private static final` because `ValidationHandler` has no state — sharing
it across instances is safe and avoids allocation.

### Alternative: sealed switch

The switch approach still works, but requires a manual cast and `@SuppressWarnings("unchecked")`:

```java
static class ValidationHandler implements CapabilityHandler<ValidationCapability> {
    @Override
    @SuppressWarnings("unchecked")
    public <R> R handle(ValidationCapability capability) {
        return (R) switch (capability) {
            case ValidationCapability.IsNonEmpty v   -> !v.value().isEmpty();
            case ValidationCapability.HasMinLength v -> v.value().length() >= v.min();
        };
    }
}
```

Note: when using the switch approach with `compose()` or `orElse()`, you must also
throw `UnsupportedOperationException` for unhandled types (the builder does this automatically).

Capture the return value inside `Effect.generate()` by assigning to the correct type:

```java
Effect.generate(ctx -> {
    Boolean valid = ctx.perform(new ValidationCapability.HasMinLength(text, 5));
    // use valid...
    return Unit.unit();
}, new ValidationHandler().widen())
```
```

### Change 3.C — Update "Stateful Handlers" section

Replace the MetricsHandler example (which shows the old switch pattern) with the builder pattern:

```markdown
## Stateful Handlers

Handlers that hold state must build their delegate in the constructor — the builder lambdas
capture instance fields via closures:

```java
static class MetricsHandler implements CapabilityHandler<Capability<?>> {
    final ConcurrentHashMap<String, AtomicInteger> counters = new ConcurrentHashMap<>();
    final ConcurrentHashMap<String, Double> gauges = new ConcurrentHashMap<>();

    private final CapabilityHandler<Capability<?>> delegate;

    MetricsHandler() {
        this.delegate = CapabilityHandler.builder()
            .on(MetricsCapability.Increment.class, inc -> {
                counters.computeIfAbsent(inc.counter(), k -> new AtomicInteger(0))
                        .incrementAndGet();
                return Unit.unit();
            })
            .on(MetricsCapability.Record.class, rec -> {
                gauges.put(rec.metric(), rec.value());
                return Unit.unit();
            })
            .build();
    }

    @Override
    public <R> R handle(Capability<?> cap) throws Exception {
        return delegate.handle(cap);
    }
}

// Hold reference before passing to the actor
MetricsHandler mh = new MetricsHandler();
Pid actor = new EffectActorBuilder<>(system,
    (ProcessItem item) -> Effect.generate(ctx -> {
        ctx.perform(new MetricsCapability.Increment("items.processed"));
        ctx.perform(new MetricsCapability.Record("last.length", (double) item.key().length()));
        return Unit.unit();
    }, mh.widen())
).withId("metrics-actor").spawn();

// After test:
assertEquals(3, mh.counters.get("items.processed").get());
```
```

### Change 3.D — Add two new sections before "See Also"

Add after the "Handler API Summary" table and before "See Also":

```markdown
---

## MissingCapabilityHandlerException Diagnostics

If `ctx.perform()` is called for a capability type that has no registered handler, Roux throws
`MissingCapabilityHandlerException` with the concrete capability type in the message. Common causes:

**1. Forgot `.widen()` on a typed handler:**
```java
// Wrong — ValidationHandler<ValidationCapability> passed where <Capability<?>> expected
Effect.generate(ctx -> ..., new ValidationHandler())   // compile error or runtime cast failure

// Correct
Effect.generate(ctx -> ..., new ValidationHandler().widen())
```

**2. Handler not registered for that capability type:**
```java
// handler only covers LogCapability — MetricsCapability will throw
CapabilityHandler<Capability<?>> h = new ConsoleLogHandler().widen();
ctx.perform(new MetricsCapability.Increment("x"));  // → MissingCapabilityHandlerException

// Fix: compose both handlers
CapabilityHandler<Capability<?>> h = CapabilityHandler.compose(
    new ConsoleLogHandler(), new MetricsHandler()
);
```

**3. Using `Effect.from()` without calling `.withCapabilityHandler()` on the builder:**
```java
// Effect.from(cap) defers handler resolution to spawn time
// If no handler was registered, every message execution will throw
new EffectActorBuilder<>(system, msg -> Effect.from(new MyCap(msg)))
    // missing: .withCapabilityHandler(myHandler.widen())
    .spawn();
```

---

## Scoped-Fork Capability Inheritance

Since Roux v0.2.1, fibers forked via `Effect.scoped()` inherit the parent scope's
`ExecutionContext`, including registered capability handlers. This means capability calls
inside a forked fiber work without any additional configuration:

```java
CapabilityHandler<Capability<?>> tracker = CapabilityHandler.builder()
    .on(TrackCapability.Label.class, l -> "handled-" + l.name())
    .build();

Effect<Throwable, String> effect = Effect.scoped(scope -> {
    // Fork a fiber that performs a capability
    Effect<Throwable, Fiber<RuntimeException, String>> forkedFiber =
        Effect.<RuntimeException, String>from(new TrackCapability.Label("child"))
              .forkIn(scope);

    return forkedFiber
        .flatMap(fiber -> fiber.join().widen())
        .flatMap(childResult ->
            Effect.<RuntimeException, String>from(new TrackCapability.Label("parent"))
                  .map(parentResult -> childResult + "+" + parentResult).widen());
});

// Run with handler — both parent and child capability calls are handled
String result = runtime.unsafeRunWithHandler(effect, tracker);
// → "handled-child+handled-parent"
```

Before v0.2.1, the child fiber would have thrown `MissingCapabilityHandlerException`.
```

After editing, verify the file looks correct (read it back).

---

## Task 4 — Create docs/effect-actors/migration.md

**File**: `docs/effect-actors/migration.md` (new file)

Create the migration guide:

```markdown
# Migrating from Roux v0.1.0 to v0.2.1

This guide covers every breaking change, renamed API, and new feature relevant to
Cajun's effect actor integration.

---

## Breaking Changes

### DefaultEffectRuntime is now AutoCloseable

`DefaultEffectRuntime` (which `ActorEffectRuntime` extends) now implements `AutoCloseable`.
Its `close()` shuts down the underlying executor.

**Cajun's fix**: `ActorEffectRuntime.close()` is overridden as a no-op — the executor
belongs to `ActorSystem`, not the runtime:

```java
@Override
public void close() {
    // no-op: executor is owned by ActorSystem, not this runtime
}
```

If you wrote your own `EffectRuntime` subclass that extends `DefaultEffectRuntime`, add
the same override if your executor has a longer lifetime than the runtime.

### CapabilityHandler.compose() / orElse() — handler contract

`compose()` and `orElse()` require handlers that throw `UnsupportedOperationException` for
unhandled capability types. In v0.1.0 this was easy to miss.

v0.2.x makes this explicit: use `CapabilityHandler.builder()` which auto-throws `UnsupportedOperationException`
for unregistered types. Or, if using the switch approach, add a default clause:

```java
// switch approach — must throw UOE for unhandled types
@Override
public <R> R handle(MyCapability cap) {
    return (R) switch (cap) {
        case Foo f -> handleFoo(f);
        // no default needed if sealed switch covers all permits
    };
}
```

---

## New APIs

### Effect factory methods

| v0.2.x | v0.1.0 equivalent |
|--------|-------------------|
| `Effect.unit()` | `Effect.succeed(Unit.unit())` |
| `Effect.runnable(() -> ...)` | `Effect.suspend(() -> { ...; return Unit.unit(); })` |
| `Effect.sleep(Duration)` | — (manual `Thread.sleep` inside `suspend`) |
| `Effect.when(bool, effect)` | manual `if` inside `suspend` |

### Observe operators

```java
// tap — observe success, pass through unchanged
effect.tap(value -> logger.info("got: " + value))

// tapError — observe failure, re-throw unchanged
effect.tapError(err -> metrics.increment("errors"))
```

### Built-in retry

```java
// v0.1.0: manual recursive catchAll
static Effect<E, A> withRetry(Supplier<A> op, int remaining) {
    return Effect.<E, A>suspend(op)
        .catchAll(err -> remaining > 0 ? withRetry(op, remaining - 1) : Effect.fail(err));
}

// v0.2.x: built-in
effect.retry(2)                                      // 2 additional attempts
effect.retryWithDelay(3, Duration.ofMillis(100))     // widens to Throwable
effect.retry(RetryPolicy.exponential(Duration.ofMillis(50)).maxAttempts(4))  // widens
```

### Timeout

```java
// Throws com.cajunsystems.roux.exception.TimeoutException (not java.util.concurrent)
effect.timeout(Duration.ofSeconds(1))
      .catchAll(e -> Effect.succeed("fallback"))
// Methods calling unsafeRun() on the result need: throws Throwable
```

### Parallel combinators

```java
// parAll — concurrent; widens to Throwable
Effects.parAll(List.of(fetchA, fetchB, fetchC))

// race — first wins; widens to Throwable
Effects.race(List.of(slow, medium, fast))

// traverse — sequential; error stays as E
Effects.traverse(items, item -> Effect.<E, B>succeed(transform(item)))
```

### Resource<A>

```java
// make — full control
Resource.make(acquireEffect, resource -> Effect.runnable(resource::close))

// fromCloseable — for AutoCloseable
Resource.fromCloseable(Effect.suspend(() -> Files.newInputStream(path)))

// use — release guaranteed; widens to Throwable
resource.use(r -> Effect.suspend(() -> r.read()))

// ensuring — try-finally; widens to Throwable
Resource.ensuring(riskyEffect, Effect.runnable(() -> cleanup()))
```

---

## Either additions

`Either` gains `map`, `flatMap`, `fold`, and `swap` in v0.2.x:

```java
Either<E, A> either = runtime.unsafeRun(effect.attempt());

// fold replaces instanceof checks
String result = either.fold(
    err  -> "failed: " + err.getMessage(),
    val  -> "succeeded: " + val
);

// map transforms Right
Either<E, String> mapped = either.map(Object::toString);
```

---

## Version compatibility

| Cajun feature | Roux version required |
|---|---|
| `EffectActorBuilder`, `ActorEffectRuntime` | 0.1.0+ |
| `tap()`, `tapError()`, `retry(n)`, `timeout()` | 0.2.0+ |
| `RetryPolicy`, `Effects.parAll/race/traverse` | 0.2.0+ |
| `Resource<A>` | 0.2.0+ |
| Scoped-fork capability inheritance | 0.2.1+ |
| `MissingCapabilityHandlerException` with type name | 0.2.1+ |
| `Fiber.join()` no double-wrap of runtime exceptions | 0.2.1+ |
```

After writing, verify the file looks correct (read it back).

</tasks>

<verification>
- [ ] getting-started.md Quick Reference expanded with 11 new rows
- [ ] getting-started.md .widen() note fixed (no longer says "widens LogCapability")
- [ ] patterns.md manual withRetry() replaced with built-in retry API
- [ ] patterns.md tap/tapError section added (Section 6)
- [ ] patterns.md timeout section added (Section 7)
- [ ] patterns.md parAll/race/traverse section added (Section 8)
- [ ] patterns.md Resource<A> section added (Section 9)
- [ ] patterns.md Example Index updated with 4 new example files
- [ ] capabilities.md ConsoleLogHandler description fixed to CapabilityHandler<Capability<?>>
- [ ] capabilities.md handler section updated to prefer builder() pattern
- [ ] capabilities.md stateful handler updated to builder() with constructor delegate
- [ ] capabilities.md MissingCapabilityHandlerException section added
- [ ] capabilities.md scoped-fork inheritance section added
- [ ] migration.md created with all sections
- [ ] Each file committed individually
- [ ] SUMMARY.md created, STATE.md + ROADMAP updated, metadata committed
</verification>

<success_criteria>
- All v0.2.x APIs documented (tap, tapError, retry, timeout, parAll, race, traverse, Resource)
- No stale v0.1.0 patterns remain (withRetry() removed from patterns.md; old handler pattern
  shown only as "alternative", with builder() as preferred)
- New example files (EffectRetryExample, EffectTimeoutExample, EffectParallelExample,
  EffectResourceExample) linked from patterns.md Example Index
- MissingCapabilityHandlerException diagnostics guide added to capabilities.md
- Scoped-fork capability inheritance documented in capabilities.md
- Migration guide covers all breaking changes and new APIs
- Documentation is self-contained and consistent with the current codebase
</success_criteria>

<output>
- Modified: docs/effect-actors/getting-started.md
- Modified: docs/effect-actors/patterns.md
- Modified: docs/effect-actors/capabilities.md
- Created:  docs/effect-actors/migration.md
- Created:  .planning/phases/16-documentation-update/16-1-SUMMARY.md
</output>
