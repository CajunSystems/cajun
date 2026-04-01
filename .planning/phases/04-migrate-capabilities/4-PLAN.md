# Phase 4 Plan: Migrate Capabilities

## Objective
Create `LogCapability` and `ConsoleLogHandler` using Roux's `Capability<R>` /
`CapabilityHandler<C>` model. These are **new files** — the old `capabilities/`
directory was already removed in Phase 1. Phase 4 is purely additive.

## Context

### What was found during discovery
- `LogCapability.java` and `ConsoleLogHandler.java` **do not exist** — never implemented
- `functional/capabilities/` directory **does not exist** — removed in Phase 1
- Roadmap step 4.1 (delete old capability files) is a **no-op** — proceed directly to creation

### Roux capability API (confirmed from jar decompilation)

```java
// com.cajunsystems.roux.capability.Capability<R>
public interface Capability<R> {
    default <E extends Throwable> Effect<E, R> toEffect();   // creates Effect.from(this)
}

// com.cajunsystems.roux.capability.CapabilityHandler<C extends Capability<?>>
public interface CapabilityHandler<C extends Capability<?>> {
    <R> R handle(C capability) throws Exception;
    default CapabilityHandler<Capability<?>> widen();         // unsafe-cast to wider type
    default CapabilityHandler<Capability<?>> orElse(CapabilityHandler<?>);
    static CapabilityHandler<Capability<?>> compose(CapabilityHandler<?>...);
}

// EffectRuntime (ActorEffectRuntime inherits this)
<E, A> A unsafeRunWithHandler(Effect<E, A>, CapabilityHandler<Capability<?>>) throws E;

// Effect static factory
static <E, R> Effect<E, R> from(Capability<R>);              // lift capability to effect
static <E, A> Effect<E, A> generate(EffectGenerator<E, A>, CapabilityHandler<Capability<?>>);

// GeneratorContext — perform capabilities inside Effect.generate()
<R> R perform(Capability<R>) throws E;
```

### Design decisions
- Use `com.cajunsystems.roux.data.Unit` (not Java's `Void`) as the log result type — `Unit.INSTANCE` is the singleton
- Package: `com.cajunsystems.functional.capabilities` (sub-package matching original design doc)
- `LogCapability` is a non-generic sealed interface extending `Capability<Unit>` — cleaner since all variants return `Unit`
- `ConsoleLogHandler` implements `CapabilityHandler<LogCapability>` — exhaustive sealed switch, no default branch needed
- To pass to `unsafeRunWithHandler`, call `handler.widen()` to get `CapabilityHandler<Capability<?>>`

### File locations
- New: `lib/src/main/java/com/cajunsystems/functional/capabilities/LogCapability.java`
- New: `lib/src/main/java/com/cajunsystems/functional/capabilities/ConsoleLogHandler.java`
- New: `lib/src/test/java/com/cajunsystems/functional/capabilities/LogCapabilityTest.java`

---

## Tasks

### Task 1 — Create LogCapability.java

**File**: `lib/src/main/java/com/cajunsystems/functional/capabilities/LogCapability.java`

```java
package com.cajunsystems.functional.capabilities;

import com.cajunsystems.roux.capability.Capability;
import com.cajunsystems.roux.data.Unit;

/**
 * Logging capability sealed interface for the Roux effect system.
 *
 * <p>Implementations are pure data — they describe logging operations without
 * performing them. A {@link ConsoleLogHandler} (or custom handler) interprets them.
 *
 * <p>Usage via {@code Effect.from()}:
 * <pre>{@code
 * Effect<RuntimeException, Unit> effect = Effect.from(new LogCapability.Info("hello"));
 * runtime.unsafeRunWithHandler(effect, new ConsoleLogHandler().widen());
 * }</pre>
 *
 * <p>Usage inside {@code Effect.generate()}:
 * <pre>{@code
 * Effect<RuntimeException, Unit> effect = Effect.generate(
 *     ctx -> {
 *         ctx.perform(new LogCapability.Info("step 1"));
 *         ctx.perform(new LogCapability.Warn("step 2"));
 *         return Unit.INSTANCE;
 *     },
 *     new ConsoleLogHandler().widen()
 * );
 * runtime.unsafeRun(effect);
 * }</pre>
 */
public sealed interface LogCapability extends Capability<Unit>
        permits LogCapability.Info, LogCapability.Debug, LogCapability.Warn, LogCapability.Error {

    /** Log an informational message. */
    record Info(String message) implements LogCapability {}

    /** Log a debug message. */
    record Debug(String message) implements LogCapability {}

    /** Log a warning message. */
    record Warn(String message) implements LogCapability {}

    /** Log an error message with an optional cause. */
    record Error(String message, Throwable cause) implements LogCapability {
        /** Convenience constructor for errors without a cause. */
        public Error(String message) {
            this(message, null);
        }
    }
}
```

Commit:
```bash
git add lib/src/main/java/com/cajunsystems/functional/capabilities/LogCapability.java
git commit -m "feat(4-4): create LogCapability sealed interface"
```

### Task 2 — Create ConsoleLogHandler.java

**File**: `lib/src/main/java/com/cajunsystems/functional/capabilities/ConsoleLogHandler.java`

```java
package com.cajunsystems.functional.capabilities;

import com.cajunsystems.roux.capability.CapabilityHandler;
import com.cajunsystems.roux.data.Unit;

/**
 * A {@link CapabilityHandler} for {@link LogCapability} that writes to the console.
 *
 * <ul>
 *   <li>{@link LogCapability.Info} and {@link LogCapability.Debug} → {@link System#out}
 *   <li>{@link LogCapability.Warn} → {@link System#out}
 *   <li>{@link LogCapability.Error} → {@link System#err}; prints stack trace if cause present
 * </ul>
 *
 * <p>To pass to {@link com.cajunsystems.roux.EffectRuntime#unsafeRunWithHandler}, call
 * {@link #widen()} to obtain a {@code CapabilityHandler<Capability<?>>}:
 * <pre>{@code
 * runtime.unsafeRunWithHandler(effect, new ConsoleLogHandler().widen());
 * }</pre>
 */
public class ConsoleLogHandler implements CapabilityHandler<LogCapability> {

    @Override
    @SuppressWarnings("unchecked")
    public <R> R handle(LogCapability capability) {
        return switch (capability) {
            case LogCapability.Info info -> {
                System.out.println("[INFO]  " + info.message());
                yield (R) Unit.INSTANCE;
            }
            case LogCapability.Debug debug -> {
                System.out.println("[DEBUG] " + debug.message());
                yield (R) Unit.INSTANCE;
            }
            case LogCapability.Warn warn -> {
                System.out.println("[WARN]  " + warn.message());
                yield (R) Unit.INSTANCE;
            }
            case LogCapability.Error error -> {
                System.err.println("[ERROR] " + error.message());
                if (error.cause() != null) {
                    error.cause().printStackTrace(System.err);
                }
                yield (R) Unit.INSTANCE;
            }
        };
    }
}
```

Commit:
```bash
git add lib/src/main/java/com/cajunsystems/functional/capabilities/ConsoleLogHandler.java
git commit -m "feat(4-4): create ConsoleLogHandler capability handler"
```

### Task 3 — Write LogCapabilityTest.java

**File**: `lib/src/test/java/com/cajunsystems/functional/capabilities/LogCapabilityTest.java`

Tests:
1. `Info` writes `[INFO]` to stdout
2. `Debug` writes `[DEBUG]` to stdout
3. `Warn` writes `[WARN]` to stdout
4. `Error` with cause writes `[ERROR]` to stderr
5. `Error` without cause writes `[ERROR]` to stderr, no NPE
6. `Effect.generate` + `ctx.perform` chains multiple log operations
7. `CapabilityHandler.compose()` with `ConsoleLogHandler` works end-to-end

```java
package com.cajunsystems.functional.capabilities;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.functional.ActorEffectRuntime;
import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.capability.CapabilityHandler;
import com.cajunsystems.roux.data.Unit;
import org.junit.jupiter.api.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

class LogCapabilityTest {

    private ActorSystem system;
    private ActorEffectRuntime runtime;
    private ConsoleLogHandler handler;

    @BeforeEach
    void setUp() {
        system = new ActorSystem();
        runtime = new ActorEffectRuntime(system);
        handler = new ConsoleLogHandler();
    }

    @AfterEach
    void tearDown() {
        system.shutdown();
    }

    @Test
    void infoWritesToStdout() throws Throwable {
        ByteArrayOutputStream out = captureStdout();
        try {
            Effect<RuntimeException, Unit> effect = Effect.from(new LogCapability.Info("hello-info"));
            Unit result = runtime.unsafeRunWithHandler(effect, handler.widen());
            assertEquals(Unit.INSTANCE, result);
        } finally {
            restoreStdout(out);
        }
        assertTrue(out.toString().contains("[INFO]"));
        assertTrue(out.toString().contains("hello-info"));
    }

    @Test
    void debugWritesToStdout() throws Throwable {
        ByteArrayOutputStream out = captureStdout();
        try {
            Effect<RuntimeException, Unit> effect = Effect.from(new LogCapability.Debug("debug-msg"));
            runtime.unsafeRunWithHandler(effect, handler.widen());
        } finally {
            restoreStdout(out);
        }
        assertTrue(out.toString().contains("[DEBUG]"));
        assertTrue(out.toString().contains("debug-msg"));
    }

    @Test
    void warnWritesToStdout() throws Throwable {
        ByteArrayOutputStream out = captureStdout();
        try {
            Effect<RuntimeException, Unit> effect = Effect.from(new LogCapability.Warn("warn-msg"));
            runtime.unsafeRunWithHandler(effect, handler.widen());
        } finally {
            restoreStdout(out);
        }
        assertTrue(out.toString().contains("[WARN]"));
        assertTrue(out.toString().contains("warn-msg"));
    }

    @Test
    void errorWithCauseWritesToStderr() throws Throwable {
        ByteArrayOutputStream err = captureStderr();
        try {
            Effect<RuntimeException, Unit> effect = Effect.from(
                    new LogCapability.Error("error-msg", new RuntimeException("cause")));
            runtime.unsafeRunWithHandler(effect, handler.widen());
        } finally {
            restoreStderr(err);
        }
        assertTrue(err.toString().contains("[ERROR]"));
        assertTrue(err.toString().contains("error-msg"));
    }

    @Test
    void errorWithoutCauseWritesToStderrWithoutNpe() throws Throwable {
        ByteArrayOutputStream err = captureStderr();
        try {
            Effect<RuntimeException, Unit> effect = Effect.from(
                    new LogCapability.Error("no-cause"));
            runtime.unsafeRunWithHandler(effect, handler.widen());
        } finally {
            restoreStderr(err);
        }
        assertTrue(err.toString().contains("[ERROR]"));
        assertTrue(err.toString().contains("no-cause"));
    }

    @Test
    void generateWithMultipleLogCapabilities() throws Throwable {
        ByteArrayOutputStream out = captureStdout();
        try {
            Effect<RuntimeException, Unit> effect = Effect.<RuntimeException, Unit>generate(
                    ctx -> {
                        ctx.perform(new LogCapability.Info("gen-info"));
                        ctx.perform(new LogCapability.Debug("gen-debug"));
                        ctx.perform(new LogCapability.Warn("gen-warn"));
                        return Unit.INSTANCE;
                    },
                    handler.widen()
            );
            runtime.unsafeRun(effect);
        } finally {
            restoreStdout(out);
        }
        String output = out.toString();
        assertTrue(output.contains("gen-info"));
        assertTrue(output.contains("gen-debug"));
        assertTrue(output.contains("gen-warn"));
    }

    @Test
    void capabilityHandlerComposeWorks() throws Throwable {
        ByteArrayOutputStream out = captureStdout();
        CapabilityHandler<com.cajunsystems.roux.capability.Capability<?>> composed =
                CapabilityHandler.compose(handler);
        try {
            Effect<RuntimeException, Unit> effect = Effect.from(new LogCapability.Info("composed"));
            runtime.unsafeRunWithHandler(effect, composed);
        } finally {
            restoreStdout(out);
        }
        assertTrue(out.toString().contains("composed"));
    }

    // --- stdout/stderr capture helpers ---

    private final PrintStream[] savedOut = new PrintStream[1];
    private final PrintStream[] savedErr = new PrintStream[1];

    private ByteArrayOutputStream captureStdout() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        savedOut[0] = System.out;
        System.setOut(new PrintStream(buf));
        return buf;
    }

    private void restoreStdout(ByteArrayOutputStream ignored) {
        System.setOut(savedOut[0]);
    }

    private ByteArrayOutputStream captureStderr() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        savedErr[0] = System.err;
        System.setErr(new PrintStream(buf));
        return buf;
    }

    private void restoreStderr(ByteArrayOutputStream ignored) {
        System.setErr(savedErr[0]);
    }
}
```

**Note on types**: `Effect.from(LogCapability.Info(...))` requires the `Info` record to be a valid `Capability<Unit>`, which it is via the sealed hierarchy. If the compiler needs help inferring `E`, use an explicit type witness.

**Note on `unsafeRunWithHandler` signature**: This method takes `CapabilityHandler<Capability<?>>`. Call `handler.widen()` to convert from `CapabilityHandler<LogCapability>`.

**Deviation rule**: If the compiler objects to `throws Throwable` on test methods (vs `throws Exception`), adapt — check whether `unsafeRunWithHandler` declares `throws E` where `E` could be broader.

Commit:
```bash
git add lib/src/test/java/com/cajunsystems/functional/capabilities/LogCapabilityTest.java
git commit -m "test(4-4): add LogCapabilityTest end-to-end integration"
```

### Task 4 — Compile and test verification

```bash
./gradlew :lib:compileJava
./gradlew :lib:compileTestJava
```

Both must produce BUILD SUCCESSFUL.

```bash
./gradlew :lib:test --tests "com.cajunsystems.functional.capabilities.LogCapabilityTest"
```

All 7 tests must pass.

```bash
./gradlew test
```

Full suite green. No regressions (minimum: 319 tests from Phase 3 + 7 new = 326 total).

**If compile fails**: The most likely issues are:
- `throws Throwable` vs `throws Exception` on test method — adjust to match the checked exception type that `unsafeRunWithHandler` declares
- Type inference on `Effect.from(...)` — add `Effect.<RuntimeException, Unit>from(...)` explicit type
- `CapabilityHandler.compose(handler)` type mismatch — try `CapabilityHandler.compose(new CapabilityHandler<?>[]{handler})` if varargs inference fails

---

## Verification

- [ ] `capabilities/LogCapability.java` created — sealed, extends `Capability<Unit>`, 4 records
- [ ] `capabilities/ConsoleLogHandler.java` created — implements `CapabilityHandler<LogCapability>`
- [ ] `LogCapabilityTest.java` created — 7 tests
- [ ] All 7 `LogCapabilityTest` tests pass
- [ ] `./gradlew :lib:compileJava` → BUILD SUCCESSFUL
- [ ] `./gradlew test` → BUILD SUCCESSFUL, no regressions

## Success Criteria

`LogCapability` and `ConsoleLogHandler` exist, compile, and integrate end-to-end with
`ActorEffectRuntime` via both `Effect.from()` + `unsafeRunWithHandler` and
`Effect.generate()` + `ctx.perform()`. No old effect types anywhere.

## Output
- Created: `lib/src/main/java/com/cajunsystems/functional/capabilities/LogCapability.java`
- Created: `lib/src/main/java/com/cajunsystems/functional/capabilities/ConsoleLogHandler.java`
- Created: `lib/src/test/java/com/cajunsystems/functional/capabilities/LogCapabilityTest.java`
