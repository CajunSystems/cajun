package com.cajunsystems.persistence.redis;

import com.cajunsystems.persistence.SnapshotEntry;
import com.cajunsystems.serialization.JavaSerializationProvider;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RedisSnapshotStoreTest {

    @Mock
    private StatefulRedisConnection<String, byte[]> connection;

    @Mock
    private RedisAsyncCommands<String, byte[]> async;

    private RedisSnapshotStore<String> store;

    @BeforeEach
    void setUp() {
        when(connection.async()).thenReturn(async);
        store = new RedisSnapshotStore<>(connection, "cajun", JavaSerializationProvider.INSTANCE);
    }

    /**
     * Creates a RedisFuture backed by a completed CompletableFuture.
     * Avoids mocking default interface methods which causes Mockito strict stubbing issues.
     */
    private <T> RedisFuture<T> completedFuture(T value) {
        CompletableFuture<T> cf = CompletableFuture.completedFuture(value);
        return new RedisFuture<T>() {
            @Override public String getError() { return null; }
            @Override public boolean await(long timeout, TimeUnit unit) { return true; }
            @Override public boolean cancel(boolean mayInterruptIfRunning) { return cf.cancel(mayInterruptIfRunning); }
            @Override public boolean isCancelled() { return cf.isCancelled(); }
            @Override public boolean isDone() { return true; }
            @Override public T get() throws InterruptedException, ExecutionException { return cf.get(); }
            @Override public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException { return cf.get(timeout, unit); }
            @Override public CompletableFuture<T> toCompletableFuture() { return cf; }
            @Override public <U> java.util.concurrent.CompletionStage<U> thenApply(Function<? super T, ? extends U> fn) { return cf.thenApply(fn); }
            @Override public <U> java.util.concurrent.CompletionStage<U> thenApplyAsync(Function<? super T, ? extends U> fn) { return cf.thenApplyAsync(fn); }
            @Override public <U> java.util.concurrent.CompletionStage<U> thenApplyAsync(Function<? super T, ? extends U> fn, java.util.concurrent.Executor executor) { return cf.thenApplyAsync(fn, executor); }
            @Override public java.util.concurrent.CompletionStage<Void> thenAccept(Consumer<? super T> action) { return cf.thenAccept(action); }
            @Override public java.util.concurrent.CompletionStage<Void> thenAcceptAsync(Consumer<? super T> action) { return cf.thenAcceptAsync(action); }
            @Override public java.util.concurrent.CompletionStage<Void> thenAcceptAsync(Consumer<? super T> action, java.util.concurrent.Executor executor) { return cf.thenAcceptAsync(action, executor); }
            @Override public java.util.concurrent.CompletionStage<Void> thenRun(Runnable action) { return cf.thenRun(action); }
            @Override public java.util.concurrent.CompletionStage<Void> thenRunAsync(Runnable action) { return cf.thenRunAsync(action); }
            @Override public java.util.concurrent.CompletionStage<Void> thenRunAsync(Runnable action, java.util.concurrent.Executor executor) { return cf.thenRunAsync(action, executor); }
            @Override public <U, V> java.util.concurrent.CompletionStage<V> thenCombine(java.util.concurrent.CompletionStage<? extends U> other, BiFunction<? super T, ? super U, ? extends V> fn) { return cf.thenCombine(other, fn); }
            @Override public <U, V> java.util.concurrent.CompletionStage<V> thenCombineAsync(java.util.concurrent.CompletionStage<? extends U> other, BiFunction<? super T, ? super U, ? extends V> fn) { return cf.thenCombineAsync(other, fn); }
            @Override public <U, V> java.util.concurrent.CompletionStage<V> thenCombineAsync(java.util.concurrent.CompletionStage<? extends U> other, BiFunction<? super T, ? super U, ? extends V> fn, java.util.concurrent.Executor executor) { return cf.thenCombineAsync(other, fn, executor); }
            @Override public <U> java.util.concurrent.CompletionStage<Void> thenAcceptBoth(java.util.concurrent.CompletionStage<? extends U> other, BiConsumer<? super T, ? super U> action) { return cf.thenAcceptBoth(other, action); }
            @Override public <U> java.util.concurrent.CompletionStage<Void> thenAcceptBothAsync(java.util.concurrent.CompletionStage<? extends U> other, BiConsumer<? super T, ? super U> action) { return cf.thenAcceptBothAsync(other, action); }
            @Override public <U> java.util.concurrent.CompletionStage<Void> thenAcceptBothAsync(java.util.concurrent.CompletionStage<? extends U> other, BiConsumer<? super T, ? super U> action, java.util.concurrent.Executor executor) { return cf.thenAcceptBothAsync(other, action, executor); }
            @Override public java.util.concurrent.CompletionStage<Void> runAfterBoth(java.util.concurrent.CompletionStage<?> other, Runnable action) { return cf.runAfterBoth(other, action); }
            @Override public java.util.concurrent.CompletionStage<Void> runAfterBothAsync(java.util.concurrent.CompletionStage<?> other, Runnable action) { return cf.runAfterBothAsync(other, action); }
            @Override public java.util.concurrent.CompletionStage<Void> runAfterBothAsync(java.util.concurrent.CompletionStage<?> other, Runnable action, java.util.concurrent.Executor executor) { return cf.runAfterBothAsync(other, action, executor); }
            @Override public <U> java.util.concurrent.CompletionStage<U> applyToEither(java.util.concurrent.CompletionStage<? extends T> other, Function<? super T, U> fn) { return cf.applyToEither(other, fn); }
            @Override public <U> java.util.concurrent.CompletionStage<U> applyToEitherAsync(java.util.concurrent.CompletionStage<? extends T> other, Function<? super T, U> fn) { return cf.applyToEitherAsync(other, fn); }
            @Override public <U> java.util.concurrent.CompletionStage<U> applyToEitherAsync(java.util.concurrent.CompletionStage<? extends T> other, Function<? super T, U> fn, java.util.concurrent.Executor executor) { return cf.applyToEitherAsync(other, fn, executor); }
            @Override public java.util.concurrent.CompletionStage<Void> acceptEither(java.util.concurrent.CompletionStage<? extends T> other, Consumer<? super T> action) { return cf.acceptEither(other, action); }
            @Override public java.util.concurrent.CompletionStage<Void> acceptEitherAsync(java.util.concurrent.CompletionStage<? extends T> other, Consumer<? super T> action) { return cf.acceptEitherAsync(other, action); }
            @Override public java.util.concurrent.CompletionStage<Void> acceptEitherAsync(java.util.concurrent.CompletionStage<? extends T> other, Consumer<? super T> action, java.util.concurrent.Executor executor) { return cf.acceptEitherAsync(other, action, executor); }
            @Override public java.util.concurrent.CompletionStage<Void> runAfterEither(java.util.concurrent.CompletionStage<?> other, Runnable action) { return cf.runAfterEither(other, action); }
            @Override public java.util.concurrent.CompletionStage<Void> runAfterEitherAsync(java.util.concurrent.CompletionStage<?> other, Runnable action) { return cf.runAfterEitherAsync(other, action); }
            @Override public java.util.concurrent.CompletionStage<Void> runAfterEitherAsync(java.util.concurrent.CompletionStage<?> other, Runnable action, java.util.concurrent.Executor executor) { return cf.runAfterEitherAsync(other, action, executor); }
            @Override public <U> java.util.concurrent.CompletionStage<U> thenCompose(Function<? super T, ? extends java.util.concurrent.CompletionStage<U>> fn) { return cf.thenCompose(fn); }
            @Override public <U> java.util.concurrent.CompletionStage<U> thenComposeAsync(Function<? super T, ? extends java.util.concurrent.CompletionStage<U>> fn) { return cf.thenComposeAsync(fn); }
            @Override public <U> java.util.concurrent.CompletionStage<U> thenComposeAsync(Function<? super T, ? extends java.util.concurrent.CompletionStage<U>> fn, java.util.concurrent.Executor executor) { return cf.thenComposeAsync(fn, executor); }
            @Override public <U> java.util.concurrent.CompletionStage<U> handle(BiFunction<? super T, Throwable, ? extends U> fn) { return cf.handle(fn); }
            @Override public <U> java.util.concurrent.CompletionStage<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> fn) { return cf.handleAsync(fn); }
            @Override public <U> java.util.concurrent.CompletionStage<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> fn, java.util.concurrent.Executor executor) { return cf.handleAsync(fn, executor); }
            @Override public java.util.concurrent.CompletionStage<T> whenComplete(BiConsumer<? super T, ? super Throwable> action) { return cf.whenComplete(action); }
            @Override public java.util.concurrent.CompletionStage<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action) { return cf.whenCompleteAsync(action); }
            @Override public java.util.concurrent.CompletionStage<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action, java.util.concurrent.Executor executor) { return cf.whenCompleteAsync(action, executor); }
            @Override public java.util.concurrent.CompletionStage<T> exceptionally(Function<Throwable, ? extends T> fn) { return cf.exceptionally(fn); }
        };
    }

    @Test
    void saveSnapshot_serializesAndSets() {
        when(async.set(anyString(), any(byte[].class))).thenReturn(completedFuture("OK"));

        store.saveSnapshot("actor-1", "state-42", 42L).join();

        verify(async).set(
                argThat(key -> key.contains("snapshot") && key.contains("actor-1")),
                argThat(bytes -> bytes != null && bytes.length > 0));
    }

    @Test
    void getLatestSnapshot_returnsEmptyWhenAbsent() {
        when(async.get(anyString())).thenReturn(completedFuture(null));

        Optional<SnapshotEntry<String>> result = store.getLatestSnapshot("actor-1").join();

        assertTrue(result.isEmpty());
    }

    @Test
    void getLatestSnapshot_deserializesEntry() {
        SnapshotEntry<String> original = new SnapshotEntry<>("actor-1", "state-42", 42L);
        byte[] serialized = JavaSerializationProvider.INSTANCE.serialize(original);
        when(async.get(anyString())).thenReturn(completedFuture(serialized));

        Optional<SnapshotEntry<String>> result = store.getLatestSnapshot("actor-1").join();

        assertTrue(result.isPresent());
        assertEquals(42L, result.get().getSequenceNumber());
        assertEquals("state-42", result.get().getState());
        assertEquals("actor-1", result.get().getActorId());
    }

    @Test
    void deleteSnapshots_callsDel() {
        // del(K...) — when called with one String, Mockito sees a single String arg
        when(async.del(anyString())).thenReturn(completedFuture(1L));

        store.deleteSnapshots("actor-1").join();

        // Verify del was called with the correct snapshot key (single String arg)
        verify(async).del(argThat((String key) -> key.contains("snapshot") && key.contains("actor-1")));
    }
}
