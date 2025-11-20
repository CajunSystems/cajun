package com.cajunsystems.cluster;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for a distributed metadata store used in cluster mode.
 * This store is used to maintain actor assignments, leader election,
 * and other cluster-related metadata.
 */
public interface MetadataStore {
    
    /**
     * Puts a key-value pair in the store.
     *
     * @param key The key
     * @param value The value
     * @return A CompletableFuture that completes when the operation is done
     */
    CompletableFuture<Void> put(String key, String value);
    
    /**
     * Gets a value from the store by key.
     *
     * @param key The key
     * @return A CompletableFuture that completes with the value, or empty if not found
     */
    CompletableFuture<Optional<String>> get(String key);
    
    /**
     * Deletes a key-value pair from the store.
     *
     * @param key The key
     * @return A CompletableFuture that completes when the operation is done
     */
    CompletableFuture<Void> delete(String key);
    
    /**
     * Lists all keys with a given prefix.
     *
     * @param prefix The key prefix
     * @return A CompletableFuture that completes with a list of keys
     */
    CompletableFuture<List<String>> listKeys(String prefix);
    
    /**
     * Attempts to acquire a distributed lock.
     *
     * @param lockName The name of the lock
     * @param ttlSeconds Time-to-live in seconds
     * @return A CompletableFuture that completes with a lock object if acquired, or empty if not
     */
    CompletableFuture<Optional<Lock>> acquireLock(String lockName, long ttlSeconds);
    
    /**
     * Watches a key for changes.
     *
     * @param key The key to watch
     * @param watcher The watcher to notify of changes
     * @return A CompletableFuture that completes with a watch ID
     */
    CompletableFuture<Long> watch(String key, KeyWatcher watcher);
    
    /**
     * Stops watching a key.
     *
     * @param watchId The watch ID returned from watch()
     * @return A CompletableFuture that completes when the operation is done
     */
    CompletableFuture<Void> unwatch(long watchId);
    
    /**
     * Connects to the metadata store.
     *
     * @return A CompletableFuture that completes when connected
     */
    CompletableFuture<Void> connect();
    
    /**
     * Closes the connection to the metadata store.
     *
     * @return A CompletableFuture that completes when closed
     */
    CompletableFuture<Void> close();
    
    /**
     * Interface for a distributed lock.
     */
    interface Lock {
        /**
         * Releases the lock.
         *
         * @return A CompletableFuture that completes when the lock is released
         */
        CompletableFuture<Void> release();
        
        /**
         * Refreshes the lock's TTL.
         *
         * @return A CompletableFuture that completes when the lock is refreshed
         */
        CompletableFuture<Void> refresh();
    }
    
    /**
     * Interface for watching key changes.
     */
    interface KeyWatcher {
        /**
         * Called when a key is created or updated.
         *
         * @param key The key
         * @param value The new value
         */
        void onPut(String key, String value);
        
        /**
         * Called when a key is deleted.
         *
         * @param key The key
         */
        void onDelete(String key);
    }
}
