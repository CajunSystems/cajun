package com.cajunsystems.cluster;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Full-featured in-memory MetadataStore for cluster lifecycle tests.
 * Supports watcher callbacks (fired on put/delete) and distributed locking.
 * Use this in tests that call system.start() and system.stop().
 * For tests that don't call start(), use the simpler InMemoryMetadataStore.
 */
class WatchableInMemoryMetadataStore implements MetadataStore {

    final ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Lock> locks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, KeyWatcher> watchers = new ConcurrentHashMap<>();

    @Override
    public CompletableFuture<Void> put(String key, String value) {
        return CompletableFuture.runAsync(() -> {
            store.put(key, value);

            // Notify watchers
            for (Map.Entry<String, KeyWatcher> entry : watchers.entrySet()) {
                if (key.startsWith(entry.getKey())) {
                    entry.getValue().onPut(key, value);
                }
            }
        });
    }

    @Override
    public CompletableFuture<Optional<String>> get(String key) {
        return CompletableFuture.supplyAsync(() -> Optional.ofNullable(store.get(key)));
    }

    @Override
    public CompletableFuture<Void> delete(String key) {
        return CompletableFuture.runAsync(() -> {
            store.remove(key);

            // Notify watchers
            for (Map.Entry<String, KeyWatcher> entry : watchers.entrySet()) {
                if (key.startsWith(entry.getKey())) {
                    entry.getValue().onDelete(key);
                }
            }
        });
    }

    @Override
    public CompletableFuture<List<String>> listKeys(String prefix) {
        return CompletableFuture.supplyAsync(() ->
            store.keySet().stream()
                .filter(k -> k.startsWith(prefix))
                .collect(Collectors.toList())
        );
    }

    @Override
    public CompletableFuture<Optional<Lock>> acquireLock(String lockName, long ttlSeconds) {
        return CompletableFuture.supplyAsync(() -> {
            if (locks.containsKey(lockName)) {
                return Optional.empty();
            }
            InMemoryLock lock = new InMemoryLock(lockName);
            locks.put(lockName, lock);
            return Optional.of((Lock) lock);
        });
    }

    @Override
    public CompletableFuture<Long> watch(String key, KeyWatcher watcher) {
        return CompletableFuture.supplyAsync(() -> {
            long watchId = System.nanoTime();
            watchers.put(key, watcher);
            return watchId;
        });
    }

    @Override
    public CompletableFuture<Void> unwatch(long watchId) {
        // Simplified implementation — watchId-based removal not needed for tests
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

    private class InMemoryLock implements Lock {

        private final String lockName;

        InMemoryLock(String name) {
            this.lockName = name;
        }

        @Override
        public CompletableFuture<Void> release() {
            return CompletableFuture.runAsync(() -> locks.remove(lockName));
        }

        @Override
        public CompletableFuture<Void> refresh() {
            return CompletableFuture.completedFuture(null);
        }
    }
}
