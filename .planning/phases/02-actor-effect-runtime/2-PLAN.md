# Phase 2 Plan: Implement ActorEffectRuntime

## Objective
Create `ActorEffectRuntime` — a Roux `EffectRuntime` that dispatches effect execution
through Cajun's `ActorSystem` executor rather than a fresh virtual-thread pool.

This is the core differentiator between Roux standalone and Roux-in-Cajun. Effects running
inside the actor system share its executor, inheriting its lifecycle and configuration.

## Context

### How DefaultEffectRuntime works (confirmed from Roux source)
- Constructor: `DefaultEffectRuntime(ExecutorService executor, boolean useTrampoline)`
- `DefaultEffectRuntime.create()` passes `Executors.newVirtualThreadPerTaskExecutor()` — its own pool
- All async dispatch goes through `executor.execute(Runnable)` — only method used
- `executor()` method returns the `Executor` view of the service
- `executeFork()` also dispatches via `executor.execute(Runnable)` — same executor, spin-waits for thread assignment

### What we need to implement
`EffectRuntime` has 5 methods. **We do not need to implement any of them directly.**
`DefaultEffectRuntime` already implements all 5. We only need to inject the right executor.

### Executor strategy
- `ActorSystem.getSharedExecutor()` → returns `ExecutorService` if shared executor enabled, `null` otherwise
- `ActorSystem.getThreadPoolFactory().createExecutorService(name)` → creates a new executor from system config
- Strategy: prefer shared executor; if null, create a dedicated one named `"actor-effect-runtime"`

### Implementation approach: extend DefaultEffectRuntime
```java
public class ActorEffectRuntime extends DefaultEffectRuntime {
    public ActorEffectRuntime(ActorSystem system) {
        super(resolveExecutor(system), true);  // true = use trampoline (stack-safe)
    }
    private static ExecutorService resolveExecutor(ActorSystem system) {
        ExecutorService shared = system.getSharedExecutor();
        return shared != null ? shared
                              : system.getThreadPoolFactory().createExecutorService("actor-effect-runtime");
    }
}
```

### File locations
- New production file: `lib/src/main/java/com/cajunsystems/functional/ActorEffectRuntime.java`
- New test file: `lib/src/test/java/com/cajunsystems/functional/ActorEffectRuntimeTest.java`

## Tasks

### Task 1 — Create ActorEffectRuntime.java
**File**: `lib/src/main/java/com/cajunsystems/functional/ActorEffectRuntime.java`

```java
package com.cajunsystems.functional;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.roux.runtime.DefaultEffectRuntime;

import java.util.concurrent.ExecutorService;

/**
 * A Roux {@link com.cajunsystems.roux.EffectRuntime} backed by a Cajun {@link ActorSystem}'s
 * executor.
 *
 * <p>Unlike {@link DefaultEffectRuntime#create()} which allocates a fresh virtual-thread pool,
 * {@code ActorEffectRuntime} dispatches all effect execution through the actor system's executor,
 * integrating effect lifecycle with actor system lifecycle.
 *
 * <p>If the system has a shared executor enabled it is used directly; otherwise a dedicated
 * executor is created from the system's {@link com.cajunsystems.config.ThreadPoolFactory}.
 *
 * <pre>{@code
 * ActorSystem system = new ActorSystem();
 * ActorEffectRuntime runtime = new ActorEffectRuntime(system);
 *
 * Effect<RuntimeException, String> greet = Effect.succeed("hello from actor system");
 * String result = runtime.unsafeRun(greet);
 * }</pre>
 */
public class ActorEffectRuntime extends DefaultEffectRuntime {

    /**
     * Creates an {@code ActorEffectRuntime} that dispatches effects through the given
     * actor system's executor.
     *
     * @param system the actor system whose executor will run effects
     */
    public ActorEffectRuntime(ActorSystem system) {
        super(resolveExecutor(system), true);
    }

    private static ExecutorService resolveExecutor(ActorSystem system) {
        ExecutorService shared = system.getSharedExecutor();
        return shared != null
                ? shared
                : system.getThreadPoolFactory().createExecutorService("actor-effect-runtime");
    }
}
```

### Task 2 — Write ActorEffectRuntimeTest.java
**File**: `lib/src/test/java/com/cajunsystems/functional/ActorEffectRuntimeTest.java`

Tests to cover:
1. `succeed` effect returns correct value
2. `fail` effect throws the error
3. `flatMap` chains correctly
4. `runAsync` invokes success callback
5. `runAsync` invokes error callback on failure
6. `executeFork` + `join` — fiber result retrieved
7. Runtime created from system with shared executor disabled (factory path)
8. Runtime created from system with shared executor enabled (shared path)
9. `executor()` returns non-null `Executor`

```java
package com.cajunsystems.functional;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.config.ThreadPoolFactory;
import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.Fiber;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ActorEffectRuntimeTest {

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
    void succeedEffectReturnsValue() throws Exception {
        Effect<RuntimeException, Integer> effect = Effect.succeed(42);
        assertEquals(42, runtime.unsafeRun(effect));
    }

    @Test
    void failEffectThrowsError() {
        Effect<RuntimeException, Integer> effect =
                Effect.fail(new RuntimeException("actor-effect-failure"));
        RuntimeException ex = assertThrows(RuntimeException.class, () -> runtime.unsafeRun(effect));
        assertEquals("actor-effect-failure", ex.getMessage());
    }

    @Test
    void flatMapChainsEffects() throws Exception {
        Effect<RuntimeException, Integer> effect =
                Effect.<RuntimeException, Integer>succeed(5)
                      .flatMap(n -> Effect.succeed(n * 3));
        assertEquals(15, runtime.unsafeRun(effect));
    }

    @Test
    void runAsyncInvokesSuccessCallback() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Integer> result = new AtomicReference<>();

        runtime.runAsync(
                Effect.<RuntimeException, Integer>succeed(99),
                v  -> { result.set(v); latch.countDown(); },
                err -> latch.countDown()
        );

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(99, result.get());
    }

    @Test
    void runAsyncInvokesErrorCallback() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> caught = new AtomicReference<>();

        runtime.runAsync(
                Effect.<RuntimeException, Integer>fail(new RuntimeException("async-fail")),
                _   -> latch.countDown(),
                err -> { caught.set(err); latch.countDown(); }
        );

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertNotNull(caught.get());
        assertEquals("async-fail", caught.get().getMessage());
    }

    @Test
    void fiberForkAndJoinReturnsResult() throws Exception {
        Effect<RuntimeException, String> forked = Effect.succeed("from-fiber");
        Effect<RuntimeException, String> workflow = forked
                .fork()
                .flatMap(Fiber::join);

        assertEquals("from-fiber", runtime.unsafeRun(workflow));
    }

    @Test
    void executorIsNonNull() {
        assertNotNull(runtime.executor());
    }

    @Test
    void worksWithSharedExecutorEnabled() throws Exception {
        ActorSystem sharedSystem = new ActorSystem(
                new ThreadPoolFactory().setUseSharedExecutor(true)
        );
        try {
            ActorEffectRuntime sharedRuntime = new ActorEffectRuntime(sharedSystem);
            assertEquals("shared", sharedRuntime.unsafeRun(Effect.succeed("shared")));
        } finally {
            sharedSystem.shutdown();
        }
    }

    @Test
    void worksWithSharedExecutorDisabled() throws Exception {
        ActorSystem noSharedSystem = new ActorSystem(
                new ThreadPoolFactory().setUseSharedExecutor(false)
        );
        try {
            ActorEffectRuntime dedicatedRuntime = new ActorEffectRuntime(noSharedSystem);
            assertEquals("dedicated", dedicatedRuntime.unsafeRun(Effect.succeed("dedicated")));
        } finally {
            noSharedSystem.shutdown();
        }
    }
}
```

**Note on `fork()`**: verify the exact API on `Effect` — it may be `Effect.fork(effect)` or `effect.fork()`. Adjust if needed after checking Roux source.

### Task 3 — Compile verification
```bash
./gradlew :lib:compileJava
./gradlew :lib:compileTestJava
```

Both must produce BUILD SUCCESSFUL.

### Task 4 — Run ActorEffectRuntime tests
```bash
./gradlew :lib:test --tests "com.cajunsystems.functional.ActorEffectRuntimeTest"
```

All 9 tests must pass.

### Task 5 — Run full test suite (regression check)
```bash
./gradlew test
```

BUILD SUCCESSFUL. No regressions.

## API Notes (verify before writing)

Before implementing Task 2, confirm the `fork()` API in Roux:
- Check if `Effect` has a `.fork()` instance method returning `Effect<E, Fiber<E, A>>`
- Or if it's `Effect.fork(innerEffect)` static method
- From Roux source: `Effect$Fork` is one of the 10 sealed variants; the `fork()` convenience
  method should be a default method on the `Effect` interface

Check with:
```bash
jar tf ~/.gradle/caches/modules-2/files-2.1/com.cajunsystems/roux/0.1.0/*/roux-0.1.0.jar | grep Fork
```

## Verification

- [ ] `ActorEffectRuntime.java` created in `com.cajunsystems.functional`
- [ ] Extends `DefaultEffectRuntime`, passes system executor to `super()`
- [ ] `resolveExecutor()` handles both shared-enabled and shared-disabled systems
- [ ] All 9 `ActorEffectRuntimeTest` tests pass
- [ ] `./gradlew :lib:compileJava` → BUILD SUCCESSFUL
- [ ] `./gradlew test` → BUILD SUCCESSFUL, no regressions

## Success Criteria

`ActorEffectRuntime` exists, compiles, and all tests pass. Effects dispatched through
it execute on the actor system's executor (not a separate virtual-thread pool). The
runtime is usable standalone: `new ActorEffectRuntime(system)`.

## Output
- Created: `lib/src/main/java/com/cajunsystems/functional/ActorEffectRuntime.java`
- Created: `lib/src/test/java/com/cajunsystems/functional/ActorEffectRuntimeTest.java`
