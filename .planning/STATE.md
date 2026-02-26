# Project State

## Current Status
**Phase**: 4 — Migrate Capabilities
**Status**: Active
**Branch**: `feature/roux-effect-integration`
**Last Updated**: 2026-02-26

## Active Phase
**Phase 4: Migrate Capabilities**

Goal: Cajun's `LogCapability` and `ConsoleLogHandler` work through Roux's `Capability<R>` / `CapabilityHandler` model.

Next action: `/gsd:plan-phase 4`

## Phase Progress

| Phase | Name | Status |
|-------|------|--------|
| 1 | Dependency Setup & Build Verification | ✅ Complete |
| 2 | ActorEffectRuntime | ✅ Complete |
| 3 | Remove Old Effect Machinery | ✅ Complete |
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
