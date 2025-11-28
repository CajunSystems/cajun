package com.cajunsystems;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Represents a reply to an ask pattern message.
 * Provides three tiers of API:
 * 1. Simple: get() - just blocks and returns value
 * 2. Safe: await() - returns Result for pattern matching
 * 3. Advanced: future() - access underlying CompletableFuture
 */
public interface Reply<T> {
    
    // ========== TIER 1: SIMPLE API ==========
    
    /**
     * Blocks until reply is available and returns the value.
     * Throws unchecked ReplyException if the ask fails.
     * Safe to call on virtual threads.
     */
    T get();
    
    /**
     * Blocks until reply is available or timeout expires.
     * @throws TimeoutException if timeout expires before reply
     * @throws ReplyException if the ask fails
     */
    T get(Duration timeout) throws TimeoutException;
    
    // ========== TIER 2: SAFE API ==========
    
    /**
     * Blocks until reply is available and returns a Result.
     * Use with pattern matching for explicit error handling.
     */
    Result<T> await();
    
    /**
     * Blocks until reply is available or timeout expires.
     * Returns Result with TimeoutException on timeout.
     */
    Result<T> await(Duration timeout);
    
    /**
     * Non-blocking check if reply is available.
     * Returns empty Optional if not yet complete.
     */
    Optional<Result<T>> poll();
    
    // ========== TIER 3: ADVANCED API ==========
    
    /**
     * Access the underlying CompletableFuture for advanced composition.
     */
    CompletableFuture<T> future();
    
    // ========== MONADIC OPERATIONS ==========
    
    /**
     * Transform the reply value when it arrives.
     */
    <U> Reply<U> map(Function<T, U> fn);
    
    /**
     * Chain another async operation on the reply.
     */
    <U> Reply<U> flatMap(Function<T, Reply<U>> fn);
    
    /**
     * Provide a fallback value if the ask fails.
     */
    Reply<T> recover(Function<Throwable, T> fn);
    
    /**
     * Provide a fallback Reply if the ask fails.
     */
    Reply<T> recoverWith(Function<Throwable, Reply<T>> fn);
    
    // ========== CALLBACK API ==========
    
    /**
     * Register callbacks for success and failure.
     * Non-blocking.
     */
    void onComplete(Consumer<T> onSuccess, Consumer<Throwable> onFailure);
    
    /**
     * Register callback for success only.
     * Non-blocking.
     */
    void onSuccess(Consumer<T> onSuccess);
    
    /**
     * Register callback for failure only.
     * Non-blocking.
     */
    void onFailure(Consumer<Throwable> onFailure);
    
    // ========== FACTORY METHODS ==========
    
    /**
     * Create a Reply from a CompletableFuture.
     */
    static <T> Reply<T> from(CompletableFuture<T> future) {
        return new PendingReply<>(future);
    }
    
    /**
     * Create an already-completed successful Reply.
     */
    static <T> Reply<T> completed(T value) {
        return new PendingReply<>(CompletableFuture.completedFuture(value));
    }
    
    /**
     * Create an already-failed Reply.
     */
    static <T> Reply<T> failed(Throwable error) {
        return new PendingReply<>(CompletableFuture.failedFuture(error));
    }
}
