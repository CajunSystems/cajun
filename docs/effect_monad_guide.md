# Effect Actors in Cajun

> **Note:** This guide has been replaced by the new Roux-native effect actor documentation.
>
> The old `Effect<State, Error, Result>` API (including `ThrowableEffect`, `EffectMatchBuilder`,
> `EffectConversions`, `Effect.modify()`, `Effect.match()`, `Effect.tell()`, and
> `fromEffect(system, effect, state)`) was **removed** in the Cajun × Roux migration (Milestone 1).

## New Documentation

- **[Getting Started with Effect Actors](effect-actors/getting-started.md)**
  — first actor, `Effect.suspend`, `Effect.succeed`, `flatMap`, `LogCapability`

- **[Capabilities Guide](effect-actors/capabilities.md)**
  — `Capability<R>`, `CapabilityHandler`, custom capabilities, `compose()`, test doubles

- **[Patterns Catalogue](effect-actors/patterns.md)**
  — error handling, retry, ask pattern, stateful composition, pipeline, fan-out

## Key API Changes (Old → New)

| Old API (deleted) | New API (Roux) |
|-------------------|----------------|
| `Effect<State, Error, Result>` | `Effect<E, A>` (2 params; no embedded state) |
| `ThrowableEffect<State, Result>` | `Effect<RuntimeException, A>` |
| `Effect.modify(s -> ...)` | `StatefulHandler.receive(msg, state, ctx)` |
| `Effect.<S,E,R,M>match().when(...)` | sealed interfaces + `instanceof` in handler |
| `Effect.log("msg")` | `LogCapability.Info("msg")` via `ctx.perform()` |
| `Effect.tell(pid, msg)` | `pid.tell(msg)` directly |
| `Effect.ask(pid, msg, timeout)` | `system.ask(pid, msg, timeout)` |
| `fromEffect(system, effect, state)` | `new EffectActorBuilder<>(system, handler).spawn()` |
| `EffectConversions.fromBiFunction(fn)` | `StatefulHandler.receive(msg, state, ctx)` |

## Runnable Examples

All examples are in `lib/src/test/java/examples/` and run as JUnit 5 tests:

```bash
./gradlew :lib:test --tests "examples.EffectActorExample"
./gradlew :lib:test --tests "examples.EffectErrorHandlingExample"
./gradlew :lib:test --tests "examples.EffectCapabilityExample"
# etc.
```
