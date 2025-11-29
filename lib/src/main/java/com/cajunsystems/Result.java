package com.cajunsystems;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Result type for safe error handling with pattern matching.
 * Sealed to ensure exhaustive matching.
 */
public sealed interface Result<T> permits Result.Success, Result.Failure {
    
    /**
     * Successful result containing a value.
     */
    record Success<T>(T value) implements Result<T> {
        @Override
        public boolean isSuccess() {
            return true;
        }
        
        @Override
        public T getOrThrow() {
            return value;
        }
        
        @Override
        public T getOrElse(T defaultValue) {
            return value;
        }
        
        @Override
        public T getOrElse(Function<Throwable, T> fn) {
            return value;
        }
    }
    
    /**
     * Failed result containing an error.
     */
    record Failure<T>(Throwable error) implements Result<T> {
        @Override
        public boolean isSuccess() {
            return false;
        }
        
        @Override
        public T getOrThrow() {
            if (error instanceof RuntimeException re) {
                throw re;
            }
            throw new ReplyException("Reply failed", error);
        }
        
        @Override
        public T getOrElse(T defaultValue) {
            return defaultValue;
        }
        
        @Override
        public T getOrElse(Function<Throwable, T> fn) {
            return fn.apply(error);
        }
    }
    
    // Common operations
    boolean isSuccess();
    T getOrThrow();
    T getOrElse(T defaultValue);
    T getOrElse(Function<Throwable, T> fn);
    
    // Monadic operations
    default <U> Result<U> map(Function<T, U> fn) {
        return switch (this) {
            case Success(var value) -> {
                try {
                    yield new Success<>(fn.apply(value));
                } catch (Exception e) {
                    yield new Failure<>(e);
                }
            }
            case Failure(var error) -> new Failure<>(error);
        };
    }
    
    default <U> Result<U> flatMap(Function<T, Result<U>> fn) {
        return switch (this) {
            case Success(var value) -> {
                try {
                    yield fn.apply(value);
                } catch (Exception e) {
                    yield new Failure<>(e);
                }
            }
            case Failure(var error) -> new Failure<>(error);
        };
    }
    
    default Result<T> recover(Function<Throwable, T> fn) {
        return switch (this) {
            case Success<T> s -> s;
            case Failure(var error) -> {
                try {
                    yield new Success<>(fn.apply(error));
                } catch (Exception e) {
                    yield new Failure<>(e);
                }
            }
        };
    }
    
    default void ifSuccess(Consumer<T> consumer) {
        if (this instanceof Success(var value)) {
            consumer.accept(value);
        }
    }
    
    default void ifFailure(Consumer<Throwable> consumer) {
        if (this instanceof Failure(var error)) {
            consumer.accept(error);
        }
    }
    
    // Factory methods
    static <T> Result<T> success(T value) {
        return new Success<>(value);
    }
    
    static <T> Result<T> failure(Throwable error) {
        return new Failure<>(error);
    }
    
    /**
     * Execute code that might throw and wrap in Result.
     */
    static <T> Result<T> attempt(ThrowingSupplier<T> supplier) {
        try {
            return new Success<>(supplier.get());
        } catch (Exception e) {
            return new Failure<>(e);
        }
    }
    
    @FunctionalInterface
    interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
