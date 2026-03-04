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
for unregistered types. Or, if using the switch approach, the sealed switch covers all
permits exhaustively — no default clause needed.

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
