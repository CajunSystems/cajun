package com.cajunsystems.builder;

import com.cajunsystems.ActorSystem;

import java.util.UUID;
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
     * Class name + sequential counter.
     * Counter is per-class globally (survives across different ActorSystem instances).
     * <p>
     * Examples: {@code "user:1"}, {@code "user:2"}, {@code "order:1"}, {@code "order:2"}
     */
    IdStrategy CLASS_BASED_SEQUENTIAL = new IdStrategy() {
        private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();

        @Override
        public String generateId(IdGenerationContext ctx) {
            String baseName = extractBaseName(ctx.handlerClass());
            long seq = counters.computeIfAbsent(baseName, k -> new AtomicLong(0))
                              .incrementAndGet();
            return baseName + ":" + seq;
        }

        /**
         * Get current counter value for a class (for testing/debugging).
         */
        public long getCurrentCounter(String baseName) {
            AtomicLong counter = counters.get(baseName);
            return counter != null ? counter.get() : 0;
        }

        /**
         * Set counter value for a class (for counter recovery).
         */
        public void setCounter(String baseName, long value) {
            counters.computeIfAbsent(baseName, k -> new AtomicLong(0)).set(value);
        }

        /**
         * Update counter to max of current and provided value.
         */
        public void updateCounter(String baseName, long value) {
            AtomicLong counter = counters.computeIfAbsent(baseName, k -> new AtomicLong(0));
            long current;
            do {
                current = counter.get();
                if (value <= current) {
                    return; // Current is already higher
                }
            } while (!counter.compareAndSet(current, value));
        }
    };

    /**
     * Class name + UUID for uniqueness.
     * <p>
     * Examples: {@code "user:a1b2c3d4-..."}, {@code "order:b2c3d4e5-..."}
     */
    IdStrategy CLASS_BASED_UUID = ctx -> {
        String baseName = extractBaseName(ctx.handlerClass());
        return baseName + ":" + java.util.UUID.randomUUID().toString();
    };

    /**
     * Class name + short UUID (8 chars).
     * <p>
     * Examples: {@code "user:a1b2c3d4"}, {@code "order:b2c3d4e5"}
     */
    IdStrategy CLASS_BASED_SHORT_UUID = ctx -> {
        String baseName = extractBaseName(ctx.handlerClass());
        String shortId = java.util.UUID.randomUUID().toString().substring(0, 8);
        return baseName + ":" + shortId;
    };

    /**
     * Class name + timestamp (milliseconds).
     * <p>
     * Example: {@code "user:1701234567890"}
     */
    IdStrategy CLASS_BASED_TIMESTAMP = ctx -> {
        String baseName = extractBaseName(ctx.handlerClass());
        return baseName + ":" + System.currentTimeMillis();
    };

    /**
     * Class name + nanosecond timestamp (for high-frequency creation).
     * <p>
     * Example: {@code "user:1701234567890123456"}
     */
    IdStrategy CLASS_BASED_NANO = ctx -> {
        String baseName = extractBaseName(ctx.handlerClass());
        return baseName + ":" + System.nanoTime();
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
