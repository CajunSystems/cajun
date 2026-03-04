# Phase 16, Plan 1 Summary: Documentation Update

## Status: Complete

## Tasks Completed

### Task 1 — Update getting-started.md
**Commit**: ed4528b

- Expanded Quick Reference table with 11 new rows covering v0.2.x APIs:
  Effect.unit(), Effect.runnable(), tap(), tapError(), retry(n), retryWithDelay(),
  retry(RetryPolicy), timeout(Duration), Effects.parAll/race/traverse
- Fixed stale .widen() note (no longer says "widens LogCapability")

### Task 2 — Update patterns.md
**Commit**: be8e8d6

- Replaced manual withRetry() recursive helper with built-in retry API (retry(n),
  retryWithDelay, RetryPolicy)
- Added Section 6: tap / tapError — observe without altering
- Added Section 7: Timeout with fallback
- Added Section 8: Parallel / Sequential Combinators (parAll/race/traverse)
- Added Section 9: Resource Management (Resource<A>)
- Updated Example Index to include EffectRetryExample, EffectTimeoutExample,
  EffectParallelExample, EffectResourceExample

### Task 3 — Update capabilities.md
**Commit**: 4c837af

- Fixed ConsoleLogHandler description: now shows CapabilityHandler<Capability<?>>
- Updated "Implementing a CapabilityHandler" section: CapabilityHandler.builder()
  as preferred approach; switch pattern shown as alternative
- Updated "Stateful Handlers" section: MetricsHandler now uses builder with
  constructor-captured delegate (closures capture instance state)
- Added "MissingCapabilityHandlerException Diagnostics" section: 3 common causes
  with fixes
- Added "Scoped-Fork Capability Inheritance" section: v0.2.1 fix documented with
  example showing parent+child capability handler inheritance

### Task 4 — Create migration.md
**Commit**: 054bcda

New file docs/effect-actors/migration.md covering:
- Breaking change: DefaultEffectRuntime AutoCloseable + ActorEffectRuntime.close() fix
- Breaking change: compose()/orElse() handler UOE contract
- New APIs: factory methods, tap/tapError, built-in retry, timeout, parallel combinators, Resource<A>
- Either additions: fold, map, flatMap, swap
- Version compatibility table

## Deviations

None.

## Key Notes
- All docs now consistent with Roux 0.2.1 and current codebase (post-Phase 12 handler refactor)
- migration.md is a new file — not referenced from getting-started.md Next Steps or patterns.md
  (could add links in a follow-up if desired)
