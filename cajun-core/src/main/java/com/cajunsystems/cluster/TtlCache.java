package com.cajunsystems.cluster;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class TtlCache<K, V> {

    private record Entry<V>(V value, long expiresAtMs) {}

    private final ConcurrentHashMap<K, Entry<V>> map = new ConcurrentHashMap<>();
    private final long ttlMs;

    public TtlCache(long ttlMs) {
        this.ttlMs = ttlMs;
    }

    public void put(K key, V value) {
        map.put(key, new Entry<>(value, System.currentTimeMillis() + ttlMs));
    }

    public Optional<V> get(K key) {
        Entry<V> entry = map.get(key);
        if (entry == null) return Optional.empty();
        if (System.currentTimeMillis() >= entry.expiresAtMs()) {
            map.remove(key, entry);
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    public void invalidate(K key) {
        map.remove(key);
    }

    public Map<K, V> snapshot() {
        long now = System.currentTimeMillis();
        Map<K, V> result = new HashMap<>();
        map.forEach((k, e) -> {
            if (now < e.expiresAtMs()) result.put(k, e.value());
        });
        return Collections.unmodifiableMap(result);
    }

    public void cleanupExpired() {
        long now = System.currentTimeMillis();
        map.entrySet().removeIf(e -> now >= e.getValue().expiresAtMs());
    }

    public int size() {
        return map.size();
    }
}
