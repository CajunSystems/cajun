package com.cajunsystems.serialization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

/**
 * Human-readable serialization using Jackson JSON with full type information.
 *
 * <p>Includes full type information so polymorphic types (sealed interfaces, etc.)
 * round-trip correctly. Best for debugging and cross-language interop;
 * prefer {@code KryoSerializationProvider} for performance-critical paths.
 */
public class JsonSerializationProvider implements SerializationProvider {

    public static final JsonSerializationProvider INSTANCE = new JsonSerializationProvider();

    private final ObjectMapper mapper;

    public JsonSerializationProvider() {
        this.mapper = JsonMapper.builder()
            .activateDefaultTyping(
                BasicPolymorphicTypeValidator.builder()
                    .allowIfBaseType(Object.class)
                    .build(),
                ObjectMapper.DefaultTyping.EVERYTHING
            )
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .build();
    }

    @Override
    public byte[] serialize(Object object) {
        try {
            return mapper.writeValueAsBytes(object);
        } catch (Exception e) {
            throw new SerializationException(
                "JSON serialization failed for " + object.getClass().getName(), e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T deserialize(byte[] bytes, Class<T> type) {
        try {
            return mapper.readValue(bytes, type);
        } catch (Exception e) {
            throw new SerializationException(
                "JSON deserialization failed for type " + type.getName(), e);
        }
    }

    @Override
    public String name() { return "json"; }
}
