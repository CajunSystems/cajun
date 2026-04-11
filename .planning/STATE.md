# Project State

## Current Status
**Milestone**: 5 — Cluster Evaluation & Enhancement
**Phase**: 28 — Complete
**Status**: Phase 28 complete — ready to begin Phase 29 (Performance Optimization)
**Branch**: feature/roux-effect-integration
**Last Updated**: 2026-04-11

## Milestone 5 Phase Progress

| Phase | Name | Status |
|-------|------|--------|
| 22 | Cluster & Persistence Audit | ✅ Complete |
| 23 | Serialization Framework | ✅ Complete |
| 24 | Redis Persistence Design | ✅ Complete |
| 25 | Redis Persistence Provider | ✅ Complete |
| 26 | Cluster + Shared Persistence Integration | ✅ Complete |
| 27 | Observability & Diagnostics | ✅ Complete |
| 28 | Reliability Hardening | ✅ Complete |
| 29 | Performance Optimization | 🔲 Not started |
| 30 | Cluster Management API | 🔲 Not started |
| 31 | Testing, Documentation & Examples | 🔲 Not started |

## Milestone 4 Phase Progress (archived — v0.7.0)

| Phase | Name | Status |
|-------|------|--------|
| 17 | Doc Audit | ✅ Complete |
| 18 | Update Root README | ✅ Complete |
| 19 | Archive/Redirect Old Effect Docs | ✅ Complete |
| 20 | Version Bump | ✅ Complete |
| 21 | Release Validation | ✅ Complete |

## Milestone 4 Audit Findings (Phase 17)
- 4 docs need ARCHIVE action: `effect_monad_api.md` (line 12), `throwable_effect_api.md` (line 3), `functional_actor_evolution.md` (line 1), `effect_monad_new_features.md` (line 5)
- Root `README.md` needs UPDATE: stale `Effect.modify/tell/tellSelf` at lines 965, 969, 973, 1004, 1010, 1011, 1017, 1019 + stale doc links at lines 1042–1043
- 20 other docs: clean, no changes needed
- `docs/effect_monad_guide.md`: already has redirect header — no action needed

## Milestone 3 Phase Progress (archived — v0.6.0)

| Phase | Name | Status |
|-------|------|--------|
| 12 | Upgrade & Compatibility | ✅ Complete |
| 13 | Bridge Concurrency & Timeout | ✅ Complete |
| 14 | Modernize Retry & Error Examples | ✅ Complete |
| 15 | New Concurrency & Resource Examples | ✅ Complete |
| 16 | Documentation Update | ✅ Complete |

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
- **`resource.use()` and `Resource.ensuring()` widen to `Throwable`**: test methods need `throws Throwable`
- **`Resource.fromCloseable(effect)`**: shorthand for `AutoCloseable`; release = `close()` automatically
- **`Resource.make(acquire, release)`**: full control; release always runs on success AND failure
- **`Resource.ensuring(effect, finalizer)`**: try-finally pattern; finalizer runs regardless of outcome
- **`Resource.flatMap()` caveat**: simplified; prefer nested `use()` for resources that depend on each other
- **`traverse()` does NOT widen**: error stays as `E`; only `parAll`/`race`/`timeout`/`retry(Policy)` widen to `Throwable`
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

## Decisions Made (Milestone 5 — Phase 28)
- `NodeCircuitBreaker` per-node (not per-actor): one node failure blocks all messages to that node; `failureThreshold=5`, `resetTimeoutMs=30s` defaults
- Circuit breaker implemented with `synchronized` + `volatile` — simpler than lock-free for low-contention send path
- `ExponentialBackoff` wraps only idempotent etcd ops (`put/get/delete/listKeys`); `acquireLock` excluded (double-acquire risk); watch/connect/close excluded
- Graceful degradation via `exceptionally()` on `metadataStore.get()` future — zero overhead on happy path; WARN on cache hit, ERROR on cache miss (message dropped)
- `DegradedRoutingTest` key prefix: `ClusterActorSystem.ACTOR_ASSIGNMENT_PREFIX` is `"cajun/actor/"` — test corrected to match

## Decisions Made (Milestone 5 — Phase 27)
- `ClusterMetrics` and `PersistenceMetrics` placed in `cajun-core/src/main/java/com/cajunsystems/metrics/` — `ReliableMessagingSystem` is in `cajun-core` so metrics must be co-located
- `ClusterMetrics` injected into `ReliableMessagingSystem` via optional setter `setClusterMetrics()` with null guards — two copies of `ReliableMessagingSystem` exist (cajun-core + lib), both updated
- `ClusterHealthStatus` record: `healthy = persistenceHealthy && messagingSystemRunning`; `persistenceHealthy=true` when no provider configured (backward compat)
- MDC cleared via try-finally in `doSendMessage()` and `handleClient()` — prevents leakage on exception
- `logback.xml` pattern: `[%X{actorId}][%X{messageId}]` added — empty strings for non-cluster log lines

## Decisions Made (Milestone 5 — Phase 26)
- `ClusterActorSystem.withPersistenceProvider(PersistenceProvider)` fluent setter; `setupPersistence()` called in `start()` before heartbeat/leader election — no-op if null
- Persistence health check at startup: WARN log (not fail-fast) to preserve backward compat
- `StatefulActorClusterStateTest`: @Disabled removed, `@Tag("requires-redis")` added, shared `RedisPersistenceProvider` used for both nodes — test now asserts count=6
- Original bug-doc test kept `@Disabled` at method level as historical documentation
- `PersistenceBenchmarkTest`: `@Tag("performance")` only; Redis tests also `@Tag("requires-redis")`; N=500 messages; no latency SLA assertions

## Decisions Made (Milestone 5 — Phase 25)
- Redis journal key: `{prefix}:journal:{actorId}` (actorId in `{}` for Cluster co-location); seq counter: `{prefix}:journal:{actorId}:seq`
- Redis snapshot key: `{prefix}:snapshot:{actorId}` — single key per actor, overwrite semantics
- `RedisPersistenceProvider` defaults to `JavaSerializationProvider`; integration tests use `KryoSerializationProvider` explicitly
- Mocking Lettuce `RedisFuture` in tests: use concrete anonymous `RedisFuture` implementation wrapping `CompletableFuture` — avoids Mockito strict-stubbing issues with default `CompletionStage` interface methods
- Integration tests tagged `@Tag("requires-redis")` — excluded from default Gradle test task in both `cajun-persistence` and `lib`

## Decisions Made (Milestone 2)
- Audience: Cajun library users (self-contained, easy to run)
- Stateful approach: ask-pattern composition, not AtomicReference-inside-effect

## Milestone 1 Decisions (carried forward)
- Hard cut: no deprecated wrappers from old Effect<S,E,R> system
- ActorEffectRuntime uses ActorSystem's executor (not virtual threads)
- Roux capabilities (Capability<R>, CapabilityHandler) replace Cajun's own
- Unit.unit() is the public factory (Unit.INSTANCE is private)
