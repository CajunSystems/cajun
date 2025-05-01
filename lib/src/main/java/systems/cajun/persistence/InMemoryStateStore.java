package systems.cajun.persistence;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of the StateStore interface.
 * This implementation is suitable for testing and development,
 * but does not provide persistence across system restarts.
 *
 * @param <K> The type of the key
 * @param <V> The type of the value (state)
 */
public class InMemoryStateStore<K, V> implements StateStore<K, V> {
    
    private final Map<K, V> store = new ConcurrentHashMap<>();
    
    @Override
    public CompletableFuture<Optional<V>> get(K key) {
        return CompletableFuture.completedFuture(Optional.ofNullable(store.get(key)));
    }
    
    @Override
    public CompletableFuture<Void> put(K key, V value) {
        store.put(key, value);
        return CompletableFuture.completedFuture(null);
    }
    
    @Override
    public CompletableFuture<Void> delete(K key) {
        store.remove(key);
        return CompletableFuture.completedFuture(null);
    }
    
    @Override
    public CompletableFuture<Boolean> exists(K key) {
        return CompletableFuture.completedFuture(store.containsKey(key));
    }
    
    @Override
    public void close() {
        // Nothing to close for in-memory store
        store.clear();
    }
}
