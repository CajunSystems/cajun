package systems.cajun.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;

/**
 * Factory for creating StateStore instances.
 * This provides a unified way to create different state store implementations.
 */
public class StateStoreFactory {
    
    private static final Logger logger = LoggerFactory.getLogger(StateStoreFactory.class);
    
    /**
     * Creates an in-memory state store.
     *
     * @param <K> The type of the key
     * @param <V> The type of the value (state)
     * @return A new in-memory state store
     */
    public static <K, V> StateStore<K, V> createInMemoryStore() {
        logger.info("Creating in-memory state store");
        return new InMemoryStateStore<>();
    }
    
    /**
     * Creates a file-based state store.
     *
     * @param baseDirectory The directory where state files will be stored
     * @param <V> The type of the value (state), must be Serializable
     * @return A new file-based state store
     * @throws IOException If the directory cannot be created
     */
    public static <V extends Serializable> StateStore<String, V> createFileStore(String baseDirectory) throws IOException {
        logger.info("Creating file-based state store at {}", baseDirectory);
        return new FileStateStore<>(baseDirectory);
    }
    
    /**
     * Creates a custom state store using the provided implementation.
     *
     * @param stateStore The state store implementation
     * @param <K> The type of the key
     * @param <V> The type of the value (state)
     * @return The provided state store implementation
     */
    public static <K, V> StateStore<K, V> createCustomStore(StateStore<K, V> stateStore) {
        logger.info("Using custom state store: {}", stateStore.getClass().getName());
        return stateStore;
    }
}
