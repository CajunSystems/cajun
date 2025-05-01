package systems.cajun.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * File-based implementation of the StateStore interface.
 * This implementation persists state to disk, providing durability across system restarts.
 *
 * @param <V> The type of the value (state), must be Serializable
 */
public class FileStateStore<V extends Serializable> implements StateStore<String, V> {
    
    private static final Logger logger = LoggerFactory.getLogger(FileStateStore.class);
    private final Path baseDirectory;
    private final Executor executor;
    
    /**
     * Creates a new FileStateStore with the specified base directory.
     *
     * @param baseDirectory The directory where state files will be stored
     * @throws IOException If the directory cannot be created
     */
    public FileStateStore(String baseDirectory) throws IOException {
        this.baseDirectory = Paths.get(baseDirectory);
        Files.createDirectories(this.baseDirectory);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        logger.info("FileStateStore initialized with base directory: {}", baseDirectory);
    }
    
    @Override
    public CompletableFuture<Optional<V>> get(String key) {
        return CompletableFuture.supplyAsync(() -> {
            Path filePath = baseDirectory.resolve(sanitizeKey(key));
            if (!Files.exists(filePath)) {
                return Optional.empty();
            }
            
            try (ObjectInputStream ois = new ObjectInputStream(Files.newInputStream(filePath))) {
                @SuppressWarnings("unchecked")
                V value = (V) ois.readObject();
                return Optional.of(value);
            } catch (Exception e) {
                logger.error("Error reading state for key: {}", key, e);
                return Optional.empty();
            }
        }, executor);
    }
    
    @Override
    public CompletableFuture<Void> put(String key, V value) {
        return CompletableFuture.runAsync(() -> {
            Path filePath = baseDirectory.resolve(sanitizeKey(key));
            try (ObjectOutputStream oos = new ObjectOutputStream(Files.newOutputStream(filePath))) {
                oos.writeObject(value);
                oos.flush();
                logger.debug("State saved for key: {}", key);
            } catch (Exception e) {
                logger.error("Error saving state for key: {}", key, e);
                throw new RuntimeException("Failed to save state", e);
            }
        }, executor);
    }
    
    @Override
    public CompletableFuture<Void> delete(String key) {
        return CompletableFuture.runAsync(() -> {
            Path filePath = baseDirectory.resolve(sanitizeKey(key));
            try {
                Files.deleteIfExists(filePath);
                logger.debug("State deleted for key: {}", key);
            } catch (Exception e) {
                logger.error("Error deleting state for key: {}", key, e);
                throw new RuntimeException("Failed to delete state", e);
            }
        }, executor);
    }
    
    @Override
    public CompletableFuture<Boolean> exists(String key) {
        return CompletableFuture.supplyAsync(() -> {
            Path filePath = baseDirectory.resolve(sanitizeKey(key));
            return Files.exists(filePath);
        }, executor);
    }
    
    @Override
    public void close() {
        // No resources to close for file-based store
        logger.info("FileStateStore closed");
    }
    
    /**
     * Sanitizes a key to be used as a filename.
     * Replaces invalid characters with underscores.
     *
     * @param key The key to sanitize
     * @return A sanitized key safe to use as a filename
     */
    private String sanitizeKey(String key) {
        return key.replaceAll("[^a-zA-Z0-9.-]", "_");
    }
}
