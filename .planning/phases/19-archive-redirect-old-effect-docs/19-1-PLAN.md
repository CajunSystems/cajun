# Phase 19, Plan 1: Archive/Redirect Old Effect Docs

## Objective

Add deprecation/redirect headers to the 4 legacy effect docs so readers landing on them
immediately see they're stale and get directed to the current `effect-actors/` guides.
Add an Effect Actors section to `docs/README.md` which currently has none.

No files are deleted — content is preserved for historical reference below each header.

---

## Context

### Files to update

| File | Header type | Lines |
|------|-------------|-------|
| `docs/effect_monad_api.md` | Redirect (API removed) | 996 |
| `docs/throwable_effect_api.md` | Redirect (API removed) | 266 |
| `docs/effect_monad_new_features.md` | Redirect (API removed) | 424 |
| `docs/functional_actor_evolution.md` | Historical (design doc) | 798 |
| `docs/README.md` | Add Effect Actors section + update category index | 163 |

### Reference: existing redirect header pattern

`docs/effect_monad_guide.md` (already handled) uses this format — match it:

```markdown
> **Note:** This guide has been replaced by the new Roux-native effect actor documentation.
>
> The old `Effect<State, Error, Result>` API ... was **removed** in the Cajun × Roux migration (Milestone 1).

## New Documentation

- **[Getting Started](effect-actors/getting-started.md)**
  — ...
```

---

## Tasks

### Task 1 — Add redirect header to `docs/effect_monad_api.md`

Prepend the following before the existing `# Effect Monad API Guide` title:

```markdown
> **Note:** This guide documents the old `Effect<State, Error, Result>` API that was **removed**
> in the Cajun × Roux migration (Milestone 1).
>
> For current documentation, see the Roux-native effect actor guides below.

## Current Documentation

- **[Getting Started with Effect Actors](effect-actors/getting-started.md)**
  — first actor, `Effect.suspend`, `flatMap`, `LogCapability`

- **[Patterns Catalogue](effect-actors/patterns.md)**
  — retry, timeout, parallel, resource, ask pattern

- **[Capabilities Guide](effect-actors/capabilities.md)**
  — `Capability<R>`, `CapabilityHandler`, custom capabilities

- **[Migration Guide](effect-actors/migration.md)**
  — upgrading from `Effect<State,Error,Result>` to `Effect<E,A>`

---

*The content below is preserved for historical reference only. The APIs described here no longer exist.*

---

```

**Implementation:** Use the Edit tool — replace the exact first line `# Effect Monad API Guide` with the block above followed by `# Effect Monad API Guide`.

---

### Task 2 — Add redirect header to `docs/throwable_effect_api.md`

Prepend before `# ThrowableEffect API Guide`:

```markdown
> **Note:** `ThrowableEffect<State, Result>` was part of the old Cajun effect system that was
> **removed** in the Cajun × Roux migration (Milestone 1).
>
> The equivalent in the current API is `Effect<RuntimeException, A>` (Roux).

## Current Documentation

- **[Getting Started with Effect Actors](effect-actors/getting-started.md)**
  — first actor, `Effect.suspend`, `flatMap`, `LogCapability`

- **[Patterns Catalogue](effect-actors/patterns.md)**
  — error handling, retry, timeout, parallel, resource patterns

- **[Migration Guide](effect-actors/migration.md)**
  — upgrading from `ThrowableEffect<State, Result>` to `Effect<E,A>`

---

*The content below is preserved for historical reference only. The APIs described here no longer exist.*

---

```

---

### Task 3 — Add redirect header to `docs/effect_monad_new_features.md`

Prepend before `# Effect Monad - New Features Summary`:

```markdown
> **Note:** This document describes feature additions to the old `Effect<State, Error, Result>`
> API that was **removed** in the Cajun × Roux migration (Milestone 1). These features
> (`Effect.identity()`, `filterOrElse()`, etc.) no longer exist.
>
> For current API features, see the Roux-native documentation below.

## Current Documentation

- **[Getting Started with Effect Actors](effect-actors/getting-started.md)**
  — first actor, `Effect.suspend`, `flatMap`, `LogCapability`

- **[Patterns Catalogue](effect-actors/patterns.md)**
  — retry, timeout, parallel, resource, ask pattern

- **[Migration Guide](effect-actors/migration.md)**
  — upgrading from `Effect<State,Error,Result>` to `Effect<E,A>`

---

*The content below is preserved for historical reference only. The APIs described here no longer exist.*

---

```

---

### Task 4 — Add historical header to `docs/functional_actor_evolution.md`

Prepend before `# FunctionalActor Evolution: Monadic API Design`:

```markdown
> **Historical Document:** This document describes the design evolution and proposal for
> Cajun's original `Effect<State, Message, Result>` monad and `FunctionalActor` class.
> That API was implemented and subsequently **removed** in the Cajun × Roux migration (Milestone 1),
> replaced by Roux's `Effect<E, A>` system.
>
> This document is preserved for historical context only.

## Current Documentation

- **[Getting Started with Effect Actors](effect-actors/getting-started.md)**
  — current Roux-native API

- **[Migration Guide](effect-actors/migration.md)**
  — what changed and how to update

---

*The content below reflects the design of the deleted API. The described classes and interfaces no longer exist.*

---

```

---

### Task 5 — Add Effect Actors section to `docs/README.md`

**Part A**: Insert a new "Effect Actors" subsection after the Clustering block (after the `cluster_mode_improvements.md` line) and before the closing `---` of "Features & Configuration".

Find this exact text in `docs/README.md`:

```
📈 **[cluster_mode_improvements.md](cluster_mode_improvements.md)** - Recent enhancements

---

## Advanced Topics
```

Replace with:

```
📈 **[cluster_mode_improvements.md](cluster_mode_improvements.md)** - Recent enhancements

### Effect Actors (Roux)
⚡ **[effect-actors/getting-started.md](effect-actors/getting-started.md)** - First effect actor
- `EffectActorBuilder`, `Effect.suspend`, `flatMap`
- Capability-based logging

📋 **[effect-actors/patterns.md](effect-actors/patterns.md)** - Patterns catalogue
- Retry, timeout, parallel execution, resource management

🔧 **[effect-actors/capabilities.md](effect-actors/capabilities.md)** - Capabilities guide
- `Capability<R>`, `CapabilityHandler`, custom capabilities

📦 **[effect-actors/migration.md](effect-actors/migration.md)** - Migration guide
- Upgrading from old `Effect<State,Error,Result>` API to Roux

---

## Advanced Topics
```

**Part B**: Add Effect Actors to the "Documentation by Category" section.

Find this exact text:

```
### Advanced Features
- [cluster_mode.md](cluster_mode.md) - Distributed actors
- [sender_propagation.md](sender_propagation.md) - Message patterns
- [../test-utils/README.md](../test-utils/README.md) - Testing utilities
```

Replace with:

```
### Effect Actors (Roux)
- [effect-actors/getting-started.md](effect-actors/getting-started.md) - First effect actor
- [effect-actors/patterns.md](effect-actors/patterns.md) - Patterns catalogue
- [effect-actors/capabilities.md](effect-actors/capabilities.md) - Capabilities guide
- [effect-actors/migration.md](effect-actors/migration.md) - Migration guide

### Advanced Features
- [cluster_mode.md](cluster_mode.md) - Distributed actors
- [sender_propagation.md](sender_propagation.md) - Message patterns
- [../test-utils/README.md](../test-utils/README.md) - Testing utilities
```

---

## Verification

After all tasks:

```bash
# Each of the 4 legacy docs should now start with a blockquote (> )
head -3 docs/effect_monad_api.md docs/throwable_effect_api.md docs/effect_monad_new_features.md docs/functional_actor_evolution.md

# docs/README.md should link to effect-actors/ guides
grep -n "effect-actors/" docs/README.md
```

Expected: 4 lines from `head` starting with `>`, and 8 lines from `grep` (4 in Features section + 4 in Category section).

---

## Success Criteria

- [ ] All 4 legacy docs have a deprecation/historical header as their first content
- [ ] Each header links to `effect-actors/getting-started.md` and `effect-actors/migration.md`
- [ ] `docs/README.md` has a new "Effect Actors (Roux)" subsection under Features
- [ ] `docs/README.md` has Effect Actors entries in Documentation by Category
- [ ] No content deleted from any of the 4 legacy docs (redirect only)

---

## Output

- `docs/effect_monad_api.md` — redirect header prepended
- `docs/throwable_effect_api.md` — redirect header prepended
- `docs/effect_monad_new_features.md` — redirect header prepended
- `docs/functional_actor_evolution.md` — historical header prepended
- `docs/README.md` — Effect Actors section added
