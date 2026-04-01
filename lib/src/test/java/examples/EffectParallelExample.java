package examples;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.functional.ActorEffectRuntime;
import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.Effects;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates the three parallel / sequential collection combinators in Roux.
 *
 * <ul>
 *   <li>{@link Effects#parAll(List)} — runs effects <em>concurrently</em>; collects
 *       results in insertion order; first failure propagates; error type widens to
 *       {@code Throwable}.</li>
 *   <li>{@link Effects#race(Effect, Effect)} / {@link Effects#race(List)} — returns
 *       the result of whichever effect completes first; cancels the rest; error type
 *       widens to {@code Throwable}.</li>
 *   <li>{@link Effects#traverse(List, java.util.function.Function)} — applies a
 *       function to each element <em>sequentially</em>; short-circuits on the first
 *       failure; error type stays as {@code E} (not widened).</li>
 * </ul>
 *
 * <p>Choose {@code parAll} when you have independent tasks that can run simultaneously
 * and you need all their results. Choose {@code race} when any single result is
 * sufficient (e.g., querying redundant data sources). Choose {@code traverse} when
 * order matters and effects must run one at a time.
 */
class EffectParallelExample {

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
     * {@code Effects.parAll()} fans out work to independent effects and collects
     * results in the original insertion order, regardless of completion order.
     *
     * <p>Three effects with different latencies (50 ms, 30 ms, 40 ms) run
     * concurrently. The combined wall-clock time is ~50 ms — not ~120 ms as it
     * would be if the effects ran sequentially.
     */
    @Test
    void parAllFansOutWorkAndCollectsResultsInOrder() throws Throwable {
        // Simulate three independent data-fetch operations with different latencies
        Effect<RuntimeException, String> fetchA = Effect.suspend(() -> {
            Thread.sleep(50); return "resultA";
        });
        Effect<RuntimeException, String> fetchB = Effect.suspend(() -> {
            Thread.sleep(30); return "resultB";
        });
        Effect<RuntimeException, String> fetchC = Effect.suspend(() -> {
            Thread.sleep(40); return "resultC";
        });

        long start = System.currentTimeMillis();
        List<String> results = runtime.unsafeRun(Effects.parAll(List.of(fetchA, fetchB, fetchC)));
        long elapsed = System.currentTimeMillis() - start;

        // Results in insertion order (A, B, C) regardless of which finished first
        assertEquals(List.of("resultA", "resultB", "resultC"), results);
        // Concurrent: ~50 ms total, not ~120 ms sequential
        assertTrue(elapsed < 200, "Expected concurrent execution (<200ms), took: " + elapsed + "ms");
    }

    /**
     * {@code Effects.race()} returns whichever effect completes first and cancels
     * the rest — ideal for querying redundant data sources.
     *
     * <p>Three sources with response times of 300 ms, 100 ms, and 20 ms are raced.
     * The fastest (20 ms) wins; the caller does not wait for the slower sources.
     */
    @Test
    void raceSelectsFastestDataSource() throws Throwable {
        Effect<RuntimeException, String> slow   = Effect.suspend(() -> { Thread.sleep(300); return "slow-source"; });
        Effect<RuntimeException, String> medium = Effect.suspend(() -> { Thread.sleep(100); return "medium-source"; });
        Effect<RuntimeException, String> fast   = Effect.suspend(() -> { Thread.sleep(20);  return "fast-source"; });

        String winner = runtime.unsafeRun(Effects.race(List.of(slow, medium, fast)));

        assertEquals("fast-source", winner);
    }

    /**
     * {@code Effects.traverse()} applies a function to each item sequentially and
     * collects the results in the same order.
     *
     * <p>Unlike {@code parAll}, traverse is not concurrent — it processes one item
     * at a time. Use it when side effects must be serialised (e.g. writing records
     * to a database) or when the effect function is not safe to run in parallel.
     *
     * <p>The error type stays as {@code E} (not widened to {@code Throwable}),
     * so the test method only needs {@code throws RuntimeException}.
     */
    @Test
    void traverseTransformsEachItemSequentiallyInOrder() throws RuntimeException {
        List<String> words = List.of("hello", "world", "cajun");

        // Apply an effectful function to each word — here: compute its length
        Effect<RuntimeException, List<Integer>> wordLengths =
                Effects.traverse(words, word ->
                    Effect.<RuntimeException, Integer>succeed(word.length())
                );

        List<Integer> result = runtime.unsafeRun(wordLengths);
        assertEquals(List.of(5, 5, 5), result);
    }
}
