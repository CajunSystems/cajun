package com.cajunsystems.serialization;

/**
 * Pluggable serialization strategy for inter-node messages and persistence journals.
 *
 * <p>Implementations must be thread-safe. A single provider instance may be shared
 * across many actors and threads simultaneously.
 */
public interface SerializationProvider {

    /**
     * Serializes an object to bytes.
     *
     * @param object the object to serialize (must not be null)
     * @return the serialized byte array
     * @throws SerializationException if serialization fails
     */
    byte[] serialize(Object object);

    /**
     * Deserializes bytes back to an object of the given type.
     *
     * @param bytes the serialized bytes
     * @param type  the expected type of the deserialized object
     * @param <T>   the type parameter
     * @return the deserialized object
     * @throws SerializationException if deserialization fails
     */
    <T> T deserialize(byte[] bytes, Class<T> type);

    /**
     * Returns the name of this provider (for logging and configuration).
     */
    String name();
}
