package com.cajunsystems.serialization;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

import java.io.ByteArrayOutputStream;

/**
 * High-performance serialization using Kryo.
 *
 * <p>Does not require types to implement {@link java.io.Serializable} or have
 * no-arg constructors (Kryo 5 uses Objenesis for instantiation).
 *
 * <p>Kryo instances are not thread-safe; this provider uses a per-thread instance.
 */
public class KryoSerializationProvider implements SerializationProvider {

    public static final KryoSerializationProvider INSTANCE = new KryoSerializationProvider();

    private final ThreadLocal<Kryo> kryoLocal = ThreadLocal.withInitial(() -> {
        Kryo kryo = new Kryo();
        kryo.setRegistrationRequired(false);
        kryo.setReferences(true);
        return kryo;
    });

    @Override
    public byte[] serialize(Object object) {
        try {
            Kryo kryo = kryoLocal.get();
            ByteArrayOutputStream bos = new ByteArrayOutputStream(256);
            try (Output output = new Output(bos)) {
                kryo.writeClassAndObject(output, object);
            }
            return bos.toByteArray();
        } catch (Exception e) {
            throw new SerializationException(
                "Kryo serialization failed for " + object.getClass().getName(), e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T deserialize(byte[] bytes, Class<T> type) {
        try {
            Kryo kryo = kryoLocal.get();
            try (Input input = new Input(bytes)) {
                return (T) kryo.readClassAndObject(input);
            }
        } catch (Exception e) {
            throw new SerializationException(
                "Kryo deserialization failed for type " + type.getName(), e);
        }
    }

    @Override
    public String name() { return "kryo"; }
}
