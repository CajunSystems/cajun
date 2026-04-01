# Milestones

## Milestone 1: Cajun × Roux Unified Effect System ✅
**Phases**: 1–6 | **Status**: Complete | **Completed**: 2026-02-27

Replaced Cajun's bespoke `Effect<State,Error,Result>` system with Roux (`com.cajunsystems:roux:0.1.0`).
Added `ActorEffectRuntime`, `EffectActorBuilder`, `ActorSystemEffectExtensions`, `LogCapability`,
and `ConsoleLogHandler`. 341 tests passing, clean build.

---

## Milestone 2: Effect Actor Examples & Documentation ✅
**Phases**: 7–11 | **Status**: Complete | **Completed**: 2026-02-27 | **Tagged**: v0.5.0

8 runnable examples + `docs/effect-actors/` (3 guides). Error handling, stateful composition,
multi-stage pipeline, fan-out dispatcher, custom domain capabilities, testable handlers.
362 tests passing, clean build.

---

## Milestone 3: Roux v0.2.1 Upgrade ✅
**Phases**: 12–16 | **Status**: Complete | **Completed**: 2026-03-27 | **Tagged**: v0.6.0

Upgrade Cajun's Roux dependency from v0.1.0 → v0.2.1. Leverage new APIs: `RetryPolicy`,
`timeout(Duration)`, `Resource<A>`, `Effects.parAll/race/traverse`, `tap/tapError`.
Fix bridge lifecycle (`ActorEffectRuntime.close()`). New concurrency/resource examples.
383 tests passing, clean build.

---

## Milestone 4: Doc Audit & v0.7.0 Release
**Phases**: 17–21 | **Status**: In Progress | **Started**: 2026-04-01

Audit all docs for stale `Effect<State,Error,Result>` API references. Update root README,
archive/redirect legacy effect docs, bump version to 0.7.0, tag release.
