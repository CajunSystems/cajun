# Project State

## Current Status
**Milestone**: 2 — Effect Actor Examples & Documentation
**Phase**: 7 (next to plan/execute)
**Status**: In Progress
**Branch**: `main` (merge feature/roux-effect-integration first, then new branch per phase)
**Last Updated**: 2026-02-27

## Milestone 2 Phase Progress

| Phase | Name | Status |
|-------|------|--------|
| 7 | Error Handling & Recovery Patterns | ⏳ Not Started |
| 8 | Stateful Actor + Effect Actor Composition | ⏳ Not Started |
| 9 | Multi-Stage Effect Pipeline | ⏳ Not Started |
| 10 | Custom Domain Capabilities | ⏳ Not Started |
| 11 | Effect Actor Documentation | ⏳ Not Started |

## Key Context
- Roux: `com.cajunsystems:roux:0.1.0` (Maven Central)
- Effect actor API: `EffectActorBuilder`, `ActorSystemEffectExtensions`, `ActorEffectRuntime`
- Capabilities: `LogCapability` (sealed, 4 variants) + `ConsoleLogHandler`
- Examples live in: `lib/src/test/java/examples/`
- Existing effect example: `EffectActorExample.java` (3 basic tests)
- Docs live in: `docs/` (stale `effect_monad_guide.md` references old API)
- Target audience: Cajun library users — examples should be self-contained
- Stateful phase: use ask-pattern composition (StatefulHandler + EffectActorBuilder side-by-side)

## Decisions Made (Milestone 2)
- Audience: Cajun library users (self-contained, easy to run)
- Stateful approach: ask-pattern composition, not AtomicReference-inside-effect

## Milestone 1 Decisions (carried forward)
- Hard cut: no deprecated wrappers from old Effect<S,E,R> system
- ActorEffectRuntime uses ActorSystem's executor (not virtual threads)
- Roux capabilities (Capability<R>, CapabilityHandler) replace Cajun's own
- Unit.unit() is the public factory (Unit.INSTANCE is private)
