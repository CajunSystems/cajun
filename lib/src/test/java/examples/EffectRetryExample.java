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

/**
 * Demonstrates Roux's built-in retry operators.
 *
 * <p>Three retry strategies are available:
 * <ul>
 *   <li>{@code effect.retry(n)} — retry up to {@code n} additional times with no delay</li>
 *   <li>{@code effect.retryWithDelay(n, duration)} — retry with a fixed pause between attempts</li>
 *   <li>{@code effect.retry(RetryPolicy)} — full control: backoff strategy, jitter,
 *       max delay, and a per-error predicate for selective retry</li>
 * </ul>
 *
 * <p>Counting convention: {@code n} is the number of <em>additional</em> attempts after
 * the first. {@code retry(2)} = 3 total (1 initial + 2 retries).
 * {@code retryWithDelay} and {@code retry(RetryPolicy)} widen the error type to
 * {@code Throwable} — test methods must declare {@code throws Throwable}.
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
     * {@code effect.retry(n)} retries up to {@code n} additional times with no delay.
     *
     * <p>The effect fails on the first two attempts and succeeds on the third.
     * {@code retry(2)} allows exactly 2 additional attempts = 3 total.
     */
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

    /**
     * {@code effect.retryWithDelay(n, delay)} sleeps between each retry.
     *
     * <p>The same counting convention applies: {@code retryWithDelay(2, 10ms)} allows
     * 2 additional retries = 3 total attempts. The error type widens to {@code Throwable}.
     */
    @Test
    void retryWithDelayPausesAndSucceeds() throws Throwable {
        AtomicInteger callCount = new AtomicInteger(0);

        Effect<RuntimeException, String> flaky = Effect.suspend(() -> {
            int n = callCount.incrementAndGet();
            if (n < 3) throw new RuntimeException("attempt " + n + " failed");
            return "ok";
        });

        // retryWithDelay widens error type to Throwable — method declares throws Throwable
        String result = runtime.unsafeRun(flaky.retryWithDelay(2, Duration.ofMillis(10)));

        assertEquals("ok", result);
        assertEquals(3, callCount.get(), "Should execute exactly 3 times");
    }

    /**
     * {@code effect.retry(RetryPolicy)} gives full control over the backoff strategy.
     *
     * <p>{@link RetryPolicy} supports immediate, fixed, and exponential backoff;
     * a maximum number of additional attempts; a maximum delay cap; jitter; and
     * a per-error predicate for selective retry.
     *
     * <p>When all attempts are exhausted, the final error is propagated.
     * Using {@code attempt()} materialises it as {@code Either.Left} for inspection.
     */
    @Test
    @SuppressWarnings("unchecked")
    void retryPolicyExhaustsThenFails() throws Throwable {
        AtomicInteger callCount = new AtomicInteger(0);

        Effect<RuntimeException, String> alwaysFails = Effect.suspend(() -> {
            callCount.incrementAndGet();
            throw new RuntimeException("always fails");
        });

        // RetryPolicy.immediate().maxAttempts(2) = 2 additional retries = 3 total attempts
        // retry(RetryPolicy) widens error type to Throwable — use attempt() to inspect result
        Either<Throwable, String> result =
                runtime.unsafeRun(alwaysFails.retry(
                        RetryPolicy.immediate().maxAttempts(2)
                ).attempt());

        assertTrue(result instanceof Either.Left, "All retries exhausted — expected Left");
        assertEquals(3, callCount.get(), "Should have attempted exactly 3 times");
        assertEquals("always fails",
                ((Either.Left<Throwable, String>) result).value().getMessage());
    }
}
