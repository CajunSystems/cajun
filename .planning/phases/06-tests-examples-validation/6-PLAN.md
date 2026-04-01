# Phase 6 Plan: Tests, Examples & Final Validation

## Objective
Close out the Roux integration. Write the missing `CapabilityIntegrationTest`, create
a Roux-native example, clean up stale untracked files, and run the final audit to confirm
no old Cajun effect types remain anywhere.

## Context

### What prior phases already delivered
Most of Phase 6's roadmap items were completed inline:

| Roadmap item | Status |
|---|---|
| `ActorEffectRuntimeTest` | ✅ Done (Phase 2) — 10 tests |
| `EffectActorBuilderTest` | ✅ Done (Phase 5) — 9 tests |
| Update example files | ✅ Done (all 22 examples are clean — no old imports) |
| Old effect imports audit | ✅ CLEAN — zero matches anywhere |

### What's still needed
1. **`CapabilityIntegrationTest`** — the one test file listed in the roadmap that doesn't exist
2. **Roux-native example** — `KVEffectExample` was deleted in Phase 3; nothing replaces it
3. **Untracked file cleanup** — 4 old design docs + 1 `.hprof` profiler file
4. **Full test suite run** — final formal verification with test count

### Current test inventory (30 tests)
- `RouxSmokeTest` — 3 tests
- `ActorEffectRuntimeTest` — 10 tests
- `LogCapabilityTest` — 8 tests
- `EffectActorBuilderTest` — 9 tests

### Untracked files to clean up
```
docs/algebraic_effects_implementation.md   ← old Effect<S,E,R> design doc
docs/generator_effect_evaluation.md        ← old design doc
docs/layer_based_dependency_injection.md   ← old design doc
docs/pure_layer_system_design.md           ← old design doc
java_pid75368.hprof                        ← profiler output, root of repo
```
These describe a system that no longer exists. Delete them all.

---

## Tasks

### Task 1 — Write CapabilityIntegrationTest.java

**File**: `lib/src/test/java/com/cajunsystems/functional/CapabilityIntegrationTest.java`

This test covers what no existing test does: combining a **custom capability** with
`LogCapability`, composing their handlers, and running the composed pipeline through
both `ActorEffectRuntime` and `EffectActorBuilder`.

Define a local `EchoCapability` sealed interface inside the test to avoid new production files:

```java
package com.cajunsystems.functional;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.functional.capabilities.ConsoleLogHandler;
import com.cajunsystems.functional.capabilities.LogCapability;
import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.capability.Capability;
import com.cajunsystems.roux.capability.CapabilityHandler;
import com.cajunsystems.roux.data.Unit;
import org.junit.jupiter.api.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CapabilityIntegrationTest {

    // Local test capability — returns a value, not Unit, to verify result passing
    sealed interface EchoCapability extends Capability<String>
            permits EchoCapability.Echo {
        record Echo(String value) implements EchoCapability {}
    }

    // Handler for EchoCapability
    static class EchoHandler implements CapabilityHandler<EchoCapability> {
        @Override
        @SuppressWarnings("unchecked")
        public <R> R handle(EchoCapability capability) {
            return switch (capability) {
                case EchoCapability.Echo e -> (R) ("ECHO:" + e.value());
            };
        }
    }

    private ActorSystem system;
    private ActorEffectRuntime runtime;

    @BeforeEach
    void setUp() {
        system = new ActorSystem();
        runtime = new ActorEffectRuntime(system);
    }

    @AfterEach
    void tearDown() {
        system.shutdown();
    }

    @Test
    void echoCapabilityHandlerReturnsValue() throws Throwable {
        CapabilityHandler<com.cajunsystems.roux.capability.Capability<?>> handler =
                new EchoHandler().widen();

        Effect<RuntimeException, String> effect =
                Effect.<RuntimeException, String>from(new EchoCapability.Echo("hello"));

        String result = runtime.unsafeRunWithHandler(effect, handler);
        assertEquals("ECHO:hello", result);
    }

    @Test
    void composedHandlerDispatchesToCorrectHandler() throws Throwable {
        // Compose EchoHandler + ConsoleLogHandler — each handles its own type
        CapabilityHandler<com.cajunsystems.roux.capability.Capability<?>> composed =
                CapabilityHandler.compose(new EchoHandler(), new ConsoleLogHandler());

        // EchoCapability dispatched to EchoHandler
        Effect<RuntimeException, String> echoEffect =
                Effect.<RuntimeException, String>from(new EchoCapability.Echo("composed"));
        String echoResult = runtime.unsafeRunWithHandler(echoEffect, composed);
        assertEquals("ECHO:composed", echoResult);

        // LogCapability dispatched to ConsoleLogHandler (no exception = success)
        Effect<RuntimeException, Unit> logEffect =
                Effect.<RuntimeException, Unit>from(new LogCapability.Info("composed-log"));
        Unit logResult = runtime.unsafeRunWithHandler(logEffect, composed);
        assertEquals(Unit.unit(), logResult);
    }

    @Test
    void generateWithMultipleCapabilityTypesViaComposedHandler() throws Throwable {
        CapabilityHandler<com.cajunsystems.roux.capability.Capability<?>> composed =
                CapabilityHandler.compose(new EchoHandler(), new ConsoleLogHandler());

        Effect<RuntimeException, String> effect = Effect.<RuntimeException, String>generate(
                ctx -> {
                    ctx.perform(new LogCapability.Info("starting generate"));
                    String echoed = ctx.perform(new EchoCapability.Echo("generate-test"));
                    ctx.perform(new LogCapability.Debug("echoed: " + echoed));
                    return echoed;
                },
                composed
        );

        String result = runtime.unsafeRun(effect);
        assertEquals("ECHO:generate-test", result);
    }

    @Test
    void effectActorWithComposedCapabilityHandler() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> captured = new AtomicReference<>();

        CapabilityHandler<com.cajunsystems.roux.capability.Capability<?>> composed =
                CapabilityHandler.compose(new EchoHandler(), new ConsoleLogHandler());

        Pid pid = new EffectActorBuilder<>(
                system,
                (String msg) -> Effect.<RuntimeException, String>generate(
                        ctx -> {
                            ctx.perform(new LogCapability.Info("actor received: " + msg));
                            String result = ctx.perform(new EchoCapability.Echo(msg));
                            captured.set(result);
                            latch.countDown();
                            return result;
                        },
                        composed
                )
        ).withId("composed-cap-actor").spawn();

        pid.tell("integration");
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals("ECHO:integration", captured.get());
    }

    @Test
    void orElseHandlerChainDispatchesCorrectly() throws Throwable {
        // Using orElse() instead of compose()
        CapabilityHandler<com.cajunsystems.roux.capability.Capability<?>> chained =
                new EchoHandler().widen().orElse(new ConsoleLogHandler());

        Effect<RuntimeException, String> echoEffect =
                Effect.<RuntimeException, String>from(new EchoCapability.Echo("orElse"));
        String result = runtime.unsafeRunWithHandler(echoEffect, chained);
        assertEquals("ECHO:orElse", result);
    }
}
```

Commit:
```bash
git add lib/src/test/java/com/cajunsystems/functional/CapabilityIntegrationTest.java
git commit -m "test(6-6): add CapabilityIntegrationTest cross-cutting integration"
```

### Task 2 — Write EffectActorExample.java

**File**: `lib/src/test/java/examples/EffectActorExample.java`

Replaces the deleted `KVEffectExample`. Demonstrates the complete Roux-native API:
`EffectActorBuilder`, `LogCapability`, `ConsoleLogHandler`, `Effect.generate`, and
`ActorSystemEffectExtensions`.

```java
package examples;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.functional.ActorSystemEffectExtensions;
import com.cajunsystems.functional.EffectActorBuilder;
import com.cajunsystems.functional.capabilities.ConsoleLogHandler;
import com.cajunsystems.functional.capabilities.LogCapability;
import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.capability.CapabilityHandler;
import com.cajunsystems.roux.data.Unit;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Demonstrates the Roux-native effect actor API.
 *
 * <p>Shows how to spawn actors whose message handling is expressed as
 * Roux {@link com.cajunsystems.roux.Effect} pipelines, executed through
 * {@link com.cajunsystems.functional.ActorEffectRuntime} on the actor system's executor.
 */
class EffectActorExample {

    /**
     * Simple effect actor that logs each message via LogCapability.
     *
     * <p>Demonstrates: EffectActorBuilder + ConsoleLogHandler + Effect.generate
     */
    @Test
    void loggingCounterActor() throws InterruptedException {
        ActorSystem system = new ActorSystem();
        int messageCount = 3;
        CountDownLatch latch = new CountDownLatch(messageCount);
        AtomicInteger processed = new AtomicInteger(0);

        ConsoleLogHandler logHandler = new ConsoleLogHandler();
        CapabilityHandler<com.cajunsystems.roux.capability.Capability<?>> handler =
                logHandler.widen();

        // Spawn an actor that logs each message and counts it
        Pid counter = new EffectActorBuilder<>(
                system,
                (Integer n) -> Effect.<RuntimeException, Unit>generate(
                        ctx -> {
                            ctx.perform(new LogCapability.Info("Processing: " + n));
                            processed.addAndGet(n);
                            ctx.perform(new LogCapability.Debug("Running total: " + processed.get()));
                            latch.countDown();
                            return Unit.unit();
                        },
                        handler
                )
        ).withId("logging-counter").spawn();

        // Send messages
        counter.tell(1);
        counter.tell(2);
        counter.tell(3);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(6, processed.get());

        system.shutdown();
    }

    /**
     * One-liner spawning via ActorSystemEffectExtensions static helpers.
     *
     * <p>Demonstrates: spawnEffectActor shorthand + Effect.suspend
     */
    @Test
    void oneLineEffectActor() throws InterruptedException {
        ActorSystem system = new ActorSystem();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger result = new AtomicInteger();

        // One-liner: no builder needed for simple cases
        Pid echoActor = ActorSystemEffectExtensions.spawnEffectActor(
                system,
                "echo-actor",
                (Integer n) -> Effect.suspend(() -> {
                    result.set(n * 2);
                    latch.countDown();
                    return Unit.unit();
                })
        );

        echoActor.tell(21);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(42, result.get());

        system.shutdown();
    }

    /**
     * Effect pipeline with flatMap chaining.
     *
     * <p>Demonstrates: composing multiple Effect steps before execution
     */
    @Test
    void effectPipelineWithFlatMap() throws InterruptedException {
        ActorSystem system = new ActorSystem();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger result = new AtomicInteger();

        Pid pipeline = ActorSystemEffectExtensions.spawnEffectActor(
                system,
                (Integer n) -> Effect.<RuntimeException, Integer>succeed(n)
                        .flatMap(x -> Effect.succeed(x * 2))
                        .flatMap(x -> Effect.suspend(() -> {
                            result.set(x);
                            latch.countDown();
                            return Unit.unit();
                        }))
        );

        pipeline.tell(10);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(20, result.get());

        system.shutdown();
    }
}
```

Commit:
```bash
git add lib/src/test/java/examples/EffectActorExample.java
git commit -m "feat(6-6): add Roux-native EffectActorExample"
```

### Task 3 — Clean up untracked files

**Delete stale design docs** (describe old Effect<S,E,R> system, now obsolete):
```bash
rm docs/algebraic_effects_implementation.md
rm docs/generator_effect_evaluation.md
rm docs/layer_based_dependency_injection.md
rm docs/pure_layer_system_design.md
```

**Delete profiler output**:
```bash
rm java_pid75368.hprof
```

**Add .hprof to .gitignore** so future profiler files are not tracked:
```bash
echo "*.hprof" >> .gitignore
git add .gitignore
git commit -m "chore(6-6): clean up stale docs and add hprof to gitignore"
```

**Note**: If `.gitignore` doesn't exist at root, create it with just `*.hprof`.

### Task 4 — Final audit and full test suite

**Final audit** — confirm zero old effect type references:
```bash
grep -r "com\.cajunsystems\.functional\.Effect[^A-Z]" lib/src --include="*.java" -l
grep -r "EffectResult\|ThrowableEffect\|EffectMatchBuilder\|EffectConversions\|Trampoline" \
     lib/src --include="*.java" -l
```

Both must return empty (no matches).

**Confirm functional package is complete**:
```bash
find lib/src/main/java/com/cajunsystems/functional -name "*.java" | sort
```

Expected output:
```
ActorEffectRuntime.java
ActorSystemEffectExtensions.java
EffectActorBuilder.java
capabilities/ConsoleLogHandler.java
capabilities/LogCapability.java
```

**Run full test suite**:
```bash
./gradlew test --no-daemon
```

Must produce BUILD SUCCESSFUL.

Report total test count. Expected minimum: **35 tests**
(30 existing + 5 new from `CapabilityIntegrationTest` + 3 new from `EffectActorExample`)

No commit for this task — it is verification only.

---

## Verification

- [ ] `CapabilityIntegrationTest.java` created — 5 tests using composed handlers + actor
- [ ] `EffectActorExample.java` created — 3 tests demonstrating full Roux-native API
- [ ] 4 stale design docs deleted
- [ ] `.hprof` file deleted, `*.hprof` added to `.gitignore`
- [ ] Zero old effect type imports found in grep audit
- [ ] `./gradlew test` → BUILD SUCCESSFUL, ≥35 tests pass

## Success Criteria

The Roux integration is complete and validated:
- All new tests pass
- No old Cajun effect types remain anywhere in the codebase
- A Roux-native example exists to demonstrate the complete API
- Build is green

## Output
- Created: `lib/src/test/java/com/cajunsystems/functional/CapabilityIntegrationTest.java`
- Created: `lib/src/test/java/examples/EffectActorExample.java`
- Deleted: 4 stale docs + 1 `.hprof` file
- Updated: `.gitignore`
