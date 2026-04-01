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
import java.util.concurrent.atomic.AtomicReference;

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

    /**
     * {@code tap()} runs a side-effect on success and passes the original value through unchanged.
     *
     * <p>Use {@code tap()} for logging, metrics, or notifications that should not
     * change the pipeline's result. The returned value is identical to the input value.
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

    /**
     * {@code tapError()} runs a side-effect on failure and re-throws the original error unchanged.
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
}
