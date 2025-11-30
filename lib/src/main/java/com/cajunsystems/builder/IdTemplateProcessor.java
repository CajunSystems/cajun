package com.cajunsystems.builder;

import com.cajunsystems.ActorSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Processes ID templates with placeholder substitution.
 * <p>
 * Supported placeholders:
 * <ul>
 *   <li>{@code {seq}} - Sequential counter per handler class</li>
 *   <li>{@code {uuid}} - Random UUID</li>
 *   <li>{@code {short-uuid}} - First 8 characters of UUID</li>
 *   <li>{@code {timestamp}} - Current timestamp in milliseconds</li>
 *   <li>{@code {nano}} - Current nanosecond time</li>
 *   <li>{@code {class}} - Handler class base name (lowercase, "Handler" suffix stripped)</li>
 *   <li>{@code {parent}} - Parent actor ID (empty string if no parent)</li>
 *   <li>{@code {template-seq}} - Sequential counter per unique template string</li>
 * </ul>
 * <p>
 * Examples:
 * <ul>
 *   <li>{@code "user:{seq}"} → {@code "user:1"}, {@code "user:2"}, etc.</li>
 *   <li>{@code "{class}:{seq}"} → {@code "user:1"}, {@code "order:1"}, etc.</li>
 *   <li>{@code "session:{timestamp}:{seq}"} → {@code "session:1701234567890:1"}</li>
 * </ul>
 */
public class IdTemplateProcessor {

    private static final Logger logger = LoggerFactory.getLogger(IdTemplateProcessor.class);

    private final ActorSystem system;
    private final Class<?> handlerClass;
    private final String parentId;

    // Counters for template-based sequential IDs
    private static final ConcurrentHashMap<String, AtomicLong> classCounters =
        new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicLong> templateCounters =
        new ConcurrentHashMap<>();

    // Flag to track if counters have been initialized from persistence
    private static volatile boolean countersInitialized = false;
    private static final Object initLock = new Object();

    /**
     * Creates a new IdTemplateProcessor.
     *
     * @param system The actor system
     * @param handlerClass The handler class
     * @param parentId The parent actor ID, or null if no parent
     */
    public IdTemplateProcessor(ActorSystem system, Class<?> handlerClass, String parentId) {
        this.system = system;
        this.handlerClass = handlerClass;
        this.parentId = parentId;
    }

    /**
     * Process template and replace all placeholders.
     * Does NOT include hierarchical prefix - that's added separately by the builder.
     *
     * @param template The template string with placeholders
     * @return The processed template with all placeholders replaced
     */
    public String process(String template) {
        ensureCountersInitialized();

        String result = template;

        // Replace placeholders
        result = replacePlaceholder(result, "seq", this::getSequentialId);
        result = replacePlaceholder(result, "uuid", this::getUuid);
        result = replacePlaceholder(result, "short-uuid", this::getShortUuid);
        result = replacePlaceholder(result, "timestamp", this::getTimestamp);
        result = replacePlaceholder(result, "nano", this::getNano);
        result = replacePlaceholder(result, "class", this::getClassName);
        result = replacePlaceholder(result, "parent", this::getParentId);
        result = replacePlaceholder(result, "template-seq", () -> getTemplateSequentialId(template));

        return result;
    }

    /**
     * Ensure counters are initialized from persisted state on first use.
     */
    private void ensureCountersInitialized() {
        if (countersInitialized) {
            return;
        }

        synchronized (initLock) {
            if (countersInitialized) {
                return;
            }

            // Initialize counters from persisted state
            initializeCountersFromPersistedState();
            countersInitialized = true;
        }
    }

    /**
     * Scan all persisted snapshots/journals and set counters to max found.
     * This ensures that sequential IDs don't collide with existing persisted actors.
     */
    private void initializeCountersFromPersistedState() {
        var persistenceProvider = system.getPersistenceProvider();
        if (persistenceProvider == null) {
            logger.debug("No persistence provider configured, skipping counter initialization");
            return;
        }

        try {
            // Get all persisted actor IDs
            var persistedActorIds = persistenceProvider.listPersistedActors();

            if (persistedActorIds.isEmpty()) {
                logger.debug("No persisted actors found, counters start at 0");
                return;
            }

            logger.info("Initializing ID counters from {} persisted actors", persistedActorIds.size());

            // Parse IDs and update counters
            for (String actorId : persistedActorIds) {
                updateCounterFromActorId(actorId);
            }

            logger.info("Counter initialization complete. Class counters: {}", classCounters);

        } catch (Exception e) {
            logger.warn("Failed to initialize counters from persisted state", e);
        }
    }

    /**
     * Parse actor ID and update counter if it matches sequential pattern.
     * Examples:
     * <ul>
     *   <li>{@code "user:5"} → set user counter to max(current, 5)</li>
     *   <li>{@code "parent/child:3"} → set child counter to max(current, 3)</li>
     * </ul>
     */
    private static void updateCounterFromActorId(String actorId) {
        // Handle hierarchical IDs: "parent/child:3" -> "child:3"
        String baseId = actorId.contains("/")
            ? actorId.substring(actorId.lastIndexOf('/') + 1)
            : actorId;

        // Parse pattern: "prefix:number"
        int colonIndex = baseId.lastIndexOf(':');
        if (colonIndex == -1) {
            return; // Not a sequential ID pattern
        }

        String prefix = baseId.substring(0, colonIndex);
        String suffix = baseId.substring(colonIndex + 1);

        try {
            long sequenceNumber = Long.parseLong(suffix);

            // Update counter to max of current and found
            classCounters.compute(prefix, (k, current) -> {
                if (current == null) {
                    return new AtomicLong(sequenceNumber);
                }
                // Update to max
                long currentVal = current.get();
                if (sequenceNumber > currentVal) {
                    current.set(sequenceNumber);
                }
                return current;
            });

        } catch (NumberFormatException e) {
            // Suffix not a number - skip (e.g., "user:admin")
        }
    }

    /**
     * Replace a single placeholder in the template.
     */
    private String replacePlaceholder(String template, String placeholder,
                                     Supplier<String> valueSupplier) {
        String pattern = "{" + placeholder + "}";
        if (template.contains(pattern)) {
            return template.replace(pattern, valueSupplier.get());
        }
        return template;
    }

    // ========================================
    // Placeholder Value Generators
    // ========================================

    private String getSequentialId() {
        String className = IdStrategy.extractBaseName(handlerClass);
        return String.valueOf(
            classCounters.computeIfAbsent(className, k -> new AtomicLong(0))
                        .incrementAndGet()
        );
    }

    private String getTemplateSequentialId(String template) {
        // Counter based on template string itself
        return String.valueOf(
            templateCounters.computeIfAbsent(template, k -> new AtomicLong(0))
                           .incrementAndGet()
        );
    }

    private String getUuid() {
        return UUID.randomUUID().toString();
    }

    private String getShortUuid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String getTimestamp() {
        return String.valueOf(System.currentTimeMillis());
    }

    private String getNano() {
        return String.valueOf(System.nanoTime());
    }

    private String getClassName() {
        return IdStrategy.extractBaseName(handlerClass);
    }

    private String getParentId() {
        return parentId != null ? parentId : "";
    }

    /**
     * Reset all counters (primarily for testing).
     */
    public static void resetCounters() {
        synchronized (initLock) {
            classCounters.clear();
            templateCounters.clear();
            countersInitialized = false;
        }
    }

    /**
     * Get current counter value for a class (for testing/debugging).
     */
    public static long getCurrentCounter(String baseName) {
        AtomicLong counter = classCounters.get(baseName);
        return counter != null ? counter.get() : 0;
    }
}
