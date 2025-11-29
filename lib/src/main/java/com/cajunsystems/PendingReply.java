package com.cajunsystems;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Implementation of Reply backed by CompletableFuture.
 */
record PendingReply<T>(CompletableFuture<T> future) implements Reply<T> {
    
    // ========== TIER 1: SIMPLE API ==========
    
    @Override
    public T get() {
        try {
            return future.join(); // unchecked blocking
        } catch (CompletionException e) {
            throw new ReplyException("Ask failed", e.getCause());
        }
    }
    
    @Override
    public T get(Duration timeout) throws TimeoutException {
        try {
            return future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            throw e;
        } catch (Exception e) {
            throw new ReplyException("Ask failed", e);
        }
    }
    
    // ========== TIER 2: SAFE API ==========
    
    @Override
    public Result<T> await() {
        try {
            return Result.success(future.join());
        } catch (CompletionException e) {
            return Result.failure(e.getCause());
        } catch (Exception e) {
            return Result.failure(e);
        }
    }
    
    @Override
    public Result<T> await(Duration timeout) {
        try {
            T value = future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            return Result.success(value);
        } catch (TimeoutException e) {
            return Result.failure(e);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return Result.failure(cause);
        }
    }
    
    @Override
    public Optional<Result<T>> poll() {
        if (!future.isDone()) {
            return Optional.empty();
        }
        return Optional.of(await());
    }
    
    // ========== TIER 3: ADVANCED API ==========
    
    @Override
    public CompletableFuture<T> future() {
        return future;
    }
    
    // ========== MONADIC OPERATIONS ==========
    
    @Override
    public <U> Reply<U> map(Function<T, U> fn) {
        return new PendingReply<>(future.thenApply(fn));
    }
    
    @Override
    public <U> Reply<U> flatMap(Function<T, Reply<U>> fn) {
        CompletableFuture<U> composed = future.thenCompose(value -> 
            fn.apply(value).future()
        );
        return new PendingReply<>(composed);
    }
    
    @Override
    public Reply<T> recover(Function<Throwable, T> fn) {
        return new PendingReply<>(future.exceptionally(fn));
    }
    
    @Override
    public Reply<T> recoverWith(Function<Throwable, Reply<T>> fn) {
        CompletableFuture<T> recovered = future.exceptionallyCompose(throwable ->
            fn.apply(throwable).future()
        );
        return new PendingReply<>(recovered);
    }
    
    // ========== CALLBACK API ==========
    
    @Override
    public void onComplete(Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
        future.whenComplete((value, error) -> {
            if (error != null) {
                Throwable cause = error instanceof CompletionException 
                    ? error.getCause() 
                    : error;
                onFailure.accept(cause);
            } else {
                onSuccess.accept(value);
            }
        });
    }
    
    @Override
    public void onSuccess(Consumer<T> onSuccess) {
        future.thenAccept(onSuccess);
    }
    
    @Override
    public void onFailure(Consumer<Throwable> onFailure) {
        future.exceptionally(error -> {
            Throwable cause = error instanceof CompletionException 
                ? error.getCause() 
                : error;
            onFailure.accept(cause);
            return null;
        });
    }
}
