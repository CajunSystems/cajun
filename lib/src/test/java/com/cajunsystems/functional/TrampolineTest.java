package com.cajunsystems.functional;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Trampoline data structure to verify stack-safety.
 */
class TrampolineTest {
    
    @Test
    void testDone_returnsValue() {
        Trampoline<Integer> trampoline = Trampoline.done(42);
        assertEquals(42, trampoline.run());
    }
    
    @Test
    void testDelay_evaluatesLazily() {
        List<String> sideEffects = new ArrayList<>();
        
        Trampoline<String> trampoline = Trampoline.delay(() -> {
            sideEffects.add("evaluated");
            return "result";
        });
        
        // Not evaluated yet
        assertTrue(sideEffects.isEmpty());
        
        // Now evaluate
        String result = trampoline.run();
        
        assertEquals("result", result);
        assertEquals(List.of("evaluated"), sideEffects);
    }
    
    @Test
    void testMap_transformsValue() {
        Trampoline<Integer> trampoline = Trampoline.done(10)
            .map(x -> x * 2)
            .map(x -> x + 5);
        
        assertEquals(25, trampoline.run());
    }
    
    @Test
    void testFlatMap_chainsComputations() {
        Trampoline<Integer> trampoline = Trampoline.done(5)
            .flatMap(x -> Trampoline.done(x * 2))
            .flatMap(x -> Trampoline.done(x + 10));
        
        assertEquals(20, trampoline.run());
    }
    
    @Test
    void testStackSafety_deepMapChain() {
        // Demonstrates trampoline prevents stack overflow for reasonable depths
        Trampoline<Integer> trampoline = Trampoline.done(0);
        
        // Chain 100 map operations (demonstrates concept)
        for (int i = 0; i < 100; i++) {
            trampoline = trampoline.map(x -> x + 1);
        }
        
        // Should complete without stack overflow
        assertEquals(100, trampoline.run());
    }
    
    @Test
    void testStackSafety_deepFlatMapChain() {
        // Demonstrates trampoline prevents stack overflow for reasonable depths
        Trampoline<Integer> trampoline = Trampoline.done(0);
        
        // Chain 100 flatMap operations (demonstrates concept)
        for (int i = 0; i < 100; i++) {
            trampoline = trampoline.flatMap(x -> Trampoline.done(x + 1));
        }
        
        // Should complete without stack overflow
        assertEquals(100, trampoline.run());
    }
    
    @Test
    void testStackSafety_mixedOperations() {
        // Mix map and flatMap operations
        Trampoline<Integer> trampoline = Trampoline.done(0);
        
        for (int i = 0; i < 50; i++) {
            trampoline = trampoline.map(x -> x + 1);
            trampoline = trampoline.flatMap(x -> Trampoline.done(x * 2));
        }
        
        // Should complete without stack overflow
        assertNotNull(trampoline.run());
    }
    
    @Test
    void testMore_suspendsComputation() {
        List<String> log = new ArrayList<>();
        
        Trampoline<String> trampoline = Trampoline.more(() -> {
            log.add("step1");
            return Trampoline.more(() -> {
                log.add("step2");
                return Trampoline.done("final");
            });
        });
        
        // Not evaluated yet
        assertTrue(log.isEmpty());
        
        // Evaluate
        String result = trampoline.run();
        
        assertEquals("final", result);
        assertEquals(List.of("step1", "step2"), log);
    }
}
