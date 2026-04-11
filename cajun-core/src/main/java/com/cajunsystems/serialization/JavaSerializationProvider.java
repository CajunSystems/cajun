package com.cajunsystems.serialization;

import java.io.*;

/**
 * Serialization provider using Java's built-in object serialization.
 * Objects must implement {@link java.io.Serializable}.
 *
 * <p>This is the backward-compatible fallback. Prefer {@code KryoSerializationProvider}
 * for new deployments.
 */
public class JavaSerializationProvider implements SerializationProvider {

    public static final JavaSerializationProvider INSTANCE = new JavaSerializationProvider();

    @Override
    public byte[] serialize(Object object) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(object);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new SerializationException(
                "Java serialization failed for " + object.getClass().getName(), e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T deserialize(byte[] bytes, Class<T> type) {
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return (T) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new SerializationException(
                "Java deserialization failed for type " + type.getName(), e);
        }
    }

    @Override
    public String name() { return "java"; }
}
