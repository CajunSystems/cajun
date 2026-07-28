package com.cajunsystems.persistence.redis;

import com.cajunsystems.persistence.JournalEntry;
import com.cajunsystems.persistence.MessageJournal;
import com.cajunsystems.serialization.SerializationProvider;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Redis-based message journal for distributed actor persistence.
 *
 * <p>Key design:
 * <ul>
 *   <li>Journal hash key: {@code {prefix}:journal:{sanitized(actorId)}}</li>
 *   <li>Sequence counter key: {@code {prefix}:journal:{sanitized(actorId)}:seq}</li>
 *   <li>Hash field = sequence number (decimal string), value = serialized message bytes</li>
 * </ul>
 *
 * <p>Atomic append uses a Lua script to INCR the counter and HSET in one round-trip.
 *
 * @param <M> The type of messages stored
 * @since 0.5.0
 */
public class RedisMessageJournal<M> implements MessageJournal<M> {

    private static final String APPEND_SCRIPT =
            "local seq = redis.call('INCR', KEYS[2])\n" +
            "redis.call('HSET', KEYS[1], tostring(seq), ARGV[1])\n" +
            "return seq";

    private final StatefulRedisConnection<String, byte[]> connection;
    private final String keyPrefix;
    private final SerializationProvider serializer;

    /**
     * Creates a new RedisMessageJournal.
     *
     * @param connection the Lettuce connection (string keys, byte-array values)
     * @param keyPrefix  the key prefix (e.g. "cajun")
     * @param serializer the serialization provider
     */
    public RedisMessageJournal(StatefulRedisConnection<String, byte[]> connection,
                               String keyPrefix,
                               SerializationProvider serializer) {
        this.connection = connection;
        this.keyPrefix = keyPrefix;
        this.serializer = serializer;
    }

    // -------------------------------------------------------------------------
    // MessageJournal API
    // -------------------------------------------------------------------------

    @Override
    public CompletableFuture<Long> append(String actorId, M message) {
        String sanitized = sanitize(actorId);
        String journalKey = journalKey(sanitized);
        String seqKey = seqKey(sanitized);

        byte[] msgBytes = serializer.serialize(message);

        RedisAsyncCommands<String, byte[]> async = connection.async();

        @SuppressWarnings("unchecked")
        RedisFuture<Long> future = async.eval(
                APPEND_SCRIPT,
                ScriptOutputType.INTEGER,
                new String[]{journalKey, seqKey},
                msgBytes);

        return future.toCompletableFuture();
    }

    @Override
    public CompletableFuture<List<JournalEntry<M>>> readFrom(String actorId, long fromSequenceNumber) {
        String sanitized = sanitize(actorId);
        String journalKey = journalKey(sanitized);

        RedisAsyncCommands<String, byte[]> async = connection.async();

        return async.hgetall(journalKey)
                .toCompletableFuture()
                .thenApply(map -> {
                    if (map == null || map.isEmpty()) {
                        return List.<JournalEntry<M>>of();
                    }

                    List<Map.Entry<String, byte[]>> filtered = new ArrayList<>();
                    for (Map.Entry<String, byte[]> entry : map.entrySet()) {
                        long seq = Long.parseLong(entry.getKey());
                        if (seq >= fromSequenceNumber) {
                            filtered.add(entry);
                        }
                    }

                    filtered.sort((a, b) ->
                            Long.compare(Long.parseLong(a.getKey()), Long.parseLong(b.getKey())));

                    List<JournalEntry<M>> result = new ArrayList<>(filtered.size());
                    for (Map.Entry<String, byte[]> entry : filtered) {
                        long seq = Long.parseLong(entry.getKey());
                        @SuppressWarnings("unchecked")
                        M msg = (M) serializer.deserialize(entry.getValue(), Object.class);
                        result.add(new JournalEntry<>(seq, actorId, msg));
                    }
                    return result;
                });
    }

    @Override
    public CompletableFuture<Void> truncateBefore(String actorId, long upToSequenceNumber) {
        String sanitized = sanitize(actorId);
        String journalKey = journalKey(sanitized);

        RedisAsyncCommands<String, byte[]> async = connection.async();

        return async.hkeys(journalKey)
                .toCompletableFuture()
                .thenCompose(fields -> {
                    if (fields == null || fields.isEmpty()) {
                        return CompletableFuture.completedFuture(null);
                    }

                    List<String> toDelete = new ArrayList<>();
                    for (String field : fields) {
                        long seq = Long.parseLong(field);
                        if (seq < upToSequenceNumber) {
                            toDelete.add(field);
                        }
                    }

                    if (toDelete.isEmpty()) {
                        return CompletableFuture.completedFuture(null);
                    }

                    String[] fieldArray = toDelete.toArray(new String[0]);
                    return async.hdel(journalKey, fieldArray)
                            .toCompletableFuture()
                            .thenApply(n -> (Void) null);
                });
    }

    @Override
    public CompletableFuture<Long> getHighestSequenceNumber(String actorId) {
        String sanitized = sanitize(actorId);
        String seqKey = seqKey(sanitized);

        RedisAsyncCommands<String, byte[]> async = connection.async();

        return async.get(seqKey)
                .toCompletableFuture()
                .thenApply(val -> {
                    if (val == null) {
                        return -1L;
                    }
                    return Long.parseLong(new String(val, StandardCharsets.UTF_8));
                });
    }

    @Override
    public void close() {
        // Connection lifecycle is managed by RedisPersistenceProvider
    }

    // -------------------------------------------------------------------------
    // Key helpers
    // -------------------------------------------------------------------------

    private String journalKey(String sanitizedActorId) {
        return keyPrefix + ":journal:{" + sanitizedActorId + "}";
    }

    private String seqKey(String sanitizedActorId) {
        return keyPrefix + ":journal:{" + sanitizedActorId + "}:seq";
    }

    static String sanitize(String actorId) {
        return actorId.replace(':', '_');
    }
}
