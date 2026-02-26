# Phase 3 Plan: Remove Old Effect Machinery

## Objective
Delete every file belonging to the old Cajun `Effect<State,Error,Result>` system.
After this phase, `functional/` contains only Roux-backed code: `ActorEffectRuntime.java`.
Build compiles and test suite stays green.

## Context

### What Phase 1 already cleaned up
The following files were untracked stale files removed during Phase 1 — they do NOT need
deleting again:
- `EffectGenerator.java`, `GeneratorContext.java`, `GeneratorContextImpl.java`
- `functional/capabilities/` (entire directory)
- `EffectGeneratorTest.java`

### Files to delete — discovered via source audit

**Production (7 core monad files)**
| File | Lines | Reason |
|------|-------|--------|
| `functional/Effect.java` | 931 | Old 3-param monad |
| `functional/EffectResult.java` | 304 | Old outcome sealed interface |
| `functional/ThrowableEffect.java` | 407 | Simplified old monad |
| `functional/EffectMatchBuilder.java` | 114 | Pattern-matching builder |
| `functional/ThrowableEffectMatchBuilder.java` | 97 | Pattern-matching builder |
| `functional/EffectConversions.java` | 239 | Cross-type conversions |
| `functional/internal/Trampoline.java` | 63 | Stack-safety impl |

**Production (2 dependent files — reference Effect<S,E,R>, rewritten in Phase 5)**
| File | Lines | Reason |
|------|-------|--------|
| `functional/EffectActorBuilder.java` | 162 | Uses Effect<S,E,R> type params |
| `functional/ActorSystemEffectExtensions.java` | 81 | Uses Effect<S,E,R> type params |

**Test (6 old effect test files)**
| File | Lines | Reason |
|------|-------|--------|
| `functional/EffectResultTest.java` | 469 | Tests old EffectResult |
| `functional/EffectCheckedExceptionTest.java` | 361 | Tests old Effect error channel |
| `functional/ThrowableEffectTest.java` | 304 | Tests ThrowableEffect |
| `functional/NewEffectOperatorsTest.java` | 1,272 | Tests old Effect operators |
| `functional/EffectInterruptionTest.java` | 460 | Tests old Effect interruption |
| `functional/TrampolineTest.java` | 122 | Tests old Trampoline |

**Example (1 file — references EffectActorBuilder and Effect<S,E,R>)**
| File | Lines | Reason |
|------|-------|--------|
| `examples/KVEffectExample.java` | 290 | Imports Effect + ActorSystemEffectExtensions |

### Files to keep (do NOT touch)
- `functional/ActorEffectRuntime.java` — new Roux-backed runtime (Phase 2 output)
- `functional/RouxSmokeTest.java` — new Roux smoke test
- `functional/ActorEffectRuntimeTest.java` — new ActorEffectRuntime test

### No external compile breakage
Zero production code outside `functional/` imports old effect types.
`ActorSystem.java` has no imports from `functional/`. Deletions are contained.

### Untracked docs (informational)
These 4 docs describe old effect designs and are untracked (not committed). They will not
affect compilation. Deleting them is safe but optional.
- `docs/algebraic_effects_implementation.md`
- `docs/generator_effect_evaluation.md`
- `docs/layer_based_dependency_injection.md`
- `docs/pure_layer_system_design.md`

---

## Tasks

### Task 1 — Delete core monad production files

Delete 7 files using `git rm`:

```bash
git rm lib/src/main/java/com/cajunsystems/functional/Effect.java
git rm lib/src/main/java/com/cajunsystems/functional/EffectResult.java
git rm lib/src/main/java/com/cajunsystems/functional/ThrowableEffect.java
git rm lib/src/main/java/com/cajunsystems/functional/EffectMatchBuilder.java
git rm lib/src/main/java/com/cajunsystems/functional/ThrowableEffectMatchBuilder.java
git rm lib/src/main/java/com/cajunsystems/functional/EffectConversions.java
git rm lib/src/main/java/com/cajunsystems/functional/internal/Trampoline.java
```

If `internal/` becomes empty after removing Trampoline, the directory will vanish
automatically (git does not track empty directories).

Commit:
```bash
git commit -m "refactor(3-3): delete old effect core monad files"
```

### Task 2 — Delete old test files

Delete 6 test files using `git rm`:

```bash
git rm lib/src/test/java/com/cajunsystems/functional/EffectResultTest.java
git rm lib/src/test/java/com/cajunsystems/functional/EffectCheckedExceptionTest.java
git rm lib/src/test/java/com/cajunsystems/functional/ThrowableEffectTest.java
git rm lib/src/test/java/com/cajunsystems/functional/NewEffectOperatorsTest.java
git rm lib/src/test/java/com/cajunsystems/functional/EffectInterruptionTest.java
git rm lib/src/test/java/com/cajunsystems/functional/TrampolineTest.java
```

Commit:
```bash
git commit -m "refactor(3-3): delete old effect test files"
```

### Task 3 — Delete dependent files (compile-error sources)

After Task 1, these files reference deleted types and will fail compilation.
They are targeted for rewrite in Phase 5 — delete them now so the build stays green.

```bash
git rm lib/src/main/java/com/cajunsystems/functional/EffectActorBuilder.java
git rm lib/src/main/java/com/cajunsystems/functional/ActorSystemEffectExtensions.java
git rm lib/src/test/java/examples/KVEffectExample.java
```

Commit:
```bash
git commit -m "refactor(3-3): delete old effect builders and example (Phase 5 rewrites)"
```

### Task 4 — Compile verification

```bash
./gradlew :lib:compileJava
./gradlew :lib:compileTestJava
```

Both must produce BUILD SUCCESSFUL.

If either fails, read the error carefully:
- If a file outside `functional/` is broken, fix the import/reference and commit the fix
  with `fix(3-3): fix compile error after effect deletion`
- Do NOT re-add any deleted files

### Task 5 — Run full test suite (regression check)

```bash
./gradlew test
```

BUILD SUCCESSFUL. No regressions.

Expected passing tests:
- `RouxSmokeTest` (3 tests)
- `ActorEffectRuntimeTest` (9 tests)
- All other non-effect tests (actor system, persistence, cluster, etc.)

---

## Verification

- [ ] `functional/Effect.java` deleted
- [ ] `functional/EffectResult.java` deleted
- [ ] `functional/ThrowableEffect.java` deleted
- [ ] `functional/EffectMatchBuilder.java` deleted
- [ ] `functional/ThrowableEffectMatchBuilder.java` deleted
- [ ] `functional/EffectConversions.java` deleted
- [ ] `functional/internal/Trampoline.java` deleted
- [ ] `functional/EffectActorBuilder.java` deleted
- [ ] `functional/ActorSystemEffectExtensions.java` deleted
- [ ] 6 old test files deleted
- [ ] `examples/KVEffectExample.java` deleted
- [ ] `ActorEffectRuntime.java` still present (not deleted)
- [ ] `RouxSmokeTest.java` still present (not deleted)
- [ ] `ActorEffectRuntimeTest.java` still present (not deleted)
- [ ] `./gradlew :lib:compileJava` → BUILD SUCCESSFUL
- [ ] `./gradlew test` → BUILD SUCCESSFUL, no regressions

## Success Criteria

`functional/` contains only `ActorEffectRuntime.java`. All old `Effect<S,E,R>` types are
gone. Build compiles. Test suite is green (12 tests: 3 smoke + 9 ActorEffectRuntime).

## Output
- Deleted: 7 core monad files + 2 dependent files (total 9 production files, ~2,298 lines)
- Deleted: 6 old test files (~2,988 lines)
- Deleted: 1 example file (~290 lines)
- Total removed: ~5,576 lines of old effect code
