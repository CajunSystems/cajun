package com.cajunsystems.functional;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.Effects;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ActorEffectRuntimeConcurrencyTest {

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

    // -------------------------------------------------------------------------
    // parAll tests
    // -------------------------------------------------------------------------

    @Test
    void parAllCollectsResultsInOrder() throws Throwable {
        List<Effect<RuntimeException, Integer>> effects = List.of(
                Effect.succeed(1),
                Effect.succeed(2),
                Effect.succeed(3)
        );
        List<Integer> result = runtime.unsafeRun(Effects.parAll(effects));
        assertEquals(List.of(1, 2, 3), result);
    }

    @Test
    void parAllRunsEffectsConcurrently() throws Throwable {
        List<Effect<RuntimeException, Integer>> effects = List.of(
                Effect.suspend(() -> { Thread.sleep(50); return 1; }),
                Effect.suspend(() -> { Thread.sleep(50); return 2; }),
                Effect.suspend(() -> { Thread.sleep(50); return 3; })
        );

        long start = System.currentTimeMillis();
        List<Integer> result = runtime.unsafeRun(Effects.parAll(effects));
        long elapsed = System.currentTimeMillis() - start;

        // Sequential would take ~150ms; concurrent should be ~50ms
        assertTrue(elapsed < 200, "Expected concurrent execution (<200ms), took: " + elapsed + "ms");
        assertEquals(List.of(1, 2, 3), result);
    }

    @Test
    void parAllPropagatesFirstFailure() throws Throwable {
        List<Effect<RuntimeException, Integer>> effects = List.of(
                Effect.succeed(1),
                Effect.fail(new RuntimeException("par-fail")),
                Effect.succeed(3)
        );
        assertThrows(RuntimeException.class, () -> runtime.unsafeRun(Effects.parAll(effects)));
    }

    // -------------------------------------------------------------------------
    // race tests
    // -------------------------------------------------------------------------

    @Test
    void raceReturnsFirstCompletingEffect() throws Throwable {
        Effect<RuntimeException, String> slow = Effect.suspend(() -> {
            Thread.sleep(200);
            return "slow";
        });
        Effect<RuntimeException, String> fast = Effect.suspend(() -> {
            Thread.sleep(10);
            return "fast";
        });

        String result = runtime.unsafeRun(Effects.race(slow, fast));
        assertEquals("fast", result);
    }

    // -------------------------------------------------------------------------
    // traverse tests
    // -------------------------------------------------------------------------

    @Test
    void traverseAppliesFunctionToAllItems() throws RuntimeException {
        Effect<RuntimeException, List<Integer>> t =
                Effects.traverse(List.of(1, 2, 3),
                        n -> Effect.<RuntimeException, Integer>succeed(n * 10));
        List<Integer> result = runtime.unsafeRun(t);
        assertEquals(List.of(10, 20, 30), result);
    }

    @Test
    void traverseShortCircuitsOnFailure() {
        Effect<RuntimeException, List<Integer>> t =
                Effects.traverse(List.of(1, 2, 3),
                        n -> n == 2
                                ? Effect.<RuntimeException, Integer>fail(new RuntimeException("fail-at-2"))
                                : Effect.<RuntimeException, Integer>succeed(n));
        assertThrows(RuntimeException.class, () -> runtime.unsafeRun(t));
    }
}
