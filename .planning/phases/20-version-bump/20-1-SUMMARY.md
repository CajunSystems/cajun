# Phase 20, Plan 1 Summary: Version Bump

## Status: Complete

## Tasks Completed

### Task 1 — Bump `gradle.properties`
**Commit**: 32114c2

`cajunVersion=0.4.0` → `cajunVersion=0.7.0`

Single-line change; all 7 submodule `build.gradle` files read from this property so no
further changes needed there.

### Task 2 — Update `README.md` installation snippets
**Commit**: 7b010cd

- Gradle snippet (line 167): `cajun:0.4.0` → `cajun:0.7.0`
- Maven snippet (line 177): `<version>0.4.0</version>` → `<version>0.7.0</version>`

### Task 3 — Add `[0.7.0]` CHANGELOG entry
**Commit**: 67a431d

Prepended new `## [0.7.0] - 2026-04-01` section above `[0.4.0]`. Entry covers:
- Added: `ActorEffectRuntime`, `EffectActorBuilder`, `ActorSystemEffectExtensions`,
  `LogCapability`, `ConsoleLogHandler`, 11 examples, `docs/effect-actors/` (4 guides)
- Changed: Roux `0.1.0` → `0.2.1`, `ActorEffectRuntime.close()` no-op fix,
  `CapabilityHandler.builder()` migration
- Removed: Entire `functional/` old effect system (hard cut)

## Deviations

None.

## Key Notes
- `0.7.0` chosen (not `0.5.0`) because `gradle.properties` was never updated during
  milestones 2 or 3 — skipping reflects all three milestones of unreleased work
- 7 `build.gradle` fallback values (`?: '0.4.0'`) intentionally left unchanged — dead
  code in practice, noise-to-value ratio too high
