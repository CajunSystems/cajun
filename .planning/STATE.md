# Project State

## Current Status
**Milestone**: 3 — Roux v0.2.1 Upgrade
**Phase**: 14 ✅ Complete
**Status**: Active — 376 tests, 0 failures
**Branch**: feature/roux-effect-integration
**Last Updated**: 2026-03-04

## Milestone 3 Phase Progress

| Phase | Name | Status |
|-------|------|--------|
| 12 | Upgrade & Compatibility | ✅ Complete |
| 13 | Bridge Concurrency & Timeout | ✅ Complete |
| 14 | Modernize Retry & Error Examples | ✅ Complete |
| 15 | New Concurrency & Resource Examples | ⬜ Not started |
| 16 | Documentation Update | ⬜ Not started |

## Milestone 2 Phase Progress (archived — v0.5.0)

| Phase | Name | Status |
|-------|------|--------|
| 7 | Error Handling & Recovery Patterns | ✅ Complete |
| 8 | Stateful Actor + Effect Actor Composition | ✅ Complete |
| 9 | Multi-Stage Effect Pipeline | ✅ Complete |
| 10 | Custom Domain Capabilities | ✅ Complete |
| 11 | Effect Actor Documentation | ✅ Complete |

## Key Context
- Roux: `com.cajunsystems:roux:0.2.1` (upgraded from 0.1.0)
- Roux v0.2.0 new API: `Effect.unit/runnable/sleep/when/unless`, `tap()`, `tapError()`, `retry(n)`, `retryWithDelay()`, `retry(RetryPolicy)`, `timeout(Duration)`, `Effects.race/sequence/traverse/parAll()`, `Resource<A>` with `make/fromCloseable/use/ensuring`
- Roux v0.2.0: `DefaultEffectRuntime` now `AutoCloseable` — `ActorEffectRuntime.close()` overridden as no-op (executor owned by ActorSystem, not the runtime)
- Roux v0.2.0 compose() contract: handlers used with `compose()`/`orElse()` MUST implement `CapabilityHandler<Capability<?>>` and throw `UnsupportedOperationException` for unhandled types — `widen()` is just a cast, adds NO type-checking. **Preferred**: `CapabilityHandler.builder().on(Type.class, fn).build()` — auto-throws UOE for unregistered types
- `ConsoleLogHandler` updated to `CapabilityHandler<Capability<?>>` (was `<LogCapability>`) using builder pattern
- Stateless handlers: `private static final CapabilityHandler<Capability<?>> DELEGATE = CapabilityHandler.builder()...build();` — shared across instances
- Stateful handlers (e.g. MetricsHandler): instance-level `delegate` field built in constructor (closures capture instance state)
- **Roux TimeoutException**: `com.cajunsystems.roux.exception.TimeoutException` — NOT `java.util.concurrent.TimeoutException`; assert with `getClass().getName().contains("TimeoutException")` or just use `catchAll` (no instanceof needed)
- **`timeout().catchAll(...)` → `Effect<Throwable,...>`**: test methods must declare `throws Throwable` (not `Exception`)
- **`retry(n)` counting**: n = ADDITIONAL attempts (not total). `retry(2)` = 3 total (1 initial + 2 retries)
- **`retryWithDelay`/`retry(RetryPolicy)` widen to `Throwable`**: test methods need `throws Throwable`
- **`tap()`**: fires on SUCCESS only; passes value through unchanged; does not alter error type
- **`tapError()`**: fires on FAILURE only; re-throws original error unchanged (observe-and-rethrow, NOT recovery)
- **Local sealed interfaces**: Java 21 does NOT allow sealed interfaces inside a method body — define as static nested type in the test class
- `Effects.parAll(List<Effect<E,A>>)` — error type widens to Throwable; test methods need `throws Throwable`
- `Effects.race(Effect<E,A>, Effect<E,A>)` — returns whichever completes first; error type widens to Throwable
- `Effects.traverse(List<A>, Function<A, Effect<E,B>>)` — sequential, error type stays as E (not widened)
- `effect.timeout(Duration)` — widens error type to Throwable; throws Roux TimeoutException on deadline
- Roux v0.2.0: `Either` gains `map/flatMap/fold/swap`; `Tuple2/Tuple3` renamed `first()/second()/third()` — Cajun does NOT use Tuple2/Tuple3, no migration needed
- Roux v0.2.1 fix: scoped fork inherits parent `ExecutionContext` including capability handlers
- Roux v0.2.1: `MissingCapabilityHandlerException` with concrete capability type in message
- Roux v0.2.1: `Fiber.join()` no longer double-wraps runtime exceptions
- Effect actor API: `EffectActorBuilder`, `ActorSystemEffectExtensions`, `ActorEffectRuntime`
- Capabilities: `LogCapability` (sealed, 4 variants) + `ConsoleLogHandler`
- Examples live in: `lib/src/test/java/examples/`
- Existing effect example: `EffectActorExample.java` (3 basic tests)
- Error handling examples: `EffectErrorHandlingExample.java` (5 tests), `EffectRetryExample.java` (3 tests)
- `attempt()` widens error type to Throwable — test methods using it need `throws Throwable`
- `@SuppressWarnings("unchecked")` required when casting `Either.Left`/`Either.Right`
- Retry pattern: recursive `catchAll` chain — `Effect.suspend(supplier)` re-evaluates supplier on each attempt
- Docs live in: `docs/` (stale `effect_monad_guide.md` references old API)
- Target audience: Cajun library users — examples should be self-contained
- Stateful phase: use ask-pattern composition (StatefulHandler + EffectActorBuilder side-by-side)
- StatefulActor journals messages BEFORE processing — all message/state types MUST implement Serializable
- Use UUID-based actor IDs in tests with StatefulHandler to avoid cross-run journal accumulation
- EffectActorBuilder actors don't expose ActorContext — use embedded `Pid replyTo` in request for replies
- `system.statefulActorOf(handlerInstance, initialState)` required when handler has constructor args
- EffectActorBuilder pipeline wiring: build sink-first, capture downstream Pid in upstream lambda closure
- EffectActorBuilder message types do NOT need Serializable (no message journaling unlike StatefulActor)
- ClusterModeTest.testRemoteActorCommunication fails intermittently (requires etcd) — pre-existing, not our issue
- Use `record Batch(List<String> items)` wrapper — raw generic types as message types break EffectActorBuilder type inference
- `AtomicInteger` cursor works safely in dispatcher lambda (actor processes one batch at a time)
- Custom `Capability<R>`: sealed interface extending `Capability<R>` directly (not with generic type param), e.g. `sealed interface ValidationCapability extends Capability<Boolean>`
- `@SuppressWarnings("unchecked")` lives in the handler's `handle()` implementation, not at the `ctx.perform()` call site
- `ctx.perform(cap)` return type inferred from assignment target — `Boolean valid = ctx.perform(new ValidationCapability.IsNonEmpty(...))` works
- `CapabilityHandler.compose(h1, h2, h3)` accepts raw unwidened handlers; returns `CapabilityHandler<Capability<?>>`
- `EffectActorBuilder.withCapabilityHandler(handler)` accepts `CapabilityHandler<Capability<?>>` — always call `.widen()` before passing
- `Effect.from(cap)` + `withCapabilityHandler(h.widen())` = handler injected at spawn time (testable/swappable)
- `Effect.generate(ctx -> ..., handler)` = handler baked into effect; no `withCapabilityHandler()` needed
- `Effect.generate()` requires `.widen()` on the handler — pass `handler.widen()`, not the raw handler
- `CapabilityHandler.compose(h1, h2, h3)` accepts raw unwidened handlers; returns `CapabilityHandler<Capability<?>>`

## Decisions Made (Milestone 2)
- Audience: Cajun library users (self-contained, easy to run)
- Stateful approach: ask-pattern composition, not AtomicReference-inside-effect

## Milestone 1 Decisions (carried forward)
- Hard cut: no deprecated wrappers from old Effect<S,E,R> system
- ActorEffectRuntime uses ActorSystem's executor (not virtual threads)
- Roux capabilities (Capability<R>, CapabilityHandler) replace Cajun's own
- Unit.unit() is the public factory (Unit.INSTANCE is private)
