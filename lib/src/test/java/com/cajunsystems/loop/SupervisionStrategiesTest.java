package com.cajunsystems.loop;

import com.cajunsystems.ActorContext;
import com.cajunsystems.roux.Effect;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SupervisionStrategies}.
 *
 * These tests run the behavior synchronously using a mock runtime so no
 * ActorSystem threads are needed.
 */
class SupervisionStrategiesTest {

    private final ActorContext ctx = Mockito.mock(ActorContext.class);

    private static <State> LoopStep<State> run(Effect<RuntimeException, LoopStep<State>> effect) {
        try {
            return com.cajunsystems.roux.runtime.DefaultEffectRuntime.create().unsafeRun(effect);
        } catch (RuntimeException e) {
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    private static <State> LoopStep<State> runChecked(Effect<? extends Exception, LoopStep<State>> effect) {
        try {
            return com.cajunsystems.roux.runtime.DefaultEffectRuntime.create()
                    .unsafeRun((Effect<RuntimeException, LoopStep<State>>) effect);
        } catch (RuntimeException e) {
            throw e;
        }
    }

    // -------------------------------------------------------------------------
    // withResume
    // -------------------------------------------------------------------------

    @Test
    void withResume_normalSuccess_propagates() {
        ActorBehavior<RuntimeException, Integer, String> base =
            (msg, state, c) -> Effect.succeed(LoopStep.continue_(state + 1));

        ActorBehavior<RuntimeException, Integer, String> supervised =
            SupervisionStrategies.withResume(base);

        LoopStep<Integer> step = run(supervised.step("x", 5, ctx));

        assertTrue(step.isContinue());
        assertEquals(6, step.state());
    }

    @Test
    void withResume_onError_continuesWithCurrentState() {
        ActorBehavior<RuntimeException, Integer, String> base =
            (msg, state, c) -> Effect.fail(new RuntimeException("boom"));

        ActorBehavior<RuntimeException, Integer, String> supervised =
            SupervisionStrategies.withResume(base);

        LoopStep<Integer> step = run(supervised.step("x", 99, ctx));

        assertTrue(step.isContinue());
        assertEquals(99, step.state()); // original state preserved
    }

    // -------------------------------------------------------------------------
    // withRestart
    // -------------------------------------------------------------------------

    @Test
    void withRestart_normalSuccess_propagates() {
        ActorBehavior<RuntimeException, Integer, String> base =
            (msg, state, c) -> Effect.succeed(LoopStep.continue_(state + 10));

        ActorBehavior<RuntimeException, Integer, String> supervised =
            SupervisionStrategies.withRestart(base, 0);

        LoopStep<Integer> step = run(supervised.step("x", 5, ctx));

        assertTrue(step.isContinue());
        assertEquals(15, step.state());
    }

    @Test
    void withRestart_onError_returnsRestartWithInitialState() {
        ActorBehavior<RuntimeException, Integer, String> base =
            (msg, state, c) -> Effect.fail(new RuntimeException("oops"));

        ActorBehavior<RuntimeException, Integer, String> supervised =
            SupervisionStrategies.withRestart(base, 0);

        LoopStep<Integer> step = run(supervised.step("x", 99, ctx));

        assertTrue(step.isRestart());
        assertEquals(0, step.state()); // initial state
    }

    // -------------------------------------------------------------------------
    // withStop
    // -------------------------------------------------------------------------

    @Test
    void withStop_onError_returnsStop() {
        ActorBehavior<RuntimeException, Integer, String> base =
            (msg, state, c) -> Effect.fail(new RuntimeException("fatal"));

        ActorBehavior<RuntimeException, Integer, String> supervised =
            SupervisionStrategies.withStop(base);

        LoopStep<Integer> step = run(supervised.step("x", 42, ctx));

        assertTrue(step.isStop());
        assertEquals(42, step.state()); // state at time of failure
    }

    // -------------------------------------------------------------------------
    // withDecision
    // -------------------------------------------------------------------------

    @Test
    void withDecision_customPerExceptionLogic() {
        ActorBehavior<Exception, Integer, String> base =
            (msg, state, c) -> {
                if (msg.equals("transient")) {
                    return Effect.fail(new java.io.IOException("network blip"));
                }
                return Effect.fail(new IllegalStateException("bad state"));
            };

        Integer initialState = 0;
        ActorBehavior<Exception, Integer, String> supervised =
            SupervisionStrategies.withDecision(base, (err, state) ->
                err instanceof java.io.IOException
                    ? LoopStep.restart(initialState)
                    : LoopStep.stop(state));

        // Transient → restart
        LoopStep<Integer> transientStep = runChecked(supervised.step("transient", 7, ctx));
        assertTrue(transientStep.isRestart());
        assertEquals(0, transientStep.state());

        // Permanent → stop
        LoopStep<Integer> permanentStep = runChecked(supervised.step("permanent", 7, ctx));
        assertTrue(permanentStep.isStop());
        assertEquals(7, permanentStep.state());
    }
}
