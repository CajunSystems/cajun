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

# Milestone 2: Effect Actor Examples & Documentation

Provide non-trivial, runnable examples and developer-facing documentation for
Cajun's Roux-native effect actor API. Targets Cajun library users learning the API.

**Depth**: Standard | **Mode**: Interactive
**Phases**: 7–11

---

## Phase 7: Error Handling & Recovery Patterns
**Goal**: Demonstrate `Effect.fail`, `mapError`, and recovery composing inside effect actors.

Show Cajun users how to express validation, typed errors, and graceful degradation — the
patterns they'll need immediately when writing real effect actors.

Plans:
- 7.1 Research Roux's error operator surface (`mapError`, `catchAll`, `recover`, `fold`)
- 7.2 Write `EffectErrorHandlingExample.java` — validation actor with fail/recover/mapError
- 7.3 Write `EffectRetryExample.java` — retry-with-backoff via Effect chain

---

## Phase 8: Stateful Actor + Effect Actor Composition
**Goal**: Show `StatefulHandler` and `EffectActorBuilder` actors working side-by-side via the ask pattern.

Demonstrates the two models interoperating: a `StatefulActor` holds domain state (shopping cart)
and an effect actor performs enrichment/logging, with the stateful actor calling the effect actor
via ask to get computed values.

Plans:
- 8.1 Write `EffectStatefulCompositionExample.java` — shopping cart stateful actor + effect enrichment actor
- 8.2 Write `EffectAskPatternExample.java` — effect actor replies to ask-pattern requests

---

## Phase 9: Multi-Stage Effect Pipeline
**Goal**: A multi-actor processing pipeline where each stage is an effect actor.

Replaces `WorkflowExample`-style imperative chains with effect-native equivalents.
Source → Enrich → Validate → Sink, each stage using `EffectActorBuilder` with capabilities.

Plans:
- 9.1 Write `EffectPipelineExample.java` — 4-stage pipeline with LogCapability at each step
- 9.2 Write `EffectFanOutExample.java` — fork to multiple effect actors, collect results

---

## Phase 10: Custom Domain Capabilities
**Goal**: Show users how to define and compose custom capabilities for realistic scenarios.

Define `ValidationCapability` and `MetricsCapability` (in-memory mock), compose with
`ConsoleLogHandler` via `CapabilityHandler.compose()`. Demonstrates the real power of
the capability pattern for dependency injection in actors.

Plans:
- 10.1 Write `EffectCapabilityExample.java` — ValidationCapability + MetricsCapability composition
- 10.2 Write `EffectTestableCapabilityExample.java` — swappable handlers for testing vs production

---

## Phase 11: Effect Actor Documentation
**Goal**: Developer-facing markdown docs covering the full Roux-native effect actor API.

New `docs/effect-actors/` directory with getting-started, capability pattern, and pattern
catalogue guides. Update stale `docs/effect_monad_guide.md` to reference Roux API.

Plans:
- 11.1 Write `docs/effect-actors/getting-started.md` — intro and first actor guide
- 11.2 Write `docs/effect-actors/capabilities.md` — capability pattern and custom capabilities
- 11.3 Write `docs/effect-actors/patterns.md` — error handling, pipeline, stateful composition
- 11.4 Update `docs/effect_monad_guide.md` — remove old API references, link to new guides

---

## Summary — Milestone 2

| Phase | Name | Key Output |
|-------|------|------------|
| 7 | Error Handling & Recovery | fail/mapError/recover examples |
| 8 | Stateful + Effect Composition | ask-pattern interop example |
| 9 | Multi-Stage Pipeline | 4-stage effect pipeline + fan-out |
| 10 | Custom Domain Capabilities | ValidationCapability + MetricsCapability |
| 11 | Effect Actor Documentation | docs/effect-actors/ + updated guides |

**Files created**: ~8 new example files, 3 new doc files
**Files updated**: 1 existing doc
