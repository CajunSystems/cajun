# Roadmap: Cajun × Roux Unified Effect System

Replace Cajun's bespoke `functional/` effect system with Roux (`com.cajunsystems:roux`).
Adds an actor-backed `EffectRuntime` so Roux effects in Cajun dispatch through actor
execution rather than virtual threads.

**Depth**: Standard | **Mode**: Interactive
**Branch**: `feature/roux-effect-integration`

---

## ~~Phase 1: Dependency Setup & Build Verification~~ ✅
**Goal**: Roux is on the classpath and the build compiles cleanly with both systems present.

No code deleted yet — establish the foundation before the hard cut.

Plans:
- 1.1 Add `com.cajunsystems:roux:0.1.0` to `lib/build.gradle`
- 1.2 Verify full build compiles with Roux present alongside old effect system
- 1.3 Smoke test — confirm Roux's `DefaultEffectRuntime` runs correctly in Cajun's test environment

---

## ~~Phase 2: Implement ActorEffectRuntime~~ ✅
**Goal**: A working `ActorEffectRuntime` that dispatches Roux effects through the actor system's executor, not a virtual-thread pool.

This is the core deliverable — the thing that makes Cajun's integration unique.

Plans:
- 2.1 Analyse Roux's `EffectRuntime` interface and `DefaultEffectRuntime` implementation
- 2.2 Implement `ActorEffectRuntime` — delegates `ExecutorService` to `ActorSystem`'s executor
- 2.3 Test basic effect execution, error handling, and fiber fork/join through `ActorEffectRuntime`
- 2.4 Test concurrency — confirm effects run on actor threads, not virtual threads

---

## ~~Phase 3: Remove Old Effect Machinery~~ ✅
**Goal**: Every line of the old Cajun effect system is gone. Build still compiles.

Hard cut: `Effect<State,Error,Result>`, `Trampoline`, `EffectGenerator`, `GeneratorContext`,
`EffectMatchBuilder`, `EffectConversions`, `ThrowableEffect` — all deleted.

Plans:
- 3.1 Delete core monad files: `Effect.java`, `EffectResult.java`, `ThrowableEffect.java`, `ThrowableEffectMatchBuilder.java`, `EffectMatchBuilder.java`, `EffectConversions.java`
- 3.2 Delete generator files: `EffectGenerator.java`, `GeneratorContext.java`, `GeneratorContextImpl.java`, `functional/internal/Trampoline.java`
- 3.3 Delete all 7 old effect tests (`EffectCheckedExceptionTest`, `EffectGeneratorTest`, `EffectInterruptionTest`, `EffectResultTest`, `NewEffectOperatorsTest`, `ThrowableEffectTest`, `TrampolineTest`)
- 3.4 Fix any compile errors in remaining code that referenced old types

---

## ~~Phase 4: Migrate Capabilities~~ ✅
**Goal**: Cajun's `LogCapability` and `ConsoleLogHandler` work through Roux's `Capability<R>` / `CapabilityHandler` model.

Cajun's own `Capability.java` and `CapabilityHandler.java` are deleted — Roux's versions are used directly.

Plans:
- 4.1 Delete `functional/capabilities/Capability.java` and `functional/capabilities/CapabilityHandler.java` (superseded by Roux)
- 4.2 Rewrite `LogCapability.java` as sealed Roux `Capability<R>` implementations
- 4.3 Rewrite `ConsoleLogHandler.java` implementing Roux's `CapabilityHandler` interface
- 4.4 Integration test — run `LogCapability` through `ActorEffectRuntime` end-to-end

---

## ~~Phase 5: Rewrite Effect Builders~~ ✅
**Goal**: `EffectActorBuilder` and `ActorSystemEffectExtensions` expose a clean Roux-native API.

The public surface that Cajun users interact with when writing effect-based actors.

Plans:
- 5.1 Rewrite `EffectActorBuilder.java` — spawns actors that execute Roux `Effect<E, A>` pipelines via `ActorEffectRuntime`
- 5.2 Rewrite `ActorSystemEffectExtensions.java` — Roux-native extension methods on `ActorSystem`
- 5.3 Integration tests for effect-based actors using the new builder API

---

## ~~Phase 6: Tests, Examples & Final Validation~~ ✅
**Goal**: Test suite is green, examples updated, no traces of old Cajun effect types remain.

Plans:
- 6.1 Write comprehensive new tests: `ActorEffectRuntimeTest`, `CapabilityIntegrationTest`, `EffectActorBuilderTest`
- 6.2 Update example files referencing old Cajun effect types
- 6.3 Run full test suite (`./gradlew test`) — fix any remaining failures
- 6.4 Final audit — grep codebase for old Cajun effect imports; confirm clean slate

---

## Summary — Milestone 1

| Phase | Name | Key Output |
|-------|------|------------|
| 1 | Dependency Setup | Roux on classpath, build green |
| 2 | ActorEffectRuntime | Actor-backed runtime implemented & tested |
| 3 | Remove Old Machinery | Old `functional/` effect system deleted |
| 4 | Migrate Capabilities | LogCapability + ConsoleLogHandler on Roux |
| 5 | Rewrite Effect Builders | EffectActorBuilder + ActorSystemEffectExtensions on Roux |
| 6 | Tests & Validation | Green test suite, clean codebase |

**Files deleted**: ~16 production + 7 test files
**Files rewritten**: 4 (capabilities + builders)
**Files created**: 1 (`ActorEffectRuntime.java`) + new tests

---

---

## ~~Milestone 2: Effect Actor Examples & Documentation~~ ✅ `v0.5.0`

8 runnable examples + `docs/effect-actors/` (3 guides) — error handling, stateful composition, pipelines, fan-out, custom capabilities. → [Archive](.planning/milestones/v0.5.0-ROADMAP.md)

---

---

# Milestone 3: Roux v0.2.1 Upgrade

Upgrade Cajun's Roux dependency from `v0.1.0` → `v0.2.1`. Leverage the new API surface:
`RetryPolicy`, `timeout(Duration)`, `Resource<A>`, `Effects.parAll/race/traverse`, `tap/tapError`.
Fix bridge lifecycle (`ActorEffectRuntime.close()` must not shut down actor executor).
New examples demonstrating concurrency and resource management patterns.

**Depth**: Standard | **Mode**: Interactive
**Phases**: 12–16

---

## Phase 12: Upgrade & Compatibility
**Goal**: Bump `roux` to `0.2.1`, verify the full build compiles and all 362 tests stay green. Fix any breaking changes.

Key tasks:
- 12.1 Bump `roux = "0.2.1"` in `gradle/libs.versions.toml`; run `./gradlew build` and fix any compile errors
- 12.2 Override `ActorEffectRuntime.close()` to be a no-op — `DefaultEffectRuntime` is now `AutoCloseable` but the executor belongs to the `ActorSystem`, not this runtime
- 12.3 Run full test suite; confirm 362+ tests green; document any API-surface changes observed

---

## ~~Phase 13: Bridge — Concurrency & Timeout~~ ✅
**Goal**: Verify and test `Effects.parAll()`, `Effects.race()`, `Effects.traverse()`, and `timeout(Duration)` through `ActorEffectRuntime`. Confirm v0.2.1 scoped-fork capability inheritance fix works.

Plans: 13.1 (complete — 9 tests added, 371 total, all green)

---

## Phase 14: Modernize Retry & Error Examples
**Goal**: Replace `EffectRetryExample`'s hand-rolled `withRetry(catchAll)` with Roux's built-in `RetryPolicy`. Add `tap()` / `tapError()` patterns. New timeout example.

Plans: 14.1 (pending)

---

## Phase 15: New Concurrency & Resource Examples
**Goal**: Demonstrate `Effects.parAll()` / `Effects.race()` / `Effects.traverse()` and `Resource<A>` in idiomatic effect actor patterns.

Key tasks:
- 15.1 Write `EffectParallelExample.java` — `Effects.parAll()` to fan-out work and collect results; `Effects.race()` to return first winner; `Effects.traverse()` over a collection
- 15.2 Write `EffectResourceExample.java` — `Resource<A>` for actors that acquire/release managed state (e.g., a connection pool mock); demonstrate guaranteed cleanup on success and failure

---

## Phase 16: Documentation Update
**Goal**: Update all `docs/effect-actors/` files to cover new Roux v0.2.x API. Add v0.1.0 → v0.2.1 migration notes.

Key tasks:
- 16.1 Update `docs/effect-actors/getting-started.md` — `Effect.unit()`, `Effect.runnable()`, `tap()`, built-in retry quickstart
- 16.2 Update `docs/effect-actors/patterns.md` — replace manual retry pattern with `RetryPolicy`; add timeout, `Effects.parAll/race/traverse`, `Resource<A>` patterns
- 16.3 Update `docs/effect-actors/capabilities.md` — `MissingCapabilityHandlerException` diagnostics; scoped-fork capability inheritance
- 16.4 Add v0.1.0 → v0.2.1 migration guide section (or new file `docs/effect-actors/migration.md`)

---

## Summary — Milestone 3

| Phase | Name | Key Output |
|-------|------|------------|
| 12 | Upgrade & Compatibility | Roux 0.2.1, green build, AutoCloseable fix |
| 13 | Bridge Concurrency & Timeout | parAll/race/timeout tests via ActorEffectRuntime |
| 14 | Modernize Retry & Errors | RetryPolicy, tap/tapError, EffectTimeoutExample |
| 15 | Concurrency & Resource Examples | EffectParallelExample, EffectResourceExample |
| 16 | Documentation Update | All 4 docs updated + migration guide |
