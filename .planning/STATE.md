# Project State

## Current Status
**Phase**: 5 — Rewrite Effect Builders
**Status**: Active
**Branch**: `feature/roux-effect-integration`
**Last Updated**: 2026-02-26

## Active Phase
**Phase 5: Rewrite Effect Builders**

Goal: `EffectActorBuilder` and `ActorSystemEffectExtensions` expose a clean Roux-native API.

Next action: `/gsd:plan-phase 5`

## Phase Progress

| Phase | Name | Status |
|-------|------|--------|
| 1 | Dependency Setup & Build Verification | ✅ Complete |
| 2 | ActorEffectRuntime | ✅ Complete |
| 3 | Remove Old Effect Machinery | ✅ Complete |
| 4 | Migrate Capabilities | ✅ Complete |
| 5 | Rewrite Effect Builders | ⬜ Not started |
| 6 | Tests, Examples & Validation | ⬜ Not started |

## Key Context
- Roux: `com.cajunsystems:roux:0.1.0` (Maven Central)
- Old effect files in: `lib/src/main/java/com/cajunsystems/functional/`
- Old tests in: `lib/src/test/java/com/cajunsystems/functional/`
- Cluster code: untouched throughout
- Persistence layer: untouched throughout
- Core actor API (`Handler`, `StatefulHandler`, `Pid`, `ActorSystem`): must not break

## Decisions Made
- Hard cut: no deprecated wrappers from old Effect<S,E,R> system
- ActorEffectRuntime uses ActorSystem's executor (not virtual threads)
- Roux capabilities (Capability<R>, CapabilityHandler) replace Cajun's own
