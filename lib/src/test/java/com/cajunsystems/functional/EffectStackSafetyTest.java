package com.cajunsystems.functional;

import com.cajunsystems.ActorContext;
import com.cajunsystems.ActorSystem;
import com.cajunsystems.config.ActorSystemConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests to verify stack safety of the Effect monad implementation.
 *
 * <p>These tests ensure that deeply nested map/flatMap chains do not cause
 * stack overflow exceptions, validating the trampolining implementation.
 */
class EffectStackSafetyTest {

    private ActorSystem system;
    private ActorContext mockContext;

    @BeforeEach
    void setUp() {
        system = ActorSystem.create(new ActorSystemConfig("test-system"));
        // Create a minimal mock context for testing
        mockContext = new ActorContext() {
            @Override
            public com.cajunsystems.Pid getSelf() { return null; }
            @Override
            public com.cajunsystems.Pid getSender() { return java.util.Optional.empty(); }
            @Override
            public void tell(com.cajunsystems.Pid target, Object message) {}
            @Override
            public void tellSelf(Object message) {}
            @Override
            public com.cajunsystems.Pid actorOf(Class<? extends com.cajunsystems.Actor> actorClass) { return null; }
            @Override
            public com.cajunsystems.Pid actorOf(String id, Class<? extends com.cajunsystems.Actor> actorClass) { return null; }
            @Override
            public <T> com.cajunsystems.builder.ActorBuilder<T> actorOf(Class<? extends com.cajunsystems.handler.Handler<T>> handlerClass) { return null; }
            @Override
            public <S, M> com.cajunsystems.builder.StatefulActorBuilder<S, M> statefulActorOf(Class<? extends com.cajunsystems.handler.StatefulHandler<S, M>> handlerClass, S initialState) { return null; }
            @Override
            public <M> com.cajunsystems.builder.ActorBuilder<M> childBuilder() { return null; }
            @Override
            public org.slf4j.Logger getLogger() { return org.slf4j.LoggerFactory.getLogger("test"); }
        };
    }

    @AfterEach
    void tearDown() {
        system.shutdown();
    }

    /**
     * Tests that 10,000 chained map operations do not cause stack overflow.
     *
     * <p>Without trampolining, this would fail with StackOverflowError.
     * With trampolining, it should complete successfully.
     */
    @Test
    void testDeepMapChainDoesNotStackOverflow() {
        // Build a chain of 10,000 map operations
        Effect<Integer, String, Integer> effect = Effect.of(0);

        // Chain 10,000 map operations
        for (int i = 0; i < 10_000; i++) {
            effect = effect.map(n -> n + 1);
        }

        // Execute the effect
        EffectResult<Integer, Integer> result = effect.run(42, "test", mockContext);

        // Verify the result
        assertTrue(result instanceof EffectResult.Success);
        EffectResult.Success<Integer, Integer> success = (EffectResult.Success<Integer, Integer>) result;
        assertEquals(10_000, success.resultValue(), "Should apply all 10,000 maps");
        assertEquals(42, success.state(), "State should be unchanged");
    }

    /**
     * Tests that 5,000 chained flatMap operations do not cause stack overflow.
     *
     * <p>flatMap is more complex than map and more prone to stack overflow.
     */
    @Test
    void testDeepFlatMapChainDoesNotStackOverflow() {
        // Build a chain of 5,000 flatMap operations
        Effect<Integer, String, Integer> effect = Effect.of(0);

        // Chain 5,000 flatMap operations
        for (int i = 0; i < 5_000; i++) {
            effect = effect.flatMap(n -> Effect.of(n + 1));
        }

        // Execute the effect
        EffectResult<Integer, Integer> result = effect.run(42, "test", mockContext);

        // Verify the result
        assertTrue(result instanceof EffectResult.Success);
        EffectResult.Success<Integer, Integer> success = (EffectResult.Success<Integer, Integer>) result;
        assertEquals(5_000, success.resultValue(), "Should apply all 5,000 flatMaps");
        assertEquals(42, success.state(), "State should be unchanged");
    }

    /**
     * Tests mixed map and flatMap chains for stack safety.
     */
    @Test
    void testMixedMapAndFlatMapChainDoesNotStackOverflow() {
        // Build a mixed chain of 10,000 operations
        Effect<Integer, String, Integer> effect = Effect.of(0);

        // Alternate between map and flatMap
        for (int i = 0; i < 5_000; i++) {
            effect = effect.map(n -> n + 1);
            effect = effect.flatMap(n -> Effect.of(n + 1));
        }

        // Execute the effect
        EffectResult<Integer, Integer> result = effect.run(42, "test", mockContext);

        // Verify the result
        assertTrue(result instanceof EffectResult.Success);
        EffectResult.Success<Integer, Integer> success = (EffectResult.Success<Integer, Integer>) result;
        assertEquals(10_000, success.resultValue(), "Should apply all 10,000 operations");
    }

    /**
     * Tests that state modifications work correctly with deep chains.
     */
    @Test
    void testDeepChainWithStateModification() {
        // Build an effect that modifies state through a deep chain
        Effect<Integer, String, Void> effect = Effect.modify((Integer s) -> s + 1);

        // Chain 1,000 andThen operations that also modify state
        for (int i = 0; i < 1_000; i++) {
            effect = effect.andThen(Effect.modify((Integer s) -> s + 1));
        }

        // Execute with initial state of 0
        EffectResult<Integer, Void> result = effect.run(0, "test", mockContext);

        // Verify the state was modified correctly
        assertTrue(result instanceof EffectResult.NoResult);
        EffectResult.NoResult<Integer, Void> noResult = (EffectResult.NoResult<Integer, Void>) result;
        assertEquals(1_001, noResult.state(), "State should be incremented 1,001 times");
    }

    /**
     * Tests that error handling works correctly in deep chains.
     */
    @Test
    void testErrorPropagationInDeepChain() {
        // Build a chain that will fail at some point
        Effect<Integer, String, Integer> effect = Effect.of(0);

        // Add 100 successful maps
        for (int i = 0; i < 100; i++) {
            effect = effect.map(n -> n + 1);
        }

        // Add a failing effect
        effect = effect.flatMap(n -> Effect.fail(new RuntimeException("Intentional failure")));

        // Add more maps that should not execute
        for (int i = 0; i < 100; i++) {
            effect = effect.map(n -> n + 1);
        }

        // Execute the effect
        EffectResult<Integer, Integer> result = effect.run(42, "test", mockContext);

        // Verify it failed
        assertTrue(result instanceof EffectResult.Failure);
        EffectResult.Failure<Integer, Integer> failure = (EffectResult.Failure<Integer, Integer>) result;
        assertEquals("Intentional failure", failure.errorValue().getMessage());
    }

    /**
     * Tests recovery in deep chains.
     */
    @Test
    void testRecoveryInDeepChain() {
        // Build a chain with a failure that gets recovered
        Effect<Integer, String, Integer> effect = Effect.<Integer, String, Integer>fail(
            new RuntimeException("Error")
        );

        // Add recovery
        effect = effect.recover(err -> 999);

        // Add 1,000 more maps
        for (int i = 0; i < 1_000; i++) {
            effect = effect.map(n -> n + 1);
        }

        // Execute the effect
        EffectResult<Integer, Integer> result = effect.run(42, "test", mockContext);

        // Verify recovery worked and subsequent maps were applied
        assertTrue(result instanceof EffectResult.Success);
        EffectResult.Success<Integer, Integer> success = (EffectResult.Success<Integer, Integer>) result;
        assertEquals(1_999, success.resultValue(), "Should recover to 999 then add 1,000");
    }

    /**
     * Benchmarks the performance of deep chains.
     *
     * <p>This is not a strict performance test, but ensures that
     * trampolining doesn't add unreasonable overhead.
     */
    @Test
    void testPerformanceOfDeepChains() {
        long startTime = System.currentTimeMillis();

        // Build and execute a very deep chain
        Effect<Integer, String, Integer> effect = Effect.of(0);
        for (int i = 0; i < 50_000; i++) {
            effect = effect.map(n -> n + 1);
        }

        EffectResult<Integer, Integer> result = effect.run(0, "test", mockContext);

        long duration = System.currentTimeMillis() - startTime;

        // Verify correctness
        assertTrue(result instanceof EffectResult.Success);
        assertEquals(50_000, ((EffectResult.Success<Integer, Integer>) result).resultValue());

        // Performance check - should complete in reasonable time (< 5 seconds)
        assertTrue(duration < 5000,
            "Deep chain of 50,000 maps should complete in < 5s, took " + duration + "ms");
    }

    /**
     * Tests that the original map/flatMap implementations from the Effect interface
     * still work (for effects created via lambdas).
     */
    @Test
    void testBackwardCompatibilityWithLambdaEffects() {
        // Create an effect using a lambda (not using factory methods)
        Effect<Integer, String, Integer> effect = (state, message, context) ->
            EffectResult.success(state, 42);

        // Chain some operations
        Effect<Integer, String, Integer> chained = effect
            .map(n -> n * 2)
            .flatMap(n -> Effect.of(n + 10));

        // Execute
        EffectResult<Integer, Integer> result = chained.run(0, "test", mockContext);

        // Verify
        assertTrue(result instanceof EffectResult.Success);
        assertEquals(94, ((EffectResult.Success<Integer, Integer>) result).resultValue());
    }
}
