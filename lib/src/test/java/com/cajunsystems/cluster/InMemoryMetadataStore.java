package com.cajunsystems.cluster;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory MetadataStore backed by a ConcurrentHashMap.
 * For use in unit tests that need cluster state without a real etcd instance.
 */
class InMemoryMetadataStore implements MetadataStore {

    final ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();

    @Override
    public CompletableFuture<Void> put(String key, String value) {
        store.put(key, value);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Optional<String>> get(String key) {
        return CompletableFuture.completedFuture(Optional.ofNullable(store.get(key)));
    }

    @Override
    public CompletableFuture<Void> delete(String key) {
        store.remove(key);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<List<String>> listKeys(String prefix) {
        List<String> keys = store.keySet().stream()
                .filter(k -> k.startsWith(prefix))
                .collect(Collectors.toList());
        return CompletableFuture.completedFuture(keys);
    }

    @Override
    public CompletableFuture<Optional<Lock>> acquireLock(String lockName, long ttlSeconds) {
        return CompletableFuture.completedFuture(Optional.empty());
    }

    @Override
    public CompletableFuture<Long> watch(String key, KeyWatcher watcher) {
        return CompletableFuture.completedFuture(0L);
    }

    @Override
    public CompletableFuture<Void> unwatch(long watchId) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> connect() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> close() {
        return CompletableFuture.completedFuture(null);
    }
}
