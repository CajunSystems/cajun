# Phase 20, Plan 1: Version Bump

## Objective

Bump the project version from `0.4.0` to `0.7.0`. Update `gradle.properties` (single source of
truth), fix the two hardcoded version strings in `README.md`'s installation snippets, and add
a `[0.7.0]` changelog entry covering milestones 1–3.

---

## Context

### Version strategy

| Version | Milestone | Notes |
|---------|-----------|-------|
| 0.4.0 | — | Last published release (in `gradle.properties` + README) |
| 0.5.0 | Milestone 2 | Archived tag only — never bumped in `gradle.properties` |
| 0.6.0 | Milestone 3 | Archived tag only — never bumped in `gradle.properties` |
| **0.7.0** | **Milestone 4** | **Target — bump now, before release validation** |

Skipping to 0.7.0 (not 0.5.0) because milestones 2 and 3 are complete but `gradle.properties`
was never updated during those milestones. The next published version should reflect all three
milestones of work.

### Files to change

| File | Current value | Target value |
|------|--------------|--------------|
| `gradle.properties` line 7 | `cajunVersion=0.4.0` | `cajunVersion=0.7.0` |
| `README.md` line 167 | `com.cajunsystems:cajun:0.4.0` | `com.cajunsystems:cajun:0.7.0` |
| `README.md` line 177 | `<version>0.4.0</version>` | `<version>0.7.0</version>` |
| `CHANGELOG.md` line 8 | `## [0.4.0]` (latest entry) | Prepend new `## [0.7.0]` entry above it |

### Individual `build.gradle` fallbacks

7 build.gradle files each have `project.findProperty('cajunVersion') ?: '0.4.0'`. These fallbacks
are never used in practice (gradle.properties always provides the property) so they are **not
updated** — noise-to-value ratio is too high for 7 one-line changes.

---

## Tasks

### Task 1 — Bump `gradle.properties`

In `gradle.properties`, replace:
```
cajunVersion=0.4.0
```
with:
```
cajunVersion=0.7.0
```

### Task 2 — Update `README.md` installation snippets

**Gradle snippet** (line 167) — replace:
```
    implementation 'com.cajunsystems:cajun:0.4.0'
```
with:
```
    implementation 'com.cajunsystems:cajun:0.7.0'
```

**Maven snippet** (line 177) — replace:
```
    <version>0.4.0</version>
```
with:
```
    <version>0.7.0</version>
```

### Task 3 — Add `[0.7.0]` entry to `CHANGELOG.md`

Prepend before the existing `## [0.4.0] - 2025-12-03` line:

```markdown
## [0.7.0] - 2026-04-01

### Added

- **Roux Effect System integration** (Milestones 1–3): Replaced Cajun's bespoke
  `Effect<State,Error,Result>` monad with Roux (`com.cajunsystems:roux:0.2.1`),
  a production-quality functional effect system for Java 21+.

- **`ActorEffectRuntime`**: Roux `EffectRuntime` backed by the actor system's executor —
  effects dispatched through actor threads, not a separate virtual-thread pool.

- **`EffectActorBuilder`** and **`ActorSystemEffectExtensions`**: Fluent API for spawning actors
  that handle messages as `Effect<E, A>` pipelines.

- **`LogCapability`** and **`ConsoleLogHandler`**: Roux-native `Capability<R>` / `CapabilityHandler`
  for structured logging inside effects.

- **11 runnable effect actor examples** covering: error handling, retry policies, timeout,
  stateful composition (ask pattern), multi-stage pipelines, fan-out dispatchers, custom
  capabilities, parallel execution (`parAll`, `race`, `traverse`), and resource management
  (`Resource<A>`).

- **`docs/effect-actors/`**: Four guides — Getting Started, Patterns Catalogue, Capabilities,
  and Migration (v0.1.0 → v0.2.1).

### Changed

- Roux dependency upgraded from `0.1.0` → `0.2.1`. New APIs available:
  `retry(n)`, `retryWithDelay()`, `retry(RetryPolicy)`, `timeout(Duration)`,
  `tap()`, `tapError()`, `Effects.parAll/race/traverse`, `Resource<A>`,
  `Effect.unit/runnable/sleep/when/unless`.

- **`ActorEffectRuntime.close()`** is now a no-op — the executor lifecycle is owned by
  `ActorSystem`, not the runtime. Fixes double-shutdown on `AutoCloseable` scope exit.

- All capability handlers migrated to `CapabilityHandler.builder()` pattern, which
  auto-throws `UnsupportedOperationException` for unregistered capability types.

### Removed

- **Old `functional/` effect system** (hard cut, no deprecated wrappers):
  `Effect<State,Error,Result>`, `ThrowableEffect`, `EffectMatchBuilder`,
  `EffectConversions`, `EffectGenerator`, `GeneratorContext`, `Trampoline`,
  `functional/capabilities/Capability.java`, `functional/capabilities/CapabilityHandler.java`.

---

```

---

## Verification

```bash
# gradle.properties
grep cajunVersion /Users/pradeep.samuel/cajun/gradle.properties

# README.md installation snippets
grep -n "cajun:0\." README.md; grep -n "<version>0\." README.md

# CHANGELOG top entry
head -5 CHANGELOG.md
```

Expected:
- `cajunVersion=0.7.0`
- README: `cajun:0.7.0` and `<version>0.7.0</version>`
- CHANGELOG first line after header: `## [0.7.0] - 2026-04-01`

---

## Success Criteria

- [ ] `gradle.properties`: `cajunVersion=0.7.0`
- [ ] `README.md` Gradle snippet: `cajun:0.7.0`
- [ ] `README.md` Maven snippet: `<version>0.7.0</version>`
- [ ] `CHANGELOG.md`: new `[0.7.0]` entry is the first version section
- [ ] No remaining `0.4.0` in `gradle.properties` or `README.md`

---

## Output

- `gradle.properties` — version bumped
- `README.md` — installation snippets updated
- `CHANGELOG.md` — `[0.7.0]` entry prepended
