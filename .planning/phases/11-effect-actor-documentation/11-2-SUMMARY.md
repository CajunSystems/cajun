# Phase 11, Plan 2 Summary: Patterns Catalogue + effect_monad_guide Update

## Status: Complete

## Tasks Completed

### Task 1 — docs/effect-actors/patterns.md
**Commit**: 0976636
- Created `docs/effect-actors/patterns.md`
- 5 patterns with full code snippets:
  1. Error handling: `catchAll`, `orElse`, `mapError`, `attempt`, retry-with-backoff
  2. Request-response (ask pattern) via embedded `Pid replyTo`
  3. Stateful + Effect composition with `StatefulHandler` and UUID-based IDs
  4. Linear pipeline with sink-first wiring and Pid closure capture
  5. Fan-out dispatcher with round-robin via `AtomicInteger` cursor
- Example Index table (9 examples, patterns covered)
- All constraints from STATE.md documented inline

### Task 2 — docs/effect_monad_guide.md (rewrite)
**Commit**: e2150b7
- Replaced 1153-line stale old-API document with 32-line deprecation notice
- Includes: deprecation notice, links to all 3 new guides, Old→New API migration table,
  runnable example commands
- No references to deleted API remain in the file

### Task 3 — Link verification
- All 12 linked files confirmed present (3 docs + 9 example files)
- No broken links

## Deviations
None. Both files matched the plan content exactly.

## Verification Checklist
- [x] `docs/effect-actors/patterns.md` written — covers all 5 patterns with code snippets
- [x] `docs/effect_monad_guide.md` rewritten — deprecation notice, links, API migration table
- [x] All links in all four `docs/effect-actors/` files resolve to real files
- [x] No references to old deleted API in any new doc
