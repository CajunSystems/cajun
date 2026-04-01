# Phase 19, Plan 1 Summary: Archive/Redirect Old Effect Docs

## Status: Complete

## Tasks Completed

### Tasks 1–4 — Redirect/historical headers on 4 legacy docs
**Commit**: d655bad

Prepended headers to all 4 files:

| File | Header type | First line |
|------|-------------|------------|
| `docs/effect_monad_api.md` | Redirect | `> **Note:** This guide documents the old Effect<State, Error, Result> API...` |
| `docs/throwable_effect_api.md` | Redirect | `> **Note:** ThrowableEffect<State, Result> was part of the old Cajun effect system...` |
| `docs/effect_monad_new_features.md` | Redirect | `> **Note:** This document describes feature additions to the old Effect<State, Error, Result> API...` |
| `docs/functional_actor_evolution.md` | Historical | `> **Historical Document:** This document describes the design evolution...` |

Each header includes: context of removal, links to `effect-actors/getting-started.md` and `effect-actors/migration.md`, and a separator line before the original content.

### Task 5 — Add Effect Actors section to `docs/README.md`
**Commit**: 69f09aa

Added two new blocks to `docs/README.md`:

1. **"Effect Actors (Roux)" subsection** under Features & Configuration (after Clustering) — 4 linked entries with descriptions
2. **"Effect Actors (Roux)" category** in Documentation by Category — 4 plain links

Verified: 8 total `effect-actors/` links in `docs/README.md` (4 in Features, 4 in Category).

## Deviations

None.

## Key Notes
- No content deleted from any of the 4 legacy docs — redirect headers prepended, original content preserved below
- `docs/effect_monad_guide.md` was already correctly handled (Milestone 1) — no changes needed
- All 4 new `effect-actors/` links point to files that exist on disk
