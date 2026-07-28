package com.cajunsystems.persistence.redis;

import com.cajunsystems.persistence.SnapshotEntry;
import com.cajunsystems.persistence.SnapshotStore;
import com.cajunsystems.serialization.SerializationProvider;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Redis-based snapshot store for actor state persistence.
 *
 * <p>Key design:
 * <ul>
 *   <li>Snapshot key: {@code {prefix}:snapshot:{sanitized(actorId)}}</li>
 *   <li>Value: serialized {@link SnapshotEntry} bytes (includes actorId and sequenceNumber)</li>
 * </ul>
 *
 * <p>Only the latest snapshot is stored per actor (overwrite semantics).
 *
 * @param <S> The type of state stored
 * @since 0.5.0
 */
public class RedisSnapshotStore<S> implements SnapshotStore<S> {

    private final StatefulRedisConnection<String, byte[]> connection;
    private final String keyPrefix;
    private final SerializationProvider serializer;

    /**
     * Creates a new RedisSnapshotStore.
     *
     * @param connection the Lettuce connection (string keys, byte-array values)
     * @param keyPrefix  the key prefix (e.g. "cajun")
     * @param serializer the serialization provider
     */
    public RedisSnapshotStore(StatefulRedisConnection<String, byte[]> connection,
                              String keyPrefix,
                              SerializationProvider serializer) {
        this.connection = connection;
        this.keyPrefix = keyPrefix;
        this.serializer = serializer;
    }

    // -------------------------------------------------------------------------
    // SnapshotStore API
    // -------------------------------------------------------------------------

    @Override
    public CompletableFuture<Void> saveSnapshot(String actorId, S state, long sequenceNumber) {
        String snapshotKey = snapshotKey(actorId);
        SnapshotEntry<S> entry = new SnapshotEntry<>(actorId, state, sequenceNumber);
        byte[] bytes = serializer.serialize(entry);

        RedisAsyncCommands<String, byte[]> async = connection.async();
        return async.set(snapshotKey, bytes)
                .toCompletableFuture()
                .thenApply(r -> (Void) null);
    }

    @Override
    public CompletableFuture<Optional<SnapshotEntry<S>>> getLatestSnapshot(String actorId) {
        String snapshotKey = snapshotKey(actorId);

        RedisAsyncCommands<String, byte[]> async = connection.async();
        return async.get(snapshotKey)
                .toCompletableFuture()
                .thenApply(bytes -> {
                    if (bytes == null) {
                        return Optional.<SnapshotEntry<S>>empty();
                    }
                    @SuppressWarnings("unchecked")
                    SnapshotEntry<S> entry = (SnapshotEntry<S>) serializer.deserialize(bytes, SnapshotEntry.class);
                    return Optional.of(entry);
                });
    }

    @Override
    public CompletableFuture<Void> deleteSnapshots(String actorId) {
        String snapshotKey = snapshotKey(actorId);

        RedisAsyncCommands<String, byte[]> async = connection.async();
        return async.del(snapshotKey)
                .toCompletableFuture()
                .thenApply(n -> (Void) null);
    }

    @Override
    public void close() {
        // Connection lifecycle is managed by RedisPersistenceProvider
    }

    @Override
    public boolean isHealthy() {
        try {
            String pong = connection.sync().ping();
            return "PONG".equalsIgnoreCase(pong);
        } catch (Exception e) {
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Key helpers
    // -------------------------------------------------------------------------

    private String snapshotKey(String actorId) {
        String sanitized = RedisMessageJournal.sanitize(actorId);
        return keyPrefix + ":snapshot:{" + sanitized + "}";
    }
}
