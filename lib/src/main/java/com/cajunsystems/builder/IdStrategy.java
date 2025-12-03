package com.cajunsystems.builder;

import com.cajunsystems.ActorSystem;

import static java.util.UUID.randomUUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Strategy for generating actor IDs.
 * Provides built-in strategies and allows custom implementations.
 * <p>
 * Built-in strategies include:
 * <ul>
 *   <li>{@link #UUID} - Random UUID-based IDs</li>
 *   <li>{@link #CLASS_BASED_SEQUENTIAL} - Class name + sequential counter</li>
 *   <li>{@link #CLASS_BASED_UUID} - Class name + UUID</li>
 *   <li>{@link #CLASS_BASED_SHORT_UUID} - Class name + short UUID (8 chars)</li>
 *   <li>{@link #CLASS_BASED_TIMESTAMP} - Class name + timestamp</li>
 *   <li>{@link #CLASS_BASED_NANO} - Class name + nanosecond timestamp</li>
 *   <li>{@link #SEQUENTIAL} - Global sequential counter</li>
 * </ul>
 */
@FunctionalInterface
public interface IdStrategy {

    /**
     * Generate an ID for an actor.
     * <p>
     * Note: The generated ID should NOT include hierarchical prefixes from parent actors.
     * The builder will automatically prepend the parent's ID if a parent is set.
     *
     * @param context The context containing system, handler class, and parent info
     * @return The generated ID (without hierarchical prefix)
     */
    String generateId(IdGenerationContext context);

    /**
     * Context passed to ID generation strategies.
     *
     * @param system The actor system
     * @param handlerClass The handler class being instantiated
     * @param parentId The parent actor ID, or null if no parent
     */
    record IdGenerationContext(
        ActorSystem system,
        Class<?> handlerClass,
        String parentId
    ) {}

    // ========================================
    // Built-in Strategies
    // ========================================

    /**
     * UUID-based IDs (default legacy behavior).
     * <p>
     * Example: {@code "550e8400-e29b-41d4-a716-446655440000"}
     */
    IdStrategy UUID = ctx -> java.util.UUID.randomUUID().toString();

    /**
     * Class-based sequential strategy: {@code {class}:{seq}}
     * <p>
     * Combines the handler class name with an auto-incrementing counter.
     * Each class has its own counter starting from 1.
     * <p>
     * Examples: {@code "user:1"}, {@code "user:2"}, {@code "order:1"}, {@code "order:2"}
     * <p>
     * Note: This strategy uses the same counter map as IdTemplateProcessor to enable
     * counter recovery from persisted actors.
     */
    IdStrategy CLASS_BASED_SEQUENTIAL = new IdStrategy() {
        @Override
        public String generateId(IdGenerationContext ctx) {
            // Ensure counters are initialized from persisted state
            IdTemplateProcessor.ensureCountersInitializedForSystem(ctx.system());
            
            String baseName = extractBaseName(ctx.handlerClass());
            long seq = IdTemplateProcessor.getOrCreateClassCounter(baseName).incrementAndGet();
            return STR."\{baseName}:\{seq}";
        }
    };

    /**
     * Class name + UUID for uniqueness.
     * <p>
     * Examples: {@code "user:a1b2c3d4-..."}, {@code "order:b2c3d4e5-..."}
     */
    IdStrategy CLASS_BASED_UUID = ctx -> {
        String baseName = extractBaseName(ctx.handlerClass());
        return STR."\{baseName}:\{randomUUID()}";
    };

    /**
     * Class name + short UUID (8 chars).
     * <p>
     * Examples: {@code "user:a1b2c3d4"}, {@code "order:b2c3d4e5"}
     */
    IdStrategy CLASS_BASED_SHORT_UUID = ctx -> {
        String baseName = extractBaseName(ctx.handlerClass());
        String shortId = randomUUID().toString().substring(0, 8);
        return STR."\{baseName}:\{shortId}";
    };

    /**
     * Class name + timestamp (milliseconds).
     * <p>
     * Example: {@code "user:1701234567890"}
     */
    IdStrategy CLASS_BASED_TIMESTAMP = ctx -> {
        String baseName = extractBaseName(ctx.handlerClass());
        return STR."\{baseName}:\{System.currentTimeMillis()}";
    };

    /**
     * Class name + nanosecond timestamp (for high-frequency creation).
     * <p>
     * Example: {@code "user:1701234567890123456"}
     */
    IdStrategy CLASS_BASED_NANO = ctx -> {
        String baseName = extractBaseName(ctx.handlerClass());
        return STR."\{baseName}:\{System.nanoTime()}";
    };

    /**
     * Simple sequential counter (global across all actor types).
     * <p>
     * Examples: {@code "1"}, {@code "2"}, {@code "3"}
     */
    IdStrategy SEQUENTIAL = new IdStrategy() {
        private final AtomicLong counter = new AtomicLong(0);

        @Override
        public String generateId(IdGenerationContext ctx) {
            return String.valueOf(counter.incrementAndGet());
        }
    };

    // ========================================
    // Helper Methods
    // ========================================

    /**
     * Extract base name from handler class.
     * Strips "Handler" suffix if present and converts to lowercase.
     * <p>
     * Examples:
     * <ul>
     *   <li>{@code UserHandler -> "user"}</li>
     *   <li>{@code OrderProcessingHandler -> "orderprocessing"}</li>
     *   <li>{@code User -> "user"}</li>
     * </ul>
     *
     * @param handlerClass The handler class
     * @return The extracted base name
     */
    static String extractBaseName(Class<?> handlerClass) {
        String name = handlerClass.getSimpleName();

        // Strip "Handler" suffix if present
        if (name.endsWith("Handler")) {
            name = name.substring(0, name.length() - 7);
        }

        // Convert to lowercase
        return name.toLowerCase();
    }
}
