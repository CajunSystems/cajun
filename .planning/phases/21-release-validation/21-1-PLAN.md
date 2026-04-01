# Phase 21, Plan 1: Release Validation

## Objective

Confirm the codebase is clean and ready for v0.7.0 release:
1. Final grep audit — no stale `Effect<State,Error,Result>` refs in Cajun source or active docs
2. Full test suite — all tests pass (383 expected, excluding pre-existing etcd failure)

Git tagging is done by the user after merging to main — not part of this plan.

---

## Context

### Scope of audit

**Check (must be clean):**
- Cajun source: `lib/src/`, `cajun-core/src/`, `cajun-system/src/`, `cajun-mailbox/src/`, `cajun-persistence/src/`, `cajun-cluster/src/`, `test-utils/src/`
- Active docs: `README.md`, `docs/README.md`, `docs/effect-actors/*.md`

**Exclude (intentionally contain old API text):**
- `docs/effect_monad_api.md` — redirect header + archived content
- `docs/effect_monad_guide.md` — redirect header
- `docs/throwable_effect_api.md` — redirect header + archived content
- `docs/effect_monad_new_features.md` — redirect header + archived content
- `docs/functional_actor_evolution.md` — historical header + archived content
- `com/` directory — untracked Roux library source (not Cajun code)

### Pre-existing known issue

`ClusterModeTest.testRemoteActorCommunication` fails intermittently when etcd is not running —
pre-existing, unrelated to this milestone. If this is the only failure, tests pass.

### Pre-flight result

Source audit run during planning returned **zero matches** for stale patterns across all Cajun modules. Formal audit in this plan confirms that result and documents it.

---

## Tasks

### Task 1 — Final grep audit: Cajun source

Run the following and confirm zero output:

```bash
grep -rn "Effect<State\|ThrowableEffect\|EffectMatchBuilder\|EffectConversions\|fromEffect(" \
  lib/src cajun-core/src cajun-system/src cajun-mailbox/src \
  cajun-persistence/src cajun-cluster/src test-utils/src \
  --include="*.java" 2>/dev/null | grep -v build/
```

Expected: **no output** (zero matches).

If any matches are found: investigate and fix before proceeding.

### Task 2 — Final grep audit: active docs

Run the following and confirm zero output:

```bash
grep -n "Effect<State\|ThrowableEffect\|EffectMatchBuilder\|Effect\.modify\|Effect\.tell\b\|fromEffect\|effect_monad_api\|throwable_effect_api\|functional_actor_evolution\|effect_monad_new_features" \
  README.md docs/README.md docs/effect-actors/getting-started.md \
  docs/effect-actors/patterns.md docs/effect-actors/capabilities.md \
  docs/effect-actors/migration.md
```

Expected: **no output** (zero matches in these active files).

Note: `docs/effect-actors/migration.md` may mention old type names in migration table cells — acceptable if they appear as documentation of what was removed, not as live API examples. Inspect any matches carefully.

### Task 3 — Run full test suite

```bash
cd /Users/pradeep.samuel/cajun && ./gradlew test
```

Expected outcome:
- 383 tests executed, 0 failures (or 1 failure if `ClusterModeTest.testRemoteActorCommunication` fires — pre-existing etcd issue, acceptable)
- BUILD SUCCESSFUL

If unexpected failures appear: read the failure output, investigate the root cause, fix before proceeding.

### Task 4 — Record audit results

After tasks 1–3 complete successfully, document the results:
- Note the exact test count from `./gradlew test` output
- Confirm both grep audits returned zero actionable matches
- Update STATE.md to mark Phase 21 complete and record final test count

---

## Verification

```bash
# Re-run both audits as final check
grep -rn "Effect<State\|ThrowableEffect\|EffectMatchBuilder" lib/src cajun-core/src --include="*.java" | grep -v build/
grep -n "Effect\.modify\|fromEffect" README.md docs/README.md docs/effect-actors/*.md
```

Both should return no output.

---

## Success Criteria

- [ ] Source audit: zero stale Effect API refs in all Cajun modules
- [ ] Active-doc audit: zero stale refs in README.md, docs/README.md, docs/effect-actors/
- [ ] Full test suite: BUILD SUCCESSFUL with ≥383 tests, 0 unexpected failures
- [ ] STATE.md updated with final test count and phase complete

---

## Output

- STATE.md updated — Phase 21 complete, final test count recorded
- No source changes expected (audit only + test run)
