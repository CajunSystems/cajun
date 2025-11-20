package com.cajunsystems.test;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Helper for simplified ask pattern testing.
 * Provides clean, synchronous-style ask operations with better error handling.
 * 
 * <p>Usage:
 * <pre>{@code
 * // Simple ask
 * Response response = AskTestHelper.ask(actor, new Request("data"), Duration.ofSeconds(2));
 * 
 * // Ask with type safety
 * Integer result = AskTestHelper.ask(actor, new GetValue(), Integer.class, Duration.ofSeconds(2));
 * 
 * // Ask and assert
 * AskTestHelper.askAndAssert(
 *     actor,
 *     new GetValue(),
 *     response -> assertEquals(42, response),
 *     Duration.ofSeconds(2)
 * );
 * }</pre>
 */
public class AskTestHelper {
    
    /**
     * Private constructor to prevent instantiation of utility class.
     */
    private AskTestHelper() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Sends a message and waits for a response.
     * 
     * @param <T> the response type
     * @param actor the actor to ask
     * @param message the message to send
     * @param timeout the maximum time to wait
     * @return the response
     * @throws AssertionError if timeout or error occurs
     */
    @SuppressWarnings("unchecked")
    public static <T> T ask(TestPid<?> actor, Object message, Duration timeout) {
        return ask(actor.pid(), actor.system(), message, timeout);
    }
    
    /**
     * Sends a message and waits for a response.
     * 
     * @param <T> the response type
     * @param pid the actor pid
     * @param system the actor system
     * @param message the message to send
     * @param timeout the maximum time to wait
     * @return the response
     * @throws AssertionError if timeout or error occurs
     */
    @SuppressWarnings("unchecked")
    public static <T> T ask(Pid pid, ActorSystem system, Object message, Duration timeout) {
        try {
            CompletableFuture<Object> future = system.ask(pid, message, timeout);
            return (T) future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new AssertionError(
                String.format("Ask timed out after %s waiting for response to: %s", timeout, message),
                e
            );
        } catch (Exception e) {
            throw new AssertionError("Ask failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * Sends a message and waits for a response of specific type.
     * 
     * @param <T> the response type
     * @param actor the actor to ask
     * @param message the message to send
     * @param responseType the expected response type
     * @param timeout the maximum time to wait
     * @return the response
     * @throws AssertionError if timeout, error, or wrong type
     */
    public static <T> T ask(TestPid<?> actor, Object message, Class<T> responseType, Duration timeout) {
        return ask(actor.pid(), actor.system(), message, responseType, timeout);
    }
    
    /**
     * Sends a message and waits for a response of specific type.
     * 
     * @param <T> the response type
     * @param pid the actor pid
     * @param system the actor system
     * @param message the message to send
     * @param responseType the expected response type
     * @param timeout the maximum time to wait
     * @return the response
     * @throws AssertionError if timeout, error, or wrong type
     */
    public static <T> T ask(Pid pid, ActorSystem system, Object message, Class<T> responseType, Duration timeout) {
        Object response = ask(pid, system, message, timeout);
        
        if (response == null) {
            throw new AssertionError("Expected response of type " + responseType.getName() + " but got null");
        }
        
        if (!responseType.isInstance(response)) {
            throw new AssertionError(
                String.format("Expected response of type %s but got %s",
                    responseType.getName(),
                    response.getClass().getName())
            );
        }
        
        return responseType.cast(response);
    }
    
    /**
     * Sends a message, waits for response, and runs assertion on it.
     * 
     * @param <T> the response type
     * @param actor the actor to ask
     * @param message the message to send
     * @param assertion the assertion to run on response
     * @param timeout the maximum time to wait
     */
    @SuppressWarnings("unchecked")
    public static <T> void askAndAssert(
            TestPid<?> actor,
            Object message,
            java.util.function.Consumer<T> assertion,
            Duration timeout) {
        T response = ask(actor, message, timeout);
        assertion.accept(response);
    }
    
    /**
     * Sends a message, waits for response, and runs assertion on it.
     * 
     * @param <T> the response type
     * @param pid the actor pid
     * @param system the actor system
     * @param message the message to send
     * @param assertion the assertion to run on response
     * @param timeout the maximum time to wait
     */
    @SuppressWarnings("unchecked")
    public static <T> void askAndAssert(
            Pid pid,
            ActorSystem system,
            Object message,
            java.util.function.Consumer<T> assertion,
            Duration timeout) {
        T response = ask(pid, system, message, timeout);
        assertion.accept(response);
    }
    
    /**
     * Sends a message and expects a specific response value.
     * 
     * @param <T> the response type
     * @param actor the actor to ask
     * @param message the message to send
     * @param expected the expected response
     * @param timeout the maximum time to wait
     * @throws AssertionError if response doesn't match expected
     */
    public static <T> void askAndExpect(TestPid<?> actor, Object message, T expected, Duration timeout) {
        askAndExpect(actor.pid(), actor.system(), message, expected, timeout);
    }
    
    /**
     * Sends a message and expects a specific response value.
     * 
     * @param <T> the response type
     * @param pid the actor pid
     * @param system the actor system
     * @param message the message to send
     * @param expected the expected response
     * @param timeout the maximum time to wait
     * @throws AssertionError if response doesn't match expected
     */
    @SuppressWarnings("unchecked")
    public static <T> void askAndExpect(Pid pid, ActorSystem system, Object message, T expected, Duration timeout) {
        T response = ask(pid, system, message, timeout);
        
        if (expected == null) {
            if (response != null) {
                throw new AssertionError("Expected null response but got: " + response);
            }
        } else if (!expected.equals(response)) {
            throw new AssertionError(
                String.format("Expected response: %s but got: %s", expected, response)
            );
        }
    }
    
    /**
     * Tries to ask and returns null if it fails (useful for negative tests).
     * 
     * @param <T> the response type
     * @param actor the actor to ask
     * @param message the message to send
     * @param timeout the maximum time to wait
     * @return the response, or null if failed
     */
    @SuppressWarnings("unchecked")
    public static <T> T tryAsk(TestPid<?> actor, Object message, Duration timeout) {
        return tryAsk(actor.pid(), actor.system(), message, timeout);
    }
    
    /**
     * Tries to ask and returns null if it fails (useful for negative tests).
     * 
     * @param <T> the response type
     * @param pid the actor pid
     * @param system the actor system
     * @param message the message to send
     * @param timeout the maximum time to wait
     * @return the response, or null if failed
     */
    @SuppressWarnings("unchecked")
    public static <T> T tryAsk(Pid pid, ActorSystem system, Object message, Duration timeout) {
        try {
            CompletableFuture<Object> future = system.ask(pid, message, timeout);
            return (T) future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return null;
        }
    }
}
