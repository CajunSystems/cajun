# Phase 7 Plan: Error Handling & Recovery Patterns

## Objective
Write two self-contained runnable examples that demonstrate Roux's error handling
operators in Cajun effect actors. Target audience: Cajun library users learning
the effect actor API.

## Context

### Research findings (task 7.1 — completed inline)

**Confirmed Roux error operator surface** (`javap` of `roux-0.1.0.jar`):

| Operator | Signature | Notes |
|---|---|---|
| `Effect.fail(e)` | `static <E,A> Effect<E,A>` | create a failed effect |
| `.catchAll(fn)` | `Function<E, Effect<E,A>> → Effect<E,A>` | recover by returning new effect |
| `.orElse(effect)` | `Effect<E,A> → Effect<E,A>` | fallback on failure |
| `.mapError(fn)` | `Function<E, E2> → Effect<E2,A>` | transform error type |
| `.attempt()` | `→ Effect<Throwable, Either<E,A>>` | materialise error as value; **never throws** |

**`Either<L,R>` API:**
- `Either.Left<L,R>` — record, `L value()` accessor
- `Either.Right<L,R>` — record, `R value()` accessor
- No `fold` — use `instanceof` pattern matching or `assertInstanceOf`
- `Either.left(L)` / `Either.right(R)` static factories

**No built-in `retry`** — compose from `catchAll` recursively.

**Error propagation in `EffectActorBuilder`:** if the effect pipeline still fails
after recovery, the runtime throws and the actor's supervisor catches it. The
`catchAll` / `orElse` / `attempt` operators prevent this by recovering inside
the pipeline.

**Key API notes (carried from prior phases):**
- `Unit.unit()` — public factory (`Unit.INSTANCE` is private)
- `Effect.suspend(ThrowingSupplier<A>)` — executes supplier on each run; rethrowing
  makes it a failed effect with that exception as error `E`
- `runtime.unsafeRun(effect)` throws `E` — for `attempt()`, `E = Throwable`,
  so test methods need `throws Throwable`
- `@SuppressWarnings("unchecked")` required when casting raw `Either.Left` / `Either.Right`

### Existing examples to avoid overlapping
- `EffectActorExample.java` — basic flatMap pipeline, suspend, logging counter
- `CapabilityIntegrationTest.java` — EchoCapability compose/orElse (capability layer)
- `ActorEffectRuntimeTest.java` — `fail` + `runAsync` error callback

### File locations
- New: `lib/src/test/java/examples/EffectErrorHandlingExample.java`
- New: `lib/src/test/java/examples/EffectRetryExample.java`

---

## Tasks

### Task 1 — Write EffectErrorHandlingExample.java

**File**: `lib/src/test/java/examples/EffectErrorHandlingExample.java`

Five tests covering each error operator. Uses `ActorEffectRuntime` directly for
operators that return a value (`orElse`, `mapError`, `attempt`) and
`EffectActorBuilder` for the actor-context demo (`catchAll` inside a parser actor).

```java
package examples;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.functional.ActorEffectRuntime;
import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.data.Either;
import com.cajunsystems.roux.data.Unit;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.cajunsystems.functional.ActorSystemEffectExtensions.spawnEffectActor;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates error handling and recovery patterns for effect-based actors.
 *
 * <p>Shows how to express validation, fallback, error transformation, and
 * error materialisation using Roux's Effect error operators:
 * <ul>
 *   <li>{@code Effect.fail()} — explicitly fail an effect</li>
 *   <li>{@code .catchAll(fn)} — recover from any error by returning a new effect</li>
 *   <li>{@code .orElse(effect)} — fall back to another effect on failure</li>
 *   <li>{@code .mapError(fn)} — transform the error type</li>
 *   <li>{@code .attempt()} — materialise errors as {@code Either<E,A>} without throwing</li>
 * </ul>
 */
class EffectErrorHandlingExample {

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

    /**
     * An actor that parses integers from strings, recovering bad input
     * with {@code -1} via {@code catchAll}.
     *
     * <p>The error is handled entirely inside the Effect pipeline — the actor
     * never throws, regardless of the input.
     */
    @Test
    void parseActorRecoversBadInputViaCatchAll() throws InterruptedException {
        List<Integer> results = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(2);

        Pid parser = spawnEffectActor(system,
            (String input) ->
                Effect.<RuntimeException, Integer>suspend(() -> Integer.parseInt(input))
                    .catchAll(err -> Effect.succeed(-1))
                    .flatMap(n -> Effect.suspend(() -> {
                        results.add(n);
                        latch.countDown();
                        return Unit.unit();
                    }))
        );

        parser.tell("42");
        parser.tell("not-a-number");

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(results.contains(42));
        assertTrue(results.contains(-1));
    }

    /**
     * When the primary effect fails, {@code orElse} runs the fallback effect.
     *
     * <p>Unlike {@code catchAll}, {@code orElse} ignores the error value —
     * use it when any failure should trigger the same fallback.
     */
    @Test
    void orElseFallsBackWhenPrimaryFails() throws Exception {
        Effect<RuntimeException, String> primary =
                Effect.fail(new RuntimeException("primary unavailable"));
        Effect<RuntimeException, String> fallback = Effect.succeed("default-value");

        String result = runtime.unsafeRun(primary.orElse(fallback));

        assertEquals("default-value", result);
    }

    /**
     * {@code mapError} transforms one exception type to another.
     *
     * <p>Use this to translate library/framework exceptions into domain-specific
     * error types at the boundary of your effect pipeline.
     */
    @Test
    void mapErrorTransformsExceptionType() {
        Effect<NumberFormatException, Integer> parseAttempt =
                Effect.suspend(() -> Integer.parseInt("not-a-number"));

        // Translate to a domain exception; preserve the original as cause
        Effect<IllegalArgumentException, Integer> domainError = parseAttempt.mapError(nfe ->
                new IllegalArgumentException("Invalid number input: " + nfe.getMessage(), nfe));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> runtime.unsafeRun(domainError));

        assertTrue(ex.getMessage().startsWith("Invalid number input:"));
        assertNotNull(ex.getCause(), "Original NumberFormatException should be preserved as cause");
    }

    /**
     * {@code attempt()} materialises a failed effect as {@code Either.Left} —
     * the error is returned as a value, not thrown.
     *
     * <p>Use {@code attempt()} when you need to inspect or route on the error
     * without propagating it as an exception through the actor system.
     *
     * <p>Note: {@code attempt()} widens the error type to {@code Throwable},
     * so the test method declares {@code throws Throwable}.
     */
    @Test
    @SuppressWarnings("unchecked")
    void attemptMaterializesFailureAsEitherLeft() throws Throwable {
        Effect<RuntimeException, Integer> failing =
                Effect.fail(new RuntimeException("computation failed"));

        Either<RuntimeException, Integer> result = runtime.unsafeRun(failing.attempt());

        assertTrue(result instanceof Either.Left, "Expected Left for failed effect");
        RuntimeException captured = ((Either.Left<RuntimeException, Integer>) result).value();
        assertEquals("computation failed", captured.getMessage());
    }

    /**
     * {@code attempt()} wraps a successful result in {@code Either.Right}.
     *
     * <p>This lets you use a uniform {@code Either}-based API regardless of
     * whether the effect succeeded or failed.
     */
    @Test
    @SuppressWarnings("unchecked")
    void attemptMaterializesSuccessAsEitherRight() throws Throwable {
        Effect<RuntimeException, Integer> success = Effect.succeed(42);

        Either<RuntimeException, Integer> result = runtime.unsafeRun(success.attempt());

        assertTrue(result instanceof Either.Right, "Expected Right for successful effect");
        Integer value = ((Either.Right<RuntimeException, Integer>) result).value();
        assertEquals(42, value);
    }
}
```

Commit:
```bash
git add lib/src/test/java/examples/EffectErrorHandlingExample.java
git commit -m "feat(7-7): add EffectErrorHandlingExample with catchAll/orElse/mapError/attempt"
```

---

### Task 2 — Write EffectRetryExample.java

**File**: `lib/src/test/java/examples/EffectRetryExample.java`

Demonstrates that higher-level resilience behaviours (retry) can be assembled
from Roux's primitive operators without any framework support.

The static `withRetry` helper builds a chain of `catchAll` recoveries. Because
`Effect.suspend(supplier)` re-evaluates the supplier on every run, passing the
same `Effect` object across retries correctly re-executes the operation.

```java
package examples;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.functional.ActorEffectRuntime;
import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.data.Either;
import com.cajunsystems.roux.data.Unit;
import org.junit.jupiter.api.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.cajunsystems.functional.ActorSystemEffectExtensions.spawnEffectActor;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates retry-with-limit composed from Roux Effect primitives.
 *
 * <p>Roux has no built-in retry operator, but retry is easily expressed by
 * recursively chaining {@code .catchAll()} — each catch attaches another attempt
 * as the recovery effect:
 *
 * <pre>{@code
 * // 3-attempt retry:
 * effect.catchAll(err -> effect.catchAll(err2 -> effect))
 * }</pre>
 *
 * <p>This pattern shows that higher-level resilience behaviours can be assembled
 * from a small set of Effect primitives without framework support.
 */
class EffectRetryExample {

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

    /**
     * Builds a retry chain: runs {@code effect}; on failure recursively retries
     * up to {@code maxAttempts - 1} additional times.
     *
     * <p>The chain is built eagerly but executed lazily by the runtime.
     * {@code Effect.suspend(supplier)} re-evaluates its supplier on every run,
     * so each retry correctly re-executes the operation.
     *
     * @param effect      the effect to retry (same description is reused)
     * @param maxAttempts total number of attempts including the first (min 1)
     */
    private static <E extends Throwable, A> Effect<E, A> withRetry(
            Effect<E, A> effect, int maxAttempts) {
        if (maxAttempts <= 1) return effect;
        return effect.catchAll(err -> withRetry(effect, maxAttempts - 1));
    }

    /**
     * When the operation succeeds on the first attempt, no retries are triggered.
     */
    @Test
    void firstAttemptSucceeds() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);

        Effect<RuntimeException, String> alwaysSucceeds = Effect.suspend(() -> {
            callCount.incrementAndGet();
            return "ok";
        });

        String result = runtime.unsafeRun(withRetry(alwaysSucceeds, 3));

        assertEquals("ok", result);
        assertEquals(1, callCount.get(), "Should only execute once when first attempt succeeds");
    }

    /**
     * When the operation fails initially, retries continue until success.
     *
     * <p>Demonstrated inside an effect actor: the retry chain is built fresh
     * for each incoming message, so the attempt counter resets per message.
     */
    @Test
    void retriesUntilSuccess() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> captured = new AtomicReference<>();

        Pid pid = spawnEffectActor(system,
            (Integer failTimes) -> {
                // Fresh counter per message — tracks attempts within this retry chain
                AtomicInteger attempts = new AtomicInteger(0);

                Effect<RuntimeException, String> flaky = Effect.suspend(() -> {
                    int n = attempts.incrementAndGet();
                    if (n <= failTimes) {
                        throw new RuntimeException("attempt " + n + " failed");
                    }
                    return "success on attempt " + n;
                });

                return withRetry(flaky, failTimes + 1)  // +1: failTimes failures + 1 success
                        .flatMap(result -> Effect.suspend(() -> {
                            captured.set(result);
                            latch.countDown();
                            return Unit.unit();
                        }));
            }
        );

        pid.tell(2); // fails on attempt 1 and 2, succeeds on attempt 3

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals("success on attempt 3", captured.get());
    }

    /**
     * When all attempts are exhausted, the final failure is propagated.
     *
     * <p>Uses {@code attempt()} so we can verify the failure without the
     * assertion itself throwing — demonstrates combining retry with attempt()
     * to inspect the terminal error.
     */
    @Test
    @SuppressWarnings("unchecked")
    void exhaustedRetriesPropagateError() throws Throwable {
        AtomicInteger callCount = new AtomicInteger(0);

        Effect<RuntimeException, String> alwaysFails = Effect.suspend(() -> {
            callCount.incrementAndGet();
            throw new RuntimeException("always fails");
        });

        Either<RuntimeException, String> result =
                runtime.unsafeRun(withRetry(alwaysFails, 3).attempt());

        assertTrue(result instanceof Either.Left, "All retries exhausted — expected Left");
        assertEquals(3, callCount.get(), "Should have attempted exactly 3 times");
        assertEquals("always fails",
                ((Either.Left<RuntimeException, String>) result).value().getMessage());
    }
}
```

Commit:
```bash
git add lib/src/test/java/examples/EffectRetryExample.java
git commit -m "feat(7-7): add EffectRetryExample demonstrating retry composed from catchAll"
```

---

### Task 3 — Run tests and verify

```bash
./gradlew test --no-daemon
```

Must produce BUILD SUCCESSFUL. The two new example classes add 8 tests.

Expected minimum: **349 tests** (341 existing + 5 from EffectErrorHandlingExample + 3 from EffectRetryExample).

No commit for this task — verification only.

---

## Verification

- [ ] `EffectErrorHandlingExample.java` created — 5 tests (catchAll, orElse, mapError, attempt-left, attempt-right)
- [ ] `EffectRetryExample.java` created — 3 tests (first-attempt-succeeds, retries-until-success, exhausted-propagates)
- [ ] `withRetry` private static helper in retry example file
- [ ] `./gradlew test` → BUILD SUCCESSFUL, ≥ 349 tests pass

## Success Criteria

Both example files compile and all 8 new tests pass. Each test is self-contained
(creates and shuts down its own `ActorSystem`) and demonstrates exactly one error
handling concept with clear Javadoc explaining when to use it.

## Output
- Created: `lib/src/test/java/examples/EffectErrorHandlingExample.java`
- Created: `lib/src/test/java/examples/EffectRetryExample.java`
