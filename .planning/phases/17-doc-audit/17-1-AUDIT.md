# Phase 17 — Doc Audit: Stale Effect API Reference Inventory

**Audited**: 2026-04-01  
**Scope**: All markdown docs in `/docs/` and root `README.md`  
**Old API under review**: `Effect<State,Error,Result>`, `ThrowableEffect`, `Effect.modify/tell/ask/log`, `EffectMatchBuilder`, `fromEffect()`

---

## Full Classification Table

| File | Old API Refs | Classification | Action |
|------|-------------|----------------|--------|
| `docs/effect_monad_api.md` | 150+ | Stale | **ARCHIVE** (redirect header) |
| `docs/throwable_effect_api.md` | 80+ | Stale | **ARCHIVE** (redirect header) |
| `docs/functional_actor_evolution.md` | 50+ | Historical design doc | **ARCHIVE** (historical header) |
| `docs/effect_monad_new_features.md` | ~10 | Stale | **ARCHIVE** (redirect header) |
| `README.md` (root) | 9 | Partially stale | **UPDATE** (Phase 18) |
| `docs/README.md` | 0 | Clean | KEEP |
| `docs/effect_monad_guide.md` | Redirect only | Already updated | KEEP |
| `docs/effect-actors/getting-started.md` | 0 | Current API | KEEP |
| `docs/effect-actors/capabilities.md` | 0 | Current API | KEEP |
| `docs/effect-actors/patterns.md` | 0 | Current API | KEEP |
| `docs/effect-actors/migration.md` | 0 | Current API | KEEP |
| `docs/backpressure_readme.md` | 0 | Unrelated | KEEP |
| `docs/backpressure_system.md` | 0 | Unrelated | KEEP |
| `docs/backpressure_system_enhancements.md` | 0 | Unrelated | KEEP |
| `docs/cluster_mode.md` | 0 | Unrelated | KEEP |
| `docs/persistence_guide.md` | 0 | Unrelated | KEEP |
| `docs/mailbox_guide.md` | 0 | Unrelated | KEEP |
| `docs/actor_id_strategies.md` | 0 | Unrelated | KEEP |
| `docs/actor_batching_optimization.md` | 0 | Unrelated | KEEP |
| `docs/BENCHMARKS.md` | 0 | Unrelated | KEEP |
| `docs/performance_improvements.md` | 0 | Unrelated | KEEP |
| `docs/performance_recommendation.md` | 0 | Unrelated | KEEP |
| `docs/reply_pattern_usage.md` | 0 | Unrelated | KEEP |
| `docs/sender_propagation.md` | 0 | Unrelated | KEEP |
| `docs/supervision_audit.md` | 0 | Unrelated | KEEP |

**Total files examined:** 25  
**Files requiring changes:** 5  
**Files clean / no action:** 20

---

## Files Requiring Changes — Detail

### 1. `docs/effect_monad_api.md` — ARCHIVE

**First stale ref:** Line 12 — `Effect<State, Error, Result>` in Key Features bullet  
**No redirect header present.** Entire document describes the deleted 3-param API.  
**Action (Phase 19):** Prepend deprecation/redirect banner pointing to `effect-actors/`.

### 2. `docs/throwable_effect_api.md` — ARCHIVE

**First stale ref:** Line 3 — `` `ThrowableEffect<State, Result>` `` in Overview  
**No redirect header present.** Entire document describes the deleted ThrowableEffect wrapper.  
**Action (Phase 19):** Prepend deprecation/redirect banner pointing to `effect-actors/`.

### 3. `docs/functional_actor_evolution.md` — ARCHIVE (historical)

**First stale ref:** Line 1 — `# FunctionalActor Evolution: Monadic API Design` (title itself)  
**No historical/archive header present.** Design proposal for the deleted 3-param Effect monad.  
**Action (Phase 19):** Prepend historical-document header noting the API was implemented then removed. Link to `effect-actors/`.

### 4. `docs/effect_monad_new_features.md` — ARCHIVE

**First stale ref:** Line 5 — `Effect.modify(s -> s)` in section 1 preamble  
**No redirect header present.** Documents new features for the old Effect monad (`.identity()`, `filterOrElse()`, etc.).  
**Action (Phase 19):** Prepend deprecation/redirect banner pointing to `effect-actors/`.

### 5. `README.md` (root) — UPDATE

**Stale refs confirmed at lines:**
- 965, 969: `Effect.modify(s -> s + msg.amount())` / `s - msg.amount()`
- 973: `Effect.tell(msg.replyTo(), state)`
- 1004: `Effect.modify(count -> count + 1)`
- 1010: `Effect.tell(otherActor, message)`
- 1011: `Effect.tellSelf(message)`
- 1017: `Effect.modify(s -> s + 1)`
- 1019: `.andThen(Effect.tell(monitor, ...))`
- 1042: Link to `docs/effect_monad_guide.md`
- 1043: Link to `docs/effect_monad_api.md`

**Action (Phase 18):** Rewrite the Effect Actor section (lines ~955–1050) with current Roux API (`Effect<E,A>`, `EffectActorBuilder`, `Handler`, `StatefulHandler`). Update links to point to `docs/effect-actors/getting-started.md`.

---

## Already-Clean Files (Confirmed)

- `docs/effect_monad_guide.md` — has correct deprecation redirect header, no action needed
- `docs/effect-actors/*.md` — all 4 files use current Roux 2-param API exclusively
- All 15 non-effect docs — no references to old Effect API types

---

## Phase Mapping

| Phase | Action |
|-------|--------|
| Phase 18 | Update root `README.md` Effect Actor section |
| Phase 19 | Add archive/redirect headers to the 4 legacy effect docs; update `docs/README.md` links |
| Phase 20 | Bump `cajunVersion` 0.4.0 → 0.7.0 |
| Phase 21 | Full test suite + final grep audit + git tag v0.7.0 |
