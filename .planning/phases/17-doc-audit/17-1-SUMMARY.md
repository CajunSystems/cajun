# Phase 17, Plan 1 Summary: Doc Audit

## Status: Complete

## Tasks Completed

### Tasks 1 & 2 — Verify stale content in all target files
**Commit**: c18c94b

Confirmed first stale reference in each archive-candidate (no redirect headers present):
- `effect_monad_api.md`: line 12 — `Effect<State, Error, Result>` in Key Features
- `throwable_effect_api.md`: line 3 — `ThrowableEffect<State, Result>` in Overview
- `functional_actor_evolution.md`: line 1 — title references `FunctionalActor`
- `effect_monad_new_features.md`: line 5 — `Effect.modify(s -> s)` in section 1

Root README.md stale refs confirmed at lines 965, 969, 973, 1004, 1010, 1011, 1017, 1019 (old `Effect.modify/tell/tellSelf` calls) and lines 1042–1043 (stale doc links).

### Task 3 — Write audit record
**Commit**: c18c94b

Created `17-1-AUDIT.md` with:
- Full classification table covering all 25 doc files
- Per-file detail for the 5 files needing changes (first stale ref + line number)
- Phase mapping: which action goes in which phase (18, 19, 20, 21)

### Task 4 — Update STATE.md
**Commit**: (metadata commit)

Updated current status to Phase 17 ✅ Complete, added Milestone 4 Audit Findings section with exact line numbers.

## Deviations

None.

## Key Notes
- 20 of 25 docs are clean — no stale Effect API refs
- The 4 legacy effect docs need redirect/archive headers only — not deletion (they're historical record)
- Root README effect-actor section (lines ~955–1050) needs a rewrite, not just link fixes
- `docs/effect_monad_guide.md` is already handled correctly — has redirect header since Milestone 1
