package com.cajunsystems.persistence.redis;

import com.cajunsystems.persistence.JournalEntry;
import com.cajunsystems.serialization.JavaSerializationProvider;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
class RedisMessageJournalTest {

    @Mock
    private StatefulRedisConnection<String, byte[]> connection;

    @Mock
    private RedisAsyncCommands<String, byte[]> async;

    private RedisMessageJournal<String> journal;

    @BeforeEach
    void setUp() {
        when(connection.async()).thenReturn(async);
        journal = new RedisMessageJournal<>(connection, "cajun", JavaSerializationProvider.INSTANCE);
    }

    /**
     * Creates a RedisFuture backed by a completed CompletableFuture.
     * Avoids mocking default interface methods (toCompletableFuture) which causes
     * Mockito strict stubbing issues.
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
    @SuppressWarnings({"unchecked", "rawtypes"})
    void append_callsLuaScriptWithCorrectKeysAndReturnsFuture() {
        // eval returns RedisFuture<Object> at runtime due to type erasure
        RedisFuture objectFuture = completedFuture(1L);
        when(async.eval(anyString(), eq(ScriptOutputType.INTEGER), any(String[].class), any(byte[].class)))
                .thenReturn(objectFuture);

        CompletableFuture<Long> result = journal.append("actor-1", "msg-1");

        assertNotNull(result);
        assertEquals(1L, result.join());
        verify(async).eval(anyString(), eq(ScriptOutputType.INTEGER), any(String[].class), any(byte[].class));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void append_sanitizesColonsInActorId() {
        RedisFuture objectFuture = completedFuture(1L);

        when(async.eval(anyString(), eq(ScriptOutputType.INTEGER), any(String[].class), any(byte[].class)))
                .thenReturn(objectFuture);

        journal.append("actor:sub", "test-msg");

        // Verify the call was made with sanitized keys (colon replaced with underscore)
        verify(async).eval(anyString(), eq(ScriptOutputType.INTEGER),
                argThat((String[] keys) -> keys != null && keys.length == 2
                        && keys[0].contains("actor_sub")
                        && !keys[0].contains("actor:sub")),
                any(byte[].class));
    }

    @Test
    void readFrom_filtersAndSortsEntriesFromSeq() {
        Map<String, byte[]> map = new HashMap<>();
        for (int i = 1; i <= 5; i++) {
            map.put(String.valueOf(i), JavaSerializationProvider.INSTANCE.serialize("msg-" + i));
        }
        when(async.hgetall(anyString())).thenReturn(completedFuture(map));

        List<JournalEntry<String>> entries = journal.readFrom("actor", 3).join();

        assertEquals(3, entries.size());
        assertEquals(3L, entries.get(0).getSequenceNumber());
        assertEquals(4L, entries.get(1).getSequenceNumber());
        assertEquals(5L, entries.get(2).getSequenceNumber());
        assertEquals("msg-3", entries.get(0).getMessage());
        assertEquals("msg-4", entries.get(1).getMessage());
        assertEquals("msg-5", entries.get(2).getMessage());
    }

    @Test
    void readFrom_returnsEmptyListWhenAllEntriesFiltered() {
        Map<String, byte[]> map = new HashMap<>();
        map.put("1", JavaSerializationProvider.INSTANCE.serialize("msg-1"));
        map.put("2", JavaSerializationProvider.INSTANCE.serialize("msg-2"));
        when(async.hgetall(anyString())).thenReturn(completedFuture(map));

        List<JournalEntry<String>> entries = journal.readFrom("actor", 5).join();

        assertTrue(entries.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void truncateBefore_deletesFieldsBelowThreshold() {
        List<String> fields = List.of("1", "2", "3", "4", "5");
        when(async.hkeys(anyString())).thenReturn(completedFuture(fields));
        // hdel(K key, K... fields) - use doReturn to handle varargs correctly
        doReturn(completedFuture(2L)).when(async).hdel(anyString(), any(String[].class));

        journal.truncateBefore("actor", 3).join();

        // Capture what was actually deleted and assert the correct fields
        org.mockito.ArgumentCaptor<String[]> captor = org.mockito.ArgumentCaptor.forClass(String[].class);
        verify(async).hdel(anyString(), captor.capture());
        Set<String> deletedFields = new HashSet<>(Arrays.asList(captor.getValue()));
        assertEquals(2, deletedFields.size());
        assertTrue(deletedFields.contains("1"));
        assertTrue(deletedFields.contains("2"));
        assertFalse(deletedFields.contains("3"));
        assertFalse(deletedFields.contains("4"));
        assertFalse(deletedFields.contains("5"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void truncateBefore_noOpWhenNoFieldsBelowThreshold() {
        List<String> fields = List.of("3", "4", "5");
        when(async.hkeys(anyString())).thenReturn(completedFuture(fields));

        journal.truncateBefore("actor", 3).join();

        // Verify hdel was never invoked
        verify(async, never()).hdel(anyString(), any(String[].class));
    }

    @Test
    void getHighestSequenceNumber_returnsMinusOneWhenKeyAbsent() {
        when(async.get(anyString())).thenReturn(completedFuture(null));

        long seq = journal.getHighestSequenceNumber("actor").join();

        assertEquals(-1L, seq);
    }

    @Test
    void getHighestSequenceNumber_returnsParsedValue() {
        byte[] valueBytes = "42".getBytes(StandardCharsets.UTF_8);
        when(async.get(anyString())).thenReturn(completedFuture(valueBytes));

        long seq = journal.getHighestSequenceNumber("actor").join();

        assertEquals(42L, seq);
    }
}
