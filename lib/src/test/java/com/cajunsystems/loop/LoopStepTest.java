package com.cajunsystems.loop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LoopStep} variants and factory methods.
 */
class LoopStepTest {

    @Test
    void continue_createsCorrectVariant() {
        LoopStep<Integer> step = LoopStep.continue_(42);

        assertInstanceOf(LoopStep.Continue.class, step);
        assertEquals(42, step.state());
        assertTrue(step.isContinue());
        assertFalse(step.isStop());
        assertFalse(step.isRestart());
    }

    @Test
    void stop_createsCorrectVariant() {
        LoopStep<String> step = LoopStep.stop("final");

        assertInstanceOf(LoopStep.Stop.class, step);
        assertEquals("final", step.state());
        assertFalse(step.isContinue());
        assertTrue(step.isStop());
        assertFalse(step.isRestart());
    }

    @Test
    void restart_createsCorrectVariant() {
        LoopStep<Integer> step = LoopStep.restart(0);

        assertInstanceOf(LoopStep.Restart.class, step);
        assertEquals(0, step.state());
        assertFalse(step.isContinue());
        assertFalse(step.isStop());
        assertTrue(step.isRestart());
    }

    @Test
    void continue_nullState_allowed() {
        LoopStep<Object> step = LoopStep.continue_(null);
        assertNull(step.state());
        assertTrue(step.isContinue());
    }

    @Test
    void patternMatchingWorksWithSealedInterface() {
        LoopStep<Integer> step = LoopStep.continue_(10);

        String label = switch (step) {
            case LoopStep.Continue<Integer> c -> "continue:" + c.state();
            case LoopStep.Stop<Integer>     s -> "stop:" + s.state();
            case LoopStep.Restart<Integer>  r -> "restart:" + r.state();
        };

        assertEquals("continue:10", label);
    }
}
