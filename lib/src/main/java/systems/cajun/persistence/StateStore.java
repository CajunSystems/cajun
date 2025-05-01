package systems.cajun.persistence;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for state persistence operations.
 * Implementations can provide different storage backends (in-memory, file, database, etc.)
 *
 * @param <K> The type of the key
 * @param <V> The type of the value (state)
 */
public interface StateStore<K, V> {
    
    /**
     * Retrieves a state by its key.
     *
     * @param key The key to look up
     * @return An Optional containing the state if found, empty otherwise
     */
    CompletableFuture<Optional<V>> get(K key);
    
    /**
     * Stores a state with the given key.
     *
     * @param key The key to store the state under
     * @param value The state to store
     * @return A CompletableFuture that completes when the operation is done
     */
    CompletableFuture<Void> put(K key, V value);
    
    /**
     * Deletes a state with the given key.
     *
     * @param key The key to delete
     * @return A CompletableFuture that completes when the operation is done
     */
    CompletableFuture<Void> delete(K key);
    
    /**
     * Checks if a state with the given key exists.
     *
     * @param key The key to check
     * @return A CompletableFuture that completes with true if the key exists, false otherwise
     */
    CompletableFuture<Boolean> exists(K key);
    
    /**
     * Closes the state store, releasing any resources.
     */
    void close();
}
