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
