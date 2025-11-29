package com.cajunsystems.functional;

import com.cajunsystems.ActorContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Simple integration tests for Effect-based actors.
 * These tests verify that Effects work correctly with the actor context.
 */
@DisplayName("Simple Effect Actor Tests")
class SimpleEffectActorTest {
    
    sealed interface CounterMsg {}
    record Increment(int amount) implements CounterMsg {}
    record Decrement(int amount) implements CounterMsg {}
    record Reset() implements CounterMsg {}
    
    @Test
    @DisplayName("Effect modifies state correctly")
    void testEffectModifiesState() {
        ActorContext mockContext = mock(ActorContext.class);
        Logger mockLogger = mock(Logger.class);
        when(mockContext.getLogger()).thenReturn(mockLogger);
        
        Effect<Integer, Increment, Void> effect = Effect.<Integer, Increment>modify(s -> s + 10);
        
        EffectResult<Integer, Void> result = effect.run(5, new Increment(10), mockContext);
        
        assertEquals(15, result.state());
        assertFalse(result.hasValue());
    }
    
    @Test
    @DisplayName("Pattern matching effect routes messages")
    void testPatternMatchingEffect() {
        ActorContext mockContext = mock(ActorContext.class);
        Logger mockLogger = mock(Logger.class);
        when(mockContext.getLogger()).thenReturn(mockLogger);
        
        Effect<Integer, CounterMsg, Void> effect = Effect.<Integer, CounterMsg, Void>match()
            .when(Increment.class, (state, msg, ctx) -> 
                Effect.modify(s -> s + msg.amount()))
            .when(Decrement.class, (state, msg, ctx) ->
                Effect.modify(s -> s - msg.amount()))
            .when(Reset.class, (state, msg, ctx) ->
                Effect.setState(0))
            .build();
        
        // Test Increment
        EffectResult<Integer, Void> result1 = effect.run(10, new Increment(5), mockContext);
        assertEquals(15, result1.state());
        
        // Test Decrement
        EffectResult<Integer, Void> result2 = effect.run(10, new Decrement(3), mockContext);
        assertEquals(7, result2.state());
        
        // Test Reset
        EffectResult<Integer, Void> result3 = effect.run(10, new Reset(), mockContext);
        assertEquals(0, result3.state());
    }
    
    @Test
    @DisplayName("Effect composition works correctly")
    void testEffectComposition() {
        ActorContext mockContext = mock(ActorContext.class);
        Logger mockLogger = mock(Logger.class);
        when(mockContext.getLogger()).thenReturn(mockLogger);
        
        Effect<Integer, Increment, Void> effect = Effect.<Integer, Increment>modify(s -> s + 10)
            .andThen(Effect.logState(s -> "State: " + s))
            .andThen(Effect.<Integer, Increment>modify(s -> s * 2));
        
        EffectResult<Integer, Void> result = effect.run(5, new Increment(10), mockContext);
        
        assertEquals(30, result.state()); // (5 + 10) * 2
        verify(mockLogger).info("State: 15");
    }
    
    @Test
    @DisplayName("Effect error handling works")
    void testEffectErrorHandling() {
        ActorContext mockContext = mock(ActorContext.class);
        Logger mockLogger = mock(Logger.class);
        when(mockContext.getLogger()).thenReturn(mockLogger);
        
        Effect<Integer, Increment, String> effect = Effect.<Integer, Increment, Integer>attempt(() -> {
            throw new RuntimeException("Test error");
        })
        .map(n -> "Success: " + n)
        .recover(error -> "Error: " + error.getMessage());
        
        EffectResult<Integer, String> result = effect.run(42, new Increment(10), mockContext);
        
        assertTrue(result.isSuccess());
        assertEquals("Error: Test error", result.value().orElseThrow());
    }
    
    @Test
    @DisplayName("Effect to StatefulHandler conversion")
    void testEffectToStatefulHandlerConversion() {
        Effect<Integer, CounterMsg, Void> effect = Effect.<Integer, CounterMsg>modify(s -> s + 10);
        
        var handler = EffectConversions.toStatefulHandler(effect);
        
        ActorContext mockContext = mock(ActorContext.class);
        Logger mockLogger = mock(Logger.class);
        when(mockContext.getLogger()).thenReturn(mockLogger);
        
        Integer newState = handler.receive(new Increment(10), 5, mockContext);
        
        assertEquals(15, newState);
    }
    
    @Test
    @DisplayName("BiFunction to Effect conversion")
    void testBiFunctionToEffectConversion() {
        java.util.function.BiFunction<Integer, CounterMsg, Integer> oldStyle = 
            (state, msg) -> {
                if (msg instanceof Increment inc) {
                    return state + inc.amount();
                }
                return state;
            };
        
        Effect<Integer, CounterMsg, Void> effect = EffectConversions.fromBiFunction(oldStyle);
        
        ActorContext mockContext = mock(ActorContext.class);
        Logger mockLogger = mock(Logger.class);
        when(mockContext.getLogger()).thenReturn(mockLogger);
        
        EffectResult<Integer, Void> result = effect.run(10, new Increment(5), mockContext);
        
        assertEquals(15, result.state());
    }
}
