# Phase 18, Plan 1 Summary: Update Root README

## Status: Complete

## Tasks Completed

### Task 1 — Replace "Functional Actors with Effects" section
**Commit**: 195129b

Replaced README.md lines 944–1045 with current Roux-native API content:

**Removed:**
- `Effect<Integer, Throwable, Void>` 3-param match-builder example
- `Effect.modify()`, `Effect.tell()`, `Effect.tellSelf()`, `Effect.logState()` snippet blocks
- `fromEffect(system, counterBehavior, 0)` invocation
- Old import: `com.cajunsystems.functional.ActorSystemEffectExtensions.*`
- 3 stale doc links (`effect_monad_guide.md`, `effect_monad_api.md`, `functional_actor_evolution.md`)

**Added:**
- `EffectActorBuilder` + `Effect.suspend()` quick example (sealed interface + switch expression)
- Updated benefits list: `retry(n)`, `timeout(Duration)`, `Effects.parAll/race/traverse`, `Resource<A>`
- Pattern snippets using current `Effect<E,A>` API: `flatMap`, `catchAll`, `retry`, `parAll`, `Resource`
- 4 links to `docs/effect-actors/`: getting-started, patterns, capabilities, migration

## Deviations

None.

## Key Notes
- Only remaining match for stale pattern grep: line 1031 in the Migration Guide link description
  (`"upgrading from old Effect<State,Error,Result> API"`) — intentional, describes what the guide covers
- Net change: -64 lines / +51 lines (removed more old content than added)
