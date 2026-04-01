package examples;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.functional.ActorEffectRuntime;
import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.Resource;
import org.junit.jupiter.api.*;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates {@link Resource}{@code <A>} for managed resource lifecycle.
 *
 * <p>A {@code Resource<A>} pairs an <em>acquire</em> effect with a <em>release</em>
 * function. The release is guaranteed to run after the resource is used — whether
 * the consumer succeeds, fails, or is interrupted. This is the functional equivalent
 * of a {@code try-with-resources} block that works inside an effect pipeline.
 *
 * <p>Three factory methods cover the common cases:
 * <ul>
 *   <li>{@link Resource#make(Effect, java.util.function.Function)} — full control over
 *       both acquire and release effects</li>
 *   <li>{@link Resource#fromCloseable(Effect)} — shorthand when the resource implements
 *       {@link AutoCloseable}; calls {@code close()} automatically</li>
 *   <li>{@link Resource#ensuring(Effect, Effect)} — simpler try-finally for effects that
 *       don't require a "handle" (no acquired value passed to consumer)</li>
 * </ul>
 *
 * <p>{@code resource.use()} and {@code Resource.ensuring()} widen the error type to
 * {@code Throwable} — test methods declare {@code throws Throwable}.
 */
class EffectResourceExample {

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
     * {@code Resource.make()} creates a resource with explicit acquire and release.
     *
     * <p>After a successful {@code use()}, the release function is always called.
     * Both the acquisition and the release are tracked via {@link AtomicBoolean}
     * flags so the test can confirm the full lifecycle happened.
     */
    @Test
    void resourceIsAlwaysReleasedAfterSuccessfulUse() throws Throwable {
        AtomicBoolean acquired = new AtomicBoolean(false);
        AtomicBoolean released = new AtomicBoolean(false);

        Resource<String> connection = Resource.make(
                Effect.suspend(() -> { acquired.set(true); return "db-connection"; }),
                conn -> Effect.runnable(() -> released.set(true))
        );

        String result = runtime.unsafeRun(
                connection.use(conn -> Effect.succeed("query-result-from:" + conn))
        );

        assertEquals("query-result-from:db-connection", result);
        assertTrue(acquired.get(), "Resource must have been acquired");
        assertTrue(released.get(), "Resource must be released after successful use");
    }

    /**
     * The release function runs even when {@code use()} throws an error.
     *
     * <p>This is the key guarantee of {@code Resource}: regardless of how the consumer
     * exits (success, failure, or interruption), the resource is always freed. Without
     * this guarantee, transient failures would cause connection or handle leaks.
     */
    @Test
    void resourceIsReleasedEvenWhenUseFails() {
        AtomicBoolean released = new AtomicBoolean(false);

        Resource<String> connection = Resource.make(
                Effect.succeed("db-connection"),
                conn -> Effect.runnable(() -> released.set(true))
        );

        // use() fails midway — release must STILL run
        assertThrows(Throwable.class, () ->
                runtime.unsafeRun(
                        connection.use(conn ->
                                Effect.<RuntimeException, String>fail(
                                        new RuntimeException("query failed"))
                        )
                )
        );

        assertTrue(released.get(), "Resource must be released even when use() fails");
    }

    /**
     * {@code Resource.fromCloseable()} wraps any {@link AutoCloseable} resource.
     *
     * <p>The release action is automatically {@code resource.close()}, eliminating
     * the need to pass an explicit release function. Use this for standard Java
     * resources such as streams, connections, and readers.
     */
    @Test
    void fromCloseableReleasesAutoCloseableOnCompletion() throws Throwable {
        AtomicBoolean closed = new AtomicBoolean(false);

        // Simulate any AutoCloseable resource (InputStream, Connection, etc.)
        AutoCloseable mockStream = () -> closed.set(true);

        Resource<AutoCloseable> resource = Resource.fromCloseable(
                Effect.succeed(mockStream)
        );

        String result = runtime.unsafeRun(
                resource.use(stream -> Effect.succeed("data read from stream"))
        );

        assertEquals("data read from stream", result);
        assertTrue(closed.get(), "AutoCloseable.close() must have been called");
    }

    /**
     * {@code Resource.ensuring()} runs a finalizer after an effect, regardless of outcome.
     *
     * <p>This is the functional {@code try-finally}: the finalizer always executes whether
     * the effect succeeds or fails. Unlike {@code Resource.make()}, there is no acquired
     * value — use {@code ensuring} when cleanup does not depend on a resource handle
     * (e.g. clearing a flag, flushing a buffer, decrementing a counter).
     */
    @Test
    void ensuringRunsFinalizerOnBothSuccessAndFailure() throws Throwable {
        AtomicBoolean finalizerRan = new AtomicBoolean(false);

        // Success case: finalizer still runs after successful effect
        runtime.unsafeRun(Resource.ensuring(
                Effect.succeed("ok"),
                Effect.runnable(() -> finalizerRan.set(true))
        ));
        assertTrue(finalizerRan.get(), "Finalizer must run on success");

        // Failure case: finalizer still runs when effect fails
        finalizerRan.set(false);
        assertThrows(Throwable.class, () ->
                runtime.unsafeRun(Resource.ensuring(
                        Effect.<RuntimeException, String>fail(new RuntimeException("fail")),
                        Effect.runnable(() -> finalizerRan.set(true))
                ))
        );
        assertTrue(finalizerRan.get(), "Finalizer must run on failure");
    }
}
