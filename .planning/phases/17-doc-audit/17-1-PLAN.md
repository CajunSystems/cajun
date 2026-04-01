# Phase 17, Plan 1: Doc Audit

## Objective

Scan every documentation file for stale references to the deleted `Effect<State,Error,Result>` API, produce a written audit record, and update STATE.md with findings so phases 18–21 can execute confidently.

---

## Context

The old Cajun effect system (`Effect<State,Error,Result>`, `ThrowableEffect`, `EffectMatchBuilder`, `Effect.modify()`, `Effect.tell()`, `EffectGenerator`, etc.) was fully deleted in Milestone 1. A pre-flight audit using the Explore agent has already identified the following classification:

| File | Old API? | Action |
|------|----------|--------|
| `docs/effect_monad_api.md` | 🔴 YES — 150+ refs | ARCHIVE (add redirect header) |
| `docs/throwable_effect_api.md` | 🔴 YES — 80+ refs | ARCHIVE (add redirect header) |
| `docs/functional_actor_evolution.md` | 🔴 YES — 50+ refs | ARCHIVE (mark as historical design doc) |
| `docs/effect_monad_new_features.md` | ⚠️ Minor | ARCHIVE (add redirect header — references old `.identity()`, old builder) |
| `README.md` (root) | ⚠️ YES — Effect.modify/tell examples (lines ~965–1043) + stale doc links | UPDATE (phase 18) |
| `docs/README.md` | ✅ Clean | KEEP |
| `docs/effect_monad_guide.md` | ✅ Already redirected | KEEP |
| `docs/effect-actors/*.md` (4 files) | ✅ Current Roux API | KEEP |
| All other docs (15 files) | ✅ No effect refs | KEEP |

This plan's job is to verify the pre-flight findings by reading the key stale files, then write the audit record.

---

## Tasks

### Task 1 — Verify stale content in the 4 archive-candidates

Read the first 50 lines of each file to confirm old API references are present and the file lacks any redirect header:

1. `docs/effect_monad_api.md` — confirm `Effect<State, Error, Result>` in first 60 lines
2. `docs/throwable_effect_api.md` — confirm `ThrowableEffect<State, Result>` in first 30 lines
3. `docs/functional_actor_evolution.md` — confirm `Effect<State, Message, Result>` design spec in first 50 lines
4. `docs/effect_monad_new_features.md` — confirm presence of old API refs (Effect.identity(), old modify pattern)

For each file, note: (a) first old API reference found, (b) whether a redirect/deprecation header already exists.

### Task 2 — Verify stale content in root README.md

Search `README.md` for:
- `Effect.modify(` — old state-mutation call
- `Effect.tell(` — old message-send call
- `effect_monad_api.md` — stale doc link
- `effect_monad_guide.md` — stale doc link

Record the line numbers of each hit.

### Task 3 — Write audit record

Create `.planning/phases/17-doc-audit/17-1-AUDIT.md` with:
- Table of all files examined (final classification)
- Per-file notes for the 5 files needing changes (first old API ref found, line number, action)
- Confirmation that the 19 KEEP files are clean

### Task 4 — Update STATE.md

Append to the Key Context section in `.planning/STATE.md`:

```
## Milestone 4 Audit Findings (Phase 17)
- 4 docs need ARCHIVE action: effect_monad_api.md, throwable_effect_api.md, functional_actor_evolution.md, effect_monad_new_features.md
- Root README.md needs UPDATE: stale Effect.modify/tell examples + 2 stale doc links
- 19 other docs: clean, no changes needed
- effect_monad_guide.md: already has redirect header — no action
```

---

## Verification

After completing all tasks:

- [ ] `17-1-AUDIT.md` exists in `.planning/phases/17-doc-audit/`
- [ ] Audit table covers all 25 doc files
- [ ] Each of the 5 files needing changes has a confirmed line number for first stale reference
- [ ] STATE.md updated with audit findings summary

---

## Success Criteria

- Audit findings documented — no ambiguity about which files need changes
- Phases 18–21 can proceed without re-investigating the same files
- No source files modified in this phase (audit only)

---

## Output

- `.planning/phases/17-doc-audit/17-1-AUDIT.md` — written audit record
- Updated `.planning/STATE.md` — audit findings in Key Context
