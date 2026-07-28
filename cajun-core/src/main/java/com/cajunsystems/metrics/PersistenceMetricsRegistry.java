package com.cajunsystems.metrics;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class PersistenceMetricsRegistry {
    private static final ConcurrentHashMap<String, PersistenceMetrics> registry = new ConcurrentHashMap<>();

    private PersistenceMetricsRegistry() {}

    public static PersistenceMetrics getOrCreate(String actorId) {
        return registry.computeIfAbsent(actorId, PersistenceMetrics::new);
    }

    public static Optional<PersistenceMetrics> get(String actorId) {
        return Optional.ofNullable(registry.get(actorId));
    }

    public static Map<String, PersistenceMetrics> getAll() {
        return Collections.unmodifiableMap(registry);
    }

    public static void unregister(String actorId) {
        registry.remove(actorId);
    }
}
