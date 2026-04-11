package com.cajunsystems.persistence.redis;

import com.cajunsystems.persistence.BatchedMessageJournal;
import com.cajunsystems.persistence.MessageJournal;
import com.cajunsystems.persistence.PersistenceProvider;
import com.cajunsystems.persistence.SnapshotStore;
import com.cajunsystems.persistence.lmdb.SimpleBatchedMessageJournal;
import com.cajunsystems.serialization.JavaSerializationProvider;
import com.cajunsystems.serialization.SerializationProvider;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;

/**
 * Redis-based persistence provider for the Cajun actor system.
 *
 * <p>Uses Lettuce for non-blocking Redis access. A single {@link StatefulRedisConnection}
 * is shared across all journals and snapshot stores created by this provider (thread-safe).
 *
 * <p>Defaults to {@link JavaSerializationProvider} to avoid pulling Kryo into the
 * {@code cajun-persistence} module. Tests in {@code lib} pass
 * {@code KryoSerializationProvider.INSTANCE} explicitly.
 *
 * @since 0.5.0
 */
public class RedisPersistenceProvider implements PersistenceProvider {

    private static final String DEFAULT_KEY_PREFIX = "cajun";
    private static final int DEFAULT_MAX_BATCH_SIZE = 100;
    private static final long DEFAULT_MAX_BATCH_DELAY_MS = 50L;

    private final RedisClient client;
    private final StatefulRedisConnection<String, byte[]> connection;
    private final String keyPrefix;
    private final SerializationProvider serializer;

    /**
     * Creates a provider with default key prefix ({@code "cajun"}) and Java serialization.
     *
     * @param redisUri Lettuce URI, e.g. {@code "redis://localhost:6379"}
     */
    public RedisPersistenceProvider(String redisUri) {
        this(redisUri, DEFAULT_KEY_PREFIX, JavaSerializationProvider.INSTANCE);
    }

    /**
     * Creates a provider with the given key prefix and Java serialization.
     *
     * @param redisUri  Lettuce URI
     * @param keyPrefix prefix applied to all Redis keys
     */
    public RedisPersistenceProvider(String redisUri, String keyPrefix) {
        this(redisUri, keyPrefix, JavaSerializationProvider.INSTANCE);
    }

    /**
     * Creates a provider with full control over key prefix and serialization.
     *
     * @param redisUri   Lettuce URI
     * @param keyPrefix  prefix applied to all Redis keys
     * @param serializer the serialization provider for messages and snapshots
     */
    public RedisPersistenceProvider(String redisUri, String keyPrefix, SerializationProvider serializer) {
        this.keyPrefix = keyPrefix;
        this.serializer = serializer;
        this.client = RedisClient.create(redisUri);
        this.connection = client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
    }

    // -------------------------------------------------------------------------
    // PersistenceProvider API — journal factory methods
    // -------------------------------------------------------------------------

    @Override
    public <M> MessageJournal<M> createMessageJournal() {
        throw new UnsupportedOperationException(
                "Redis journal requires actorId — use createMessageJournal(String actorId)");
    }

    @Override
    public <M> MessageJournal<M> createMessageJournal(String actorId) {
        return new RedisMessageJournal<>(connection, keyPrefix, serializer);
    }

    @Override
    public <M> BatchedMessageJournal<M> createBatchedMessageJournal() {
        throw new UnsupportedOperationException(
                "Redis batched journal requires actorId — use createBatchedMessageJournal(String actorId)");
    }

    @Override
    public <M> BatchedMessageJournal<M> createBatchedMessageJournal(String actorId) {
        return createBatchedMessageJournal(actorId, DEFAULT_MAX_BATCH_SIZE, DEFAULT_MAX_BATCH_DELAY_MS);
    }

    @Override
    public <M> BatchedMessageJournal<M> createBatchedMessageJournal(
            String actorId, int maxBatchSize, long maxBatchDelayMs) {
        MessageJournal<M> base = createMessageJournal(actorId);
        return new SimpleBatchedMessageJournal<>(base, maxBatchSize, maxBatchDelayMs);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <M extends java.io.Serializable> BatchedMessageJournal<M> createBatchedMessageJournalSerializable(
            String actorId, int maxBatchSize, long maxBatchDelayMs) {
        // createMessageJournal returns MessageJournal<Object> at runtime due to erasure;
        // the raw cast is safe because RedisMessageJournal uses the serializer's type-erased API.
        MessageJournal<M> base = (MessageJournal<M>) (MessageJournal) createMessageJournal(actorId);
        return new SimpleBatchedMessageJournal<>(base, maxBatchSize, maxBatchDelayMs);
    }

    // -------------------------------------------------------------------------
    // PersistenceProvider API — snapshot factory methods
    // -------------------------------------------------------------------------

    @Override
    public <S> SnapshotStore<S> createSnapshotStore() {
        throw new UnsupportedOperationException(
                "Redis snapshot store requires actorId — use createSnapshotStore(String actorId)");
    }

    @Override
    public <S> SnapshotStore<S> createSnapshotStore(String actorId) {
        return new RedisSnapshotStore<>(connection, keyPrefix, serializer);
    }

    // -------------------------------------------------------------------------
    // PersistenceProvider API — metadata / health
    // -------------------------------------------------------------------------

    @Override
    public String getProviderName() {
        return "redis";
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

    /**
     * Closes the Redis connection and shuts down the client.
     * Call this during application shutdown.
     */
    public void close() {
        connection.close();
        client.shutdown();
    }
}
