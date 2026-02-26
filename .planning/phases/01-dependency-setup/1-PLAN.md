# Phase 1 Plan: Dependency Setup & Build Verification

## Objective
Add `com.cajunsystems:roux:0.1.0` to Cajun's `lib` module as an `api` dependency, confirm the full build compiles cleanly with Roux present alongside the existing effect system, and write a smoke test proving Roux's runtime works in Cajun's test environment.

**This phase does NOT delete or modify any existing code.** That starts in Phase 3.

## Context
- Build file to modify: `lib/build.gradle`
- Version catalog: `gradle/libs.versions.toml` (optional — can add Roux entry there for consistency)
- Roux coordinates: `com.cajunsystems:roux:0.1.0`
- Roux must be `api` scope (not `implementation`) because users of `cajun` need `Effect<E, A>` on their compile classpath
- All modules already use `mavenCentral()` — no repo config needed
- Build uses `--enable-preview` throughout — Roux also requires this (already handled)

## Tasks

### Task 1 — Add Roux to version catalog
**File**: `gradle/libs.versions.toml`

Add to `[versions]`:
```toml
roux = "0.1.0"
```

Add to `[libraries]`:
```toml
roux = { module = "com.cajunsystems:roux", version.ref = "roux" }
```

### Task 2 — Add Roux as api dependency to lib module
**File**: `lib/build.gradle`

In the `dependencies` block, after the existing `api` entries, add:
```groovy
// Roux effect system (unified effects for Cajun actors)
api libs.roux
```

Use `api` (not `implementation`) so that consumers of `cajun` have `Effect<E, A>` on their classpath.

### Task 3 — Verify build compiles
Run:
```bash
./gradlew :lib:compileJava
```

Expected: BUILD SUCCESSFUL with no errors. If compilation fails, investigate and fix before proceeding.

Also run the full build to ensure no module-level issues:
```bash
./gradlew build -x test
```

### Task 4 — Write Roux smoke test
**File**: `lib/src/test/java/com/cajunsystems/functional/RouxSmokeTest.java`

Create a JUnit test that verifies Roux is on the classpath and its runtime executes correctly:

```java
package com.cajunsystems.functional;

import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.runtime.DefaultEffectRuntime;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RouxSmokeTest {

    @Test
    void rouxDefaultRuntimeExecutesSucceedEffect() throws Exception {
        var runtime = DefaultEffectRuntime.create();
        var effect = Effect.<RuntimeException, Integer>succeed(42);
        var result = runtime.unsafeRun(effect);
        assertEquals(42, result);
    }

    @Test
    void rouxDefaultRuntimeExecutesFlatMap() throws Exception {
        var runtime = DefaultEffectRuntime.create();
        var effect = Effect.<RuntimeException, Integer>succeed(10)
                .flatMap(n -> Effect.succeed(n * 2));
        var result = runtime.unsafeRun(effect);
        assertEquals(20, result);
    }

    @Test
    void rouxDefaultRuntimeExecutesFailEffect() {
        var runtime = DefaultEffectRuntime.create();
        var effect = Effect.<RuntimeException, Integer>fail(new RuntimeException("expected failure"));
        assertThrows(RuntimeException.class, () -> runtime.unsafeRun(effect));
    }
}
```

**Note on exact API**: Verify `DefaultEffectRuntime.create()` and `Effect.succeed()` match Roux's actual published API. Adjust factory methods if needed after checking the Roux source.

### Task 5 — Run the smoke test
```bash
./gradlew :lib:test --tests "com.cajunsystems.functional.RouxSmokeTest"
```

Expected: all 3 tests pass.

### Task 6 — Run full existing test suite
```bash
./gradlew test
```

Expected: same pass/fail count as before this phase. No regressions from adding the dependency.

## Verification

- [ ] `gradle/libs.versions.toml` has `roux = "0.1.0"` entry
- [ ] `lib/build.gradle` has `api libs.roux`
- [ ] `./gradlew :lib:compileJava` → BUILD SUCCESSFUL
- [ ] `./gradlew build -x test` → BUILD SUCCESSFUL
- [ ] `RouxSmokeTest` — all 3 tests pass
- [ ] `./gradlew test` — no regressions from pre-phase baseline

## Success Criteria

Roux is a resolved, compiled dependency in the `lib` module. The smoke test proves Roux's `DefaultEffectRuntime` runs effects correctly in Cajun's Java 21 + `--enable-preview` environment. No existing tests broken.

## Output
- Modified: `gradle/libs.versions.toml`
- Modified: `lib/build.gradle`
- Created: `lib/src/test/java/com/cajunsystems/functional/RouxSmokeTest.java`
