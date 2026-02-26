# Cajun × Roux: Unified Effect System

Replace Cajun's internal effect system with Roux (`com.cajunsystems:roux`), providing a single unified effect system across both libraries. Cajun adds an actor-backed `EffectRuntime` so Roux effects running inside the actor system use actor dispatch rather than virtual threads.

## Problem

Cajun has its own bespoke effect monad (`Effect<State, Error, Result>` in `functional/`), its own `EffectGenerator`, `GeneratorContext`, `Trampoline`, and capabilities system. Roux is a purpose-built, production-quality effect system for Java 21+. Running two parallel effect systems creates fragmentation, duplication, and an inconsistent developer experience.

## Solution

- Remove Cajun's `functional/` effect machinery entirely (hard cut, no deprecated wrappers)
- Add `com.cajunsystems:roux` as a Cajun dependency
- Implement `ActorEffectRuntime` — a Roux `EffectRuntime` backed by actor dispatch instead of a virtual-thread `ExecutorService`
- Rewrite `EffectActorBuilder` and `ActorSystemEffectExtensions` using Roux's `Effect<E, A>` API
- Migrate Cajun's `capabilities/` to Roux's `Capability<R>` / `CapabilityHandler` model

The result: one effect type (`Effect<E, A>` from Roux), one execution story, two runtimes — the standard Roux virtual-thread runtime for standalone use, and the actor-backed runtime when running inside Cajun.

## Requirements

### Validated

- ✓ Actor model: Handler, StatefulHandler, ActorSystem, Pid, ActorContext — existing
- ✓ ActorBuilder / StatefulActorBuilder fluent APIs — existing
- ✓ Backpressure management (NORMAL→WARNING→CRITICAL→RECOVERY) — existing
- ✓ Persistence: FileSystem + LMDB backends — existing
- ✓ Clustering: Etcd + gRPC distributed actor system — existing
- ✓ Roux effect system: Effect<E, A>, Fiber, EffectScope, Capability, GeneratorContext — existing in Roux
- ✓ Roux DefaultEffectRuntime (virtual thread backed) — existing in Roux
- ✓ Old Cajun functional/ package: Effect<S,E,R>, Trampoline, EffectGenerator, capabilities/ — exists, to be removed

### Active

- [ ] Add `com.cajunsystems:roux` as a dependency in Cajun (cajun-system / lib modules)
- [ ] Implement `ActorEffectRuntime` implementing Roux's `EffectRuntime<E, A>`, dispatching effect execution through the actor system's executor rather than a virtual-thread pool
- [ ] Remove `functional/Effect.java`, `EffectResult.java`, `ThrowableEffect.java`, `EffectMatchBuilder.java`, `EffectConversions.java` (old monad)
- [ ] Remove Cajun's `functional/internal/Trampoline.java` (Roux handles stack safety internally)
- [ ] Remove Cajun's `functional/EffectGenerator.java`, `GeneratorContext.java`, `GeneratorContextImpl.java` (replaced by Roux equivalents)
- [ ] Migrate `functional/capabilities/` — rewrite `LogCapability`, `ConsoleLogHandler`, etc. using Roux's `Capability<R>` and `CapabilityHandler` interfaces
- [ ] Rewrite `EffectActorBuilder` to spawn actors that handle Roux `Effect<E, A>` pipelines
- [ ] Rewrite `ActorSystemEffectExtensions` to expose Roux-native API on `ActorSystem`
- [ ] Delete or rewrite effect-related tests (`EffectGeneratorTest`, `NewEffectOperatorsTest`) against Roux API
- [ ] Update any example files referencing old Cajun effect types

### Out of Scope

- Cluster mode changes — no Roux effects in distributed message routing (future milestone)
- Persistence layer changes — `StatefulActor` event sourcing is not effect-based
- New capabilities — only migrate what already exists; no new LogCapability variants, etc.
- Roux changes — this work is Cajun-side only; Roux API is consumed as-is

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Hard cut, no deprecated wrappers | Old Effect<S,E,R> type signature is incompatible with Effect<E,A>; bridging would create confusion | Old functional/ package deleted |
| Actor-backed EffectRuntime | Effects in Cajun should use actor dispatch — consistent execution model, no hidden virtual threads | ActorEffectRuntime uses ActorSystem executor |
| Roux as external Maven dependency | Roux is published at `com.cajunsystems:roux:0.1.0`; no copy-pasting or submodule needed | Gradle dependency added |
| Capabilities migrated, not redesigned | Roux's Capability<R>/CapabilityHandler model is the right foundation; Cajun just needs to restate existing caps in that model | functional/capabilities/ rewritten |
| Cluster untouched | Distributed effects are a larger design question; keep scope tight | No cluster code changes |

## Constraints

- Java 21+ with `--enable-preview` (already required by both Cajun and Roux)
- Roux version: `0.1.0` (published at `com.cajunsystems:roux`)
- Must not break the core actor API (`Handler`, `StatefulHandler`, `ActorSystem`, `Pid`)
- All existing non-functional tests must continue passing

---
*Last updated: 2026-02-26 after initialization*
