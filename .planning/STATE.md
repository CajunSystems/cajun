# Project State

## Current Status
**Milestone**: 3 — Roux v0.2.1 Upgrade
**Phase**: 12 (not yet started)
**Status**: Planning
**Branch**: new branch per phase (merge `feature/roux-effect-integration` to main first)
**Last Updated**: 2026-03-03

## Milestone 3 Phase Progress

| Phase | Name | Status |
|-------|------|--------|
| 12 | Upgrade & Compatibility | ⬜ Not started |
| 13 | Bridge Concurrency & Timeout | ⬜ Not started |
| 14 | Modernize Retry & Error Examples | ⬜ Not started |
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
- Roux: upgrading `com.cajunsystems:roux:0.1.0` → `0.2.1` (Maven Central)
- Roux v0.2.0 new API: `Effect.unit/runnable/sleep/when/unless`, `tap()`, `tapError()`, `retry(n)`, `retryWithDelay()`, `retry(RetryPolicy)`, `timeout(Duration)`, `Effects.race/sequence/traverse/parAll()`, `Resource<A>` with `make/fromCloseable/use/ensuring`
- Roux v0.2.0: `DefaultEffectRuntime` now `AutoCloseable` — `ActorEffectRuntime.close()` must be overridden (no-op; executor owned by ActorSystem)
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
