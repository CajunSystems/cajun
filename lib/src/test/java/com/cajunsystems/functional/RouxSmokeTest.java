package com.cajunsystems.functional;

import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.EffectRuntime;
import com.cajunsystems.roux.runtime.DefaultEffectRuntime;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RouxSmokeTest {

    @Test
    void rouxDefaultRuntimeExecutesSucceedEffect() throws Exception {
        EffectRuntime runtime = DefaultEffectRuntime.create();
        Effect<RuntimeException, Integer> effect = Effect.succeed(42);
        Integer result = runtime.unsafeRun(effect);
        assertEquals(42, result);
    }

    @Test
    void rouxDefaultRuntimeExecutesFlatMap() throws Exception {
        EffectRuntime runtime = DefaultEffectRuntime.create();
        Effect<RuntimeException, Integer> effect = Effect.<RuntimeException, Integer>succeed(10)
                .flatMap(n -> Effect.succeed(n * 2));
        Integer result = runtime.unsafeRun(effect);
        assertEquals(20, result);
    }

    @Test
    void rouxDefaultRuntimeExecutesFailEffect() {
        EffectRuntime runtime = DefaultEffectRuntime.create();
        Effect<RuntimeException, Integer> effect = Effect.fail(new RuntimeException("expected failure"));
        assertThrows(RuntimeException.class, () -> runtime.unsafeRun(effect));
    }
}
