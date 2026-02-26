# Project State

## Current Status
**Phase**: 1 — Dependency Setup & Build Verification
**Status**: Not started
**Branch**: `feature/roux-effect-integration`
**Last Updated**: 2026-02-26

## Active Phase
**Phase 1: Dependency Setup & Build Verification**

Goal: Add `com.cajunsystems:roux:0.1.0` to Cajun's build and confirm everything compiles.

Next action: `/gsd:plan-phase 1`

## Phase Progress

| Phase | Name | Status |
|-------|------|--------|
| 1 | Dependency Setup & Build Verification | ⬜ Not started |
| 2 | ActorEffectRuntime | ⬜ Not started |
| 3 | Remove Old Effect Machinery | ⬜ Not started |
| 4 | Migrate Capabilities | ⬜ Not started |
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
