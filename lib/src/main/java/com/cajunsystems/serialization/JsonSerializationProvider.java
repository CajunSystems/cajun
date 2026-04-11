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
 *
 * <p><strong>Security note</strong>: polymorphic deserialization is restricted to
 * explicitly trusted package prefixes. The default {@link #INSTANCE} trusts only
 * {@code com.cajunsystems.*}, {@code java.*}, and {@code javax.*}. If your actor
 * messages live in other packages, create a custom instance:
 * <pre>
 *   new JsonSerializationProvider("com.cajunsystems.", "com.example.myapp.")
 * </pre>
 */
public class JsonSerializationProvider implements SerializationProvider {

    /**
     * Default instance — trusts {@code com.cajunsystems.*}, {@code java.*},
     * and {@code javax.*} package prefixes only.
     */
    public static final JsonSerializationProvider INSTANCE =
            new JsonSerializationProvider("com.cajunsystems.", "java.", "javax.");

    private final ObjectMapper mapper;

    /**
     * Creates a provider that restricts polymorphic deserialization to the given
     * package prefixes. Always includes {@code java.*} and {@code javax.*}.
     *
     * @param trustedPackagePrefixes package prefixes whose subtypes may be instantiated
     *                               during deserialization (e.g. {@code "com.example."})
     */
    public JsonSerializationProvider(String... trustedPackagePrefixes) {
        BasicPolymorphicTypeValidator.Builder pvtBuilder =
                BasicPolymorphicTypeValidator.builder();
        for (String pkg : trustedPackagePrefixes) {
            pvtBuilder.allowIfSubType(pkg);
        }
        this.mapper = JsonMapper.builder()
                .activateDefaultTyping(
                        pvtBuilder.build(),
                        ObjectMapper.DefaultTyping.NON_FINAL)
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
