package com.cajunsystems.loop;

import com.cajunsystems.ActorContext;
import com.cajunsystems.loop.middleware.DeadLetterMiddleware;
import com.cajunsystems.loop.middleware.LoggingMiddleware;
import com.cajunsystems.loop.middleware.MetricsMiddleware;
import com.cajunsystems.loop.middleware.RetryMiddleware;
import com.cajunsystems.roux.Effect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for built-in {@link BehaviorMiddleware} implementations.
 * All tests run synchronously — no ActorSystem needed.
 */
class BehaviorMiddlewareTest {

    private ActorContext ctx;

    @BeforeEach
    void setUp() {
        ctx = Mockito.mock(ActorContext.class);
        Mockito.when(ctx.getActorId()).thenReturn("test-actor");
    }

    // Simple synchronous effect runner using Roux's built-in default runtime
    private static <State> LoopStep<State> run(Effect<RuntimeException, LoopStep<State>> effect) {
        try {
            return com.cajunsystems.roux.runtime.DefaultEffectRuntime.create().unsafeRun(effect);
        } catch (RuntimeException e) {
            throw e;
        }
    }

    // -------------------------------------------------------------------------
    // LoggingMiddleware
    // -------------------------------------------------------------------------

    @Test
    void loggingMiddleware_passesThrough_onSuccess() {
        ActorBehavior<RuntimeException, Integer, String> base =
            (msg, state, c) -> Effect.succeed(LoopStep.continue_(state + 1));

        Logger logger = LoggerFactory.getLogger("test");
        ActorBehavior<RuntimeException, Integer, String> logged =
            base.withMiddleware(new LoggingMiddleware<>(logger));

        LoopStep<Integer> step = run(logged.step("hello", 3, ctx));
        assertTrue(step.isContinue());
        assertEquals(4, step.state());
    }

    @Test
    void loggingMiddleware_propagatesError() {
        ActorBehavior<RuntimeException, Integer, String> base =
            (msg, state, c) -> Effect.fail(new RuntimeException("bad"));

        Logger logger = LoggerFactory.getLogger("test");
        ActorBehavior<RuntimeException, Integer, String> logged =
            base.withMiddleware(new LoggingMiddleware<>(logger));

        assertThrows(RuntimeException.class, () -> run(logged.step("hello", 3, ctx)));
    }

    // -------------------------------------------------------------------------
    // MetricsMiddleware
    // -------------------------------------------------------------------------

    @Test
    void metricsMiddleware_recordsSuccessCount() {
        ActorBehavior<RuntimeException, Integer, String> base =
            (msg, state, c) -> Effect.succeed(LoopStep.continue_(state + 1));

        MetricsMiddleware<RuntimeException, Integer, String> metrics =
            new MetricsMiddleware<>("test");
        ActorBehavior<RuntimeException, Integer, String> observed =
            base.withMiddleware(metrics);

        run(observed.step("a", 0, ctx));
        run(observed.step("b", 1, ctx));

        assertEquals(2, metrics.handle().processedCount());
        assertEquals(0, metrics.handle().errorCount());
    }

    @Test
    void metricsMiddleware_recordsErrorCount() {
        ActorBehavior<RuntimeException, Integer, String> base =
            (msg, state, c) -> Effect.fail(new RuntimeException("fail"));

        MetricsMiddleware<RuntimeException, Integer, String> metrics =
            new MetricsMiddleware<>("test");
        ActorBehavior<RuntimeException, Integer, String> observed =
            base.withMiddleware(metrics);

        try { run(observed.step("x", 0, ctx)); } catch (RuntimeException ignored) {}

        assertEquals(0, metrics.handle().processedCount());
        assertEquals(1, metrics.handle().errorCount());
    }

    @Test
    void metricsMiddleware_emitsEvents() {
        List<MetricsMiddleware.StepEvent> events = new ArrayList<>();
        ActorBehavior<RuntimeException, Integer, String> base =
            (msg, state, c) -> Effect.succeed(LoopStep.continue_(state + 1));

        MetricsMiddleware<RuntimeException, Integer, String> metrics =
            new MetricsMiddleware<>("my-actor", events::add);
        ActorBehavior<RuntimeException, Integer, String> observed =
            base.withMiddleware(metrics);

        run(observed.step("Increment", 0, ctx));

        assertEquals(1, events.size());
        MetricsMiddleware.StepEvent event = events.get(0);
        assertEquals("my-actor", event.actorId());
        assertEquals("Continue", event.outcome());
        assertTrue(event.latencyNanos() >= 0);
    }

    @Test
    void metricsMiddleware_recordsStopVariant() {
        ActorBehavior<RuntimeException, Integer, String> base =
            (msg, state, c) -> Effect.succeed(LoopStep.stop(state));

        MetricsMiddleware<RuntimeException, Integer, String> metrics =
            new MetricsMiddleware<>("test");
        ActorBehavior<RuntimeException, Integer, String> observed =
            base.withMiddleware(metrics);

        run(observed.step("x", 5, ctx));

        assertEquals(1, metrics.handle().processedCount());
        assertEquals(1, metrics.handle().stopCount());
    }

    // -------------------------------------------------------------------------
    // RetryMiddleware
    // -------------------------------------------------------------------------

    @Test
    void retryMiddleware_succeedsOnFirstAttempt_noRetry() {
        AtomicInteger attempts = new AtomicInteger();
        ActorBehavior<RuntimeException, Integer, String> base =
            (msg, state, c) -> {
                attempts.incrementAndGet();
                return Effect.succeed(LoopStep.continue_(state + 1));
            };

        ActorBehavior<RuntimeException, Integer, String> retried =
            base.withMiddleware(new RetryMiddleware<>(3));

        LoopStep<Integer> step = run(retried.step("x", 0, ctx));
        assertEquals(1, step.state());
        assertEquals(1, attempts.get()); // only one attempt
    }

    @Test
    void retryMiddleware_retriesUpToMaxAttempts() {
        AtomicInteger attempts = new AtomicInteger();
        ActorBehavior<RuntimeException, Integer, String> base =
            (msg, state, c) -> {
                attempts.incrementAndGet();
                if (attempts.get() < 3) {
                    return Effect.fail(new RuntimeException("transient"));
                }
                return Effect.succeed(LoopStep.continue_(state + 1));
            };

        ActorBehavior<RuntimeException, Integer, String> retried =
            base.withMiddleware(new RetryMiddleware<>(3)); // max 3 attempts

        LoopStep<Integer> step = run(retried.step("x", 0, ctx));
        assertEquals(1, step.state());
        assertEquals(3, attempts.get()); // third attempt succeeded
    }

    @Test
    void retryMiddleware_exhaustsAttempts_propagatesError() {
        AtomicInteger attempts = new AtomicInteger();
        ActorBehavior<RuntimeException, Integer, String> base =
            (msg, state, c) -> {
                attempts.incrementAndGet();
                return Effect.fail(new RuntimeException("permanent"));
            };

        ActorBehavior<RuntimeException, Integer, String> retried =
            base.withMiddleware(new RetryMiddleware<>(3));

        assertThrows(RuntimeException.class, () -> run(retried.step("x", 0, ctx)));
        assertEquals(3, attempts.get()); // all 3 attempts tried
    }

    @Test
    void retryMiddleware_predicate_skipsNonMatchingErrors() {
        ActorBehavior<Exception, Integer, String> base =
            (msg, state, c) -> Effect.fail(new IllegalStateException("permanent"));

        // Only retry IOException; skip IllegalStateException
        ActorBehavior<Exception, Integer, String> retried =
            base.withMiddleware(RetryMiddleware.withPredicate(3,
                err -> err instanceof java.io.IOException));

        // Should fail immediately without retrying (predicate returns false)
        AtomicInteger attempts = new AtomicInteger();
        ActorBehavior<Exception, Integer, String> counted =
            (msg, state, c) -> {
                attempts.incrementAndGet();
                return base.step(msg, state, c);
            };
        ActorBehavior<Exception, Integer, String> retriedCounted =
            counted.withMiddleware(RetryMiddleware.withPredicate(3,
                err -> err instanceof java.io.IOException));

        assertThrows(Exception.class, () ->
            com.cajunsystems.roux.runtime.DefaultEffectRuntime.create()
                .unsafeRun(retriedCounted.step("x", 0, ctx)));

        assertEquals(1, attempts.get()); // Only one attempt — predicate short-circuited
    }

    // -------------------------------------------------------------------------
    // DeadLetterMiddleware
    // -------------------------------------------------------------------------

    @Test
    void deadLetterMiddleware_capturesFailedMessages() {
        List<DeadLetterMiddleware.DeadLetter<String>> captured = new ArrayList<>();
        ActorBehavior<RuntimeException, Integer, String> base =
            (msg, state, c) -> Effect.fail(new RuntimeException("dead"));

        ActorBehavior<RuntimeException, Integer, String> withDlq =
            base.withMiddleware(new DeadLetterMiddleware<>(captured::add));

        try { run(withDlq.step("important-msg", 0, ctx)); } catch (RuntimeException ignored) {}

        assertEquals(1, captured.size());
        assertEquals("important-msg", captured.get(0).message());
        assertEquals("test-actor", captured.get(0).actorId());
        assertNotNull(captured.get(0).error());
        assertNotNull(captured.get(0).timestamp());
    }

    @Test
    void deadLetterMiddleware_doesNotCapture_onSuccess() {
        List<DeadLetterMiddleware.DeadLetter<String>> captured = new ArrayList<>();
        ActorBehavior<RuntimeException, Integer, String> base =
            (msg, state, c) -> Effect.succeed(LoopStep.continue_(state + 1));

        ActorBehavior<RuntimeException, Integer, String> withDlq =
            base.withMiddleware(new DeadLetterMiddleware<>(captured::add));

        run(withDlq.step("msg", 0, ctx));

        assertTrue(captured.isEmpty());
    }

    // -------------------------------------------------------------------------
    // Middleware composition
    // -------------------------------------------------------------------------

    @Test
    void middlewaresCompose_metricsAndLogging() {
        MetricsMiddleware<RuntimeException, Integer, String> metrics =
            new MetricsMiddleware<>("composed");
        Logger logger = LoggerFactory.getLogger("composed-test");

        ActorBehavior<RuntimeException, Integer, String> pipeline =
            ((ActorBehavior<RuntimeException, Integer, String>)
                (msg, state, c) -> Effect.succeed(LoopStep.continue_(state + 1)))
                .withMiddleware(new LoggingMiddleware<>(logger))
                .withMiddleware(metrics);

        run(pipeline.step("test", 10, ctx));
        run(pipeline.step("test", 11, ctx));

        assertEquals(2, metrics.handle().processedCount());
    }
}
