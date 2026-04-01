<objective>
Modernize retry and error handling examples to use Roux v0.2.x built-in operators:
- Rewrite EffectRetryExample: replace hand-rolled withRetry(catchAll) with retry(n),
  retryWithDelay(n, Duration), and retry(RetryPolicy).
- Extend EffectErrorHandlingExample: add tap() and tapError() demonstrations.
- Create EffectTimeoutExample: timeout(Duration) with graceful degradation in actors.

No production code changes. Pure example/test file work.
</objective>

<execution_context>
- EffectRetryExample:   lib/src/test/java/examples/EffectRetryExample.java   (3 tests, static withRetry() helper)
- EffectErrorHandlingExample: lib/src/test/java/examples/EffectErrorHandlingExample.java (5 tests)
- New file: lib/src/test/java/examples/EffectTimeoutExample.java
- Package: examples
- Runtime: runtime.unsafeRun(effect) — throws; runtime.unsafeRun(effect.attempt()) → Either
- Actor spawn: spawnEffectActor(system, (MsgType msg) -> Effect<E,A>)
</execution_context>

<context>
## API reference — Roux v0.2.x (from source)

### effect.retry(int maxAttempts)
```java
// maxAttempts = ADDITIONAL retries (not total).
// retry(2) = 3 total attempts (1 initial + 2 retries).
// Error type stays as E (not widened).
// Old withRetry(effect, totalAttempts) → effect.retry(totalAttempts - 1)
default Effect<E, A> retry(int maxAttempts)
```

### effect.retryWithDelay(int maxAttempts, Duration delay)
```java
// Same counting: maxAttempts = ADDITIONAL retries.
// Error type WIDENS to Throwable — test method needs throws Throwable.
default Effect<Throwable, A> retryWithDelay(int maxAttempts, Duration delay)
```

### effect.retry(RetryPolicy)
```java
// Error type WIDENS to Throwable — test method needs throws Throwable.
// RetryPolicy.maxAttempts(n) = n ADDITIONAL retries.
default Effect<Throwable, A> retry(RetryPolicy policy)

// Factories:
RetryPolicy.immediate()                    // no delay, unlimited retries by default
RetryPolicy.fixed(Duration)               // constant delay
RetryPolicy.exponential(Duration base)    // doubles each attempt: base, base*2, base*4…

// Fluent builder:
.maxAttempts(n)                           // cap at n additional retries
.maxDelay(Duration)                       // cap on computed delay
.withJitter(0.2)                          // random spread ±20%
.retryWhen(Predicate<Throwable>)          // only retry matching errors
```

### effect.tap(Consumer<A>)
```java
// Runs action on SUCCESS only; passes original value through unchanged.
// Does NOT change the error type.
default Effect<E, A> tap(Consumer<A> action)
// Pattern: effect.tap(v -> log("result: " + v)) — logging/metrics without altering result
```

### effect.tapError(Consumer<E>)
```java
// Runs action on FAILURE only; re-throws original error unchanged.
// Does NOT swallow the error.
default Effect<E, A> tapError(Consumer<E> action)
// Pattern: effect.tapError(e -> log("error: " + e)) — observe without recovery
```

### effect.timeout(Duration)
```java
// Throws com.cajunsystems.roux.exception.TimeoutException (Roux's own, NOT java.util.concurrent)
// Error type WIDENS to Throwable.
default Effect<Throwable, A> timeout(Duration duration)

// Graceful degradation pattern:
effect.timeout(Duration.ofMillis(50))
      .catchAll(e -> Effect.succeed("fallback"))
// The catchAll receives the TimeoutException and returns fallback — test method stays simple.
```

### import summary for new tests
```java
import com.cajunsystems.roux.RetryPolicy;  // for Task 1
import java.time.Duration;                  // for Task 1 + Task 3
// No explicit import needed for TimeoutException if using catchAll (no instanceof check)
```

## Existing files — what stays, what changes

### EffectRetryExample.java (REWRITE — same 3 tests, different implementation)
Current: static withRetry() helper + 3 tests using it.
Target:
- DELETE withRetry() entirely
- Rewrite test 1 → uses retry(int)
- Rewrite test 2 → uses retryWithDelay(int, Duration) or retry(RetryPolicy)
- Rewrite test 3 → uses retry(RetryPolicy) to show full control

### EffectErrorHandlingExample.java (ADD — 2 new tests at end)
Existing 5 tests stay unchanged.
Add: tapObservesValueWithoutAltering(), tapErrorObservesFailureWithoutSuppressing()

### EffectTimeoutExample.java (CREATE — new file, 3 tests)
New example showing timeout with graceful degradation.
</context>

<tasks>

## Task 1 — Rewrite EffectRetryExample.java

**File**: `lib/src/test/java/examples/EffectRetryExample.java`

Replace the entire file. Remove `withRetry()`. Same 3 tests, modernized:

### Test 1.1 — `builtInRetrySucceedsAfterTransientFailures()`
```java
@Test
void builtInRetrySucceedsAfterTransientFailures() throws Exception {
    AtomicInteger callCount = new AtomicInteger(0);

    // Fails on attempt 1 and 2; succeeds on attempt 3
    Effect<RuntimeException, String> flaky = Effect.suspend(() -> {
        int n = callCount.incrementAndGet();
        if (n < 3) throw new RuntimeException("attempt " + n + " failed");
        return "ok";
    });

    // retry(2) = 2 additional attempts after the first = 3 total
    String result = runtime.unsafeRun(flaky.retry(2));

    assertEquals("ok", result);
    assertEquals(3, callCount.get(), "Should execute exactly 3 times (1 initial + 2 retries)");
}
```

### Test 1.2 — `retryWithDelayPausesAndSucceeds()`
```java
@Test
void retryWithDelayPausesAndSucceeds() throws Throwable {
    AtomicInteger callCount = new AtomicInteger(0);

    Effect<RuntimeException, String> flaky = Effect.suspend(() -> {
        int n = callCount.incrementAndGet();
        if (n < 3) throw new RuntimeException("attempt " + n + " failed");
        return "ok";
    });

    // retryWithDelay(2, 10ms) = 2 additional attempts, 10ms pause each
    // Error type widens to Throwable — method declares throws Throwable
    String result = runtime.unsafeRun(flaky.retryWithDelay(2, Duration.ofMillis(10)));

    assertEquals("ok", result);
    assertEquals(3, callCount.get());
}
```

### Test 1.3 — `retryPolicyExhaustsThenFails()`
```java
@Test
@SuppressWarnings("unchecked")
void retryPolicyExhaustsThenFails() throws Throwable {
    AtomicInteger callCount = new AtomicInteger(0);

    Effect<RuntimeException, String> alwaysFails = Effect.suspend(() -> {
        callCount.incrementAndGet();
        throw new RuntimeException("always fails");
    });

    // RetryPolicy.immediate().maxAttempts(2) = 2 additional = 3 total attempts
    // retry(RetryPolicy) widens error type to Throwable — use attempt() to inspect
    Either<Throwable, String> result =
            runtime.unsafeRun(alwaysFails.retry(
                    RetryPolicy.immediate().maxAttempts(2)
            ).attempt());

    assertTrue(result instanceof Either.Left, "All retries exhausted — expected Left");
    assertEquals(3, callCount.get(), "Should have attempted exactly 3 times");
    assertEquals("always fails",
            ((Either.Left<Throwable, String>) result).value().getMessage());
}
```

**Required imports** (full new header):
```java
package examples;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.functional.ActorEffectRuntime;
import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.RetryPolicy;
import com.cajunsystems.roux.data.Either;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
```

Note: `Pid` and `Unit` imports are no longer needed (removed actor-based retriesUntilSuccess).

After writing, run:
```bash
./gradlew :lib:test --tests "examples.EffectRetryExample"
```
Fix any errors before proceeding to Task 2.

---

## Task 2 — Add tap() and tapError() to EffectErrorHandlingExample.java

**File**: `lib/src/test/java/examples/EffectErrorHandlingExample.java`

Add these imports at the top (if not already present):
```java
import java.util.concurrent.atomic.AtomicReference;
```

Add two new tests at the END of the class (before closing `}`):

### Test 2.1 — `tapObservesSuccessValueWithoutAltering()`
```java
/**
 * {@code tap()} runs a side-effect on success and passes the original value through.
 *
 * <p>Use {@code tap()} for logging, metrics, or notifications that should not
 * change the pipeline's result. The returned value is identical to the input.
 */
@Test
void tapObservesSuccessValueWithoutAltering() throws Exception {
    AtomicReference<String> observed = new AtomicReference<>();

    Effect<RuntimeException, String> effect =
            Effect.<RuntimeException, String>succeed("hello")
                  .tap(v -> observed.set("saw: " + v));

    String result = runtime.unsafeRun(effect);

    assertEquals("hello", result, "tap() must not change the returned value");
    assertEquals("saw: hello", observed.get(), "tap() side-effect must have run");
}
```

### Test 2.2 — `tapErrorObservesFailureWithoutSuppressing()`
```java
/**
 * {@code tapError()} runs a side-effect on failure and re-throws the original error.
 *
 * <p>Unlike {@code catchAll()}, {@code tapError()} does not recover — it only
 * observes the error. Use it for logging or alerting without swallowing exceptions.
 */
@Test
void tapErrorObservesFailureWithoutSuppressing() {
    AtomicReference<String> observed = new AtomicReference<>();

    Effect<RuntimeException, String> effect =
            Effect.<RuntimeException, String>fail(new RuntimeException("boom"))
                  .tapError(e -> observed.set("caught: " + e.getMessage()));

    // tapError does NOT recover — the error still propagates
    RuntimeException thrown = assertThrows(RuntimeException.class,
            () -> runtime.unsafeRun(effect));

    assertEquals("boom", thrown.getMessage(), "tapError() must not swallow the error");
    assertEquals("caught: boom", observed.get(), "tapError() side-effect must have run");
}
```

After writing, run:
```bash
./gradlew :lib:test --tests "examples.EffectErrorHandlingExample"
```
Fix any errors before proceeding to Task 3.

---

## Task 3 — Create EffectTimeoutExample.java

**File**: `lib/src/test/java/examples/EffectTimeoutExample.java`

New file. Write the complete class:

```java
package examples;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.functional.ActorEffectRuntime;
import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.data.Unit;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.cajunsystems.functional.ActorSystemEffectExtensions.spawnEffectActor;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates effect.timeout(Duration) with graceful degradation patterns.
 *
 * <p>Roux's {@code timeout(Duration)} wraps an effect and fails with
 * {@code com.cajunsystems.roux.exception.TimeoutException} if the effect
 * does not complete within the deadline. The error type widens to
 * {@code Throwable}.
 *
 * <p>Key patterns shown:
 * <ul>
 *   <li>Timeout + {@code catchAll} for a fallback value when deadline exceeded</li>
 *   <li>Fast effects that complete within a generous deadline — no interference</li>
 *   <li>Effect actors that surface timeouts as graceful reply messages</li>
 * </ul>
 */
class EffectTimeoutExample {

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
     * A slow effect that exceeds the deadline falls back to a default value via
     * {@code catchAll}.
     *
     * <p>{@code timeout} fails with {@code TimeoutException}; {@code catchAll}
     * catches any error (including timeout) and returns the fallback effect.
     */
    @Test
    void timeoutFallbackReturnsDefaultWhenDeadlineExceeded() throws Exception {
        Effect<RuntimeException, String> slow = Effect.suspend(() -> {
            Thread.sleep(500);
            return "too late";
        });

        // timeout widens to Throwable; catchAll catches TimeoutException → fallback
        String result = runtime.unsafeRun(
                slow.timeout(Duration.ofMillis(50))
                    .catchAll(e -> Effect.succeed("fallback"))
        );

        assertEquals("fallback", result);
    }

    /**
     * A fast effect completes normally when the deadline is generous.
     *
     * <p>Confirms that {@code timeout} does not add overhead or alter results
     * when the effect finishes well within the deadline.
     */
    @Test
    void fastEffectCompletesNormallyUnderTimeout() throws Exception {
        Effect<RuntimeException, String> fast = Effect.suspend(() -> {
            Thread.sleep(10);
            return "on time";
        });

        String result = runtime.unsafeRun(
                fast.timeout(Duration.ofMillis(500))
                    .catchAll(e -> Effect.succeed("fallback"))
        );

        assertEquals("on time", result);
    }

    /**
     * An effect actor uses timeout with fallback to guarantee a reply even
     * when processing takes too long.
     *
     * <p>The actor receives a request with an embedded reply-to {@link Pid}.
     * If computation exceeds the deadline, it replies with {@code "timeout-reply"}
     * instead of waiting indefinitely — the caller always receives a response.
     */
    @Test
    void actorAlwaysRepliesEvenOnTimeout() throws InterruptedException {
        record Request(String input, Pid replyTo) {}

        CountDownLatch latch = new CountDownLatch(2);
        AtomicReference<String> slowReply  = new AtomicReference<>();
        AtomicReference<String> fastReply  = new AtomicReference<>();

        Pid collector = spawnEffectActor(system,
                (String reply) -> Effect.suspend(() -> {
                    // Route reply to correct slot
                    if (slowReply.get() == null) slowReply.set(reply);
                    else                          fastReply.set(reply);
                    latch.countDown();
                    return Unit.unit();
                })
        );

        Pid processor = spawnEffectActor(system,
                (Request req) -> {
                    // Simulate work — sleep duration determined by input
                    int sleepMs = Integer.parseInt(req.input());
                    Effect<RuntimeException, String> work = Effect.suspend(() -> {
                        Thread.sleep(sleepMs);
                        return "processed-" + sleepMs;
                    });

                    // Timeout at 100ms; graceful fallback keeps the reply flowing
                    return work.timeout(Duration.ofMillis(100))
                               .catchAll(e -> Effect.succeed("timeout-reply"))
                               .flatMap(reply -> Effect.runnable(() -> req.replyTo().tell(reply)));
                }
        );

        // Send a slow request (500ms > 100ms deadline) and a fast request (20ms)
        processor.tell(new Request("500", collector));
        processor.tell(new Request("20",  collector));

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals("timeout-reply", slowReply.get(),  "Slow request should get timeout fallback");
        assertEquals("processed-20",  fastReply.get(),  "Fast request should get normal result");
    }
}
```

After writing, run:
```bash
./gradlew :lib:test --tests "examples.EffectTimeoutExample"
```

**If `actorAlwaysRepliesEvenOnTimeout` is flaky** (timing race between slow and fast replies),
simplify the reply routing: use separate collectors per request, or use `ConcurrentHashMap`.

---

## Task 4 — Run full test suite

```bash
./gradlew test
```

Expected: 371 + 2 new tap tests + 3 new timeout tests = **376 tests, 0 failures**.
(EffectRetryExample rewrites keep count at 3, not adding new tests.)

If any test fails:
1. Read the failure stack trace
2. For timing failures, increase bounds (e.g., latch.await timeout, sleep duration)
3. For API mismatches, consult the API reference above
4. Fix minimally — no production code changes

</tasks>

<verification>
- [ ] EffectRetryExample.java rewritten — no withRetry() helper, 3 tests using retry(n)/retryWithDelay/RetryPolicy
- [ ] EffectErrorHandlingExample.java — 2 new tests (tap, tapError) at end, existing 5 unchanged
- [ ] EffectTimeoutExample.java created — 3 tests: fallback, fast-completes, actor-always-replies
- [ ] ./gradlew test → 376 tests, 0 failures
- [ ] Each file committed individually
</verification>

<success_criteria>
- Built-in retry API confirmed working: retry(n), retryWithDelay(n, Duration), retry(RetryPolicy)
- tap() and tapError() patterns demonstrated: observe-without-alter for success and error paths
- timeout(Duration) + catchAll confirmed as graceful degradation pattern in actors
- All examples self-contained and runnable by Cajun library users
- Test count at 376, full suite green
</success_criteria>

<output>
- Modified: lib/src/test/java/examples/EffectRetryExample.java
- Modified: lib/src/test/java/examples/EffectErrorHandlingExample.java
- Created:  lib/src/test/java/examples/EffectTimeoutExample.java
- Created:  .planning/phases/14-modernize-retry-errors/14-1-SUMMARY.md
</output>
