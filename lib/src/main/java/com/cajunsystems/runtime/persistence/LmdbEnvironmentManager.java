package com.cajunsystems.runtime.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages LMDB environment operations for high-performance persistence.
 * This class handles the low-level LMDB operations including serialization.
 * For demonstration purposes, uses an in-memory map to simulate LMDB functionality.
 */
public class LmdbEnvironmentManager {
    private static final Logger logger = LoggerFactory.getLogger(LmdbEnvironmentManager.class);
    
    private final Path dbPath;
    private final long mapSize;
    private final int maxDbs;
    
    // In-memory storage to simulate LMDB databases
    private final Map<String, Map<String, byte[]>> databases = new ConcurrentHashMap<>();
    private volatile boolean initialized = false;
    
    /**
     * Creates a new LmdbEnvironmentManager.
     *
     * @param dbPath The path for the LMDB database files
     * @param mapSize The memory map size for LMDB
     * @param maxDbs The maximum number of databases
     */
    public LmdbEnvironmentManager(Path dbPath, long mapSize, int maxDbs) {
        this.dbPath = dbPath;
        this.mapSize = mapSize;
        this.maxDbs = maxDbs;
    }
    
    /**
     * Initializes the LMDB environment.
     */
    public void initialize() {
        if (initialized) {
            return;
        }
        
        try {
            // Initialize the in-memory database structure
            logger.debug("LMDB environment initialized for path: {}, mapSize: {}, maxDbs: {}", 
                        dbPath, mapSize, maxDbs);
            initialized = true;
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize LMDB environment", e);
        }
    }
    
    /**
     * Writes an entry to the specified database.
     *
     * @param dbName The database name
     * @param key The key for the entry
     * @param value The value to store
     */
    public <T> void writeEntry(String dbName, String key, T value) {
        if (!initialized) {
            throw new IllegalStateException("LMDB environment not initialized");
        }
        
        try {
            byte[] serializedValue = serialize(value);
            
            // Get or create the database
            Map<String, byte[]> db = databases.computeIfAbsent(dbName, k -> new ConcurrentHashMap<>());
            db.put(key, serializedValue);
            
            logger.trace("Written entry to database {}: key={}", dbName, key);
        } catch (Exception e) {
            throw new RuntimeException("Failed to write entry to database: " + dbName, e);
        }
    }
    
    /**
     * Reads a value from the specified database.
     *
     * @param dbName The database name
     * @param key The key for the entry
     * @param type The expected type of the value
     * @return The deserialized value, or null if not found
     */
    @SuppressWarnings("unchecked")
    public <T> T readValue(String dbName, String key, Class<T> type) {
        if (!initialized) {
            throw new IllegalStateException("LMDB environment not initialized");
        }
        
        try {
            Map<String, byte[]> db = databases.get(dbName);
            if (db == null) {
                return null;
            }
            
            byte[] serializedValue = db.get(key);
            return serializedValue != null ? (T) deserialize(serializedValue) : null;
        } catch (Exception e) {
            throw new RuntimeException("Failed to read value from database: " + dbName, e);
        }
    }
    
    /**
     * Reads a range of values from the specified database.
     *
     * @param dbName The database name
     * @param startKey The start key (inclusive)
     * @param endKey The end key (exclusive)
     * @return List of deserialized values
     */
    @SuppressWarnings("unchecked")
    public <T> List<T> readRange(String dbName, String startKey, String endKey) {
        if (!initialized) {
            throw new IllegalStateException("LMDB environment not initialized");
        }
        
        List<T> results = new ArrayList<>();
        
        try {
            Map<String, byte[]> db = databases.get(dbName);
            if (db == null) {
                return results;
            }
            
            // Find keys in the specified range
            for (Map.Entry<String, byte[]> entry : db.entrySet()) {
                String key = entry.getKey();
                if (key.compareTo(startKey) >= 0 && key.compareTo(endKey) < 0) {
                    byte[] serializedValue = entry.getValue();
                    if (serializedValue != null) {
                        results.add((T) deserialize(serializedValue));
                    }
                }
            }
            
            logger.trace("Read {} entries from database {} in range [{}..{})", 
                        results.size(), dbName, startKey, endKey);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read range from database: " + dbName, e);
        }
        
        return results;
    }
    
    /**
     * Deletes a range of entries from the specified database.
     *
     * @param dbName The database name
     * @param startKey The start key (inclusive)
     * @param endKey The end key (exclusive)
     */
    public void deleteRange(String dbName, String startKey, String endKey) {
        if (!initialized) {
            throw new IllegalStateException("LMDB environment not initialized");
        }
        
        try {
            Map<String, byte[]> db = databases.get(dbName);
            if (db == null) {
                return;
            }
            
            // Remove keys in the specified range
            List<String> keysToRemove = new ArrayList<>();
            for (String key : db.keySet()) {
                if (key.compareTo(startKey) >= 0 && key.compareTo(endKey) < 0) {
                    keysToRemove.add(key);
                }
            }
            
            for (String key : keysToRemove) {
                db.remove(key);
            }
            
            logger.trace("Deleted {} entries from database {} in range [{}..{})", 
                        keysToRemove.size(), dbName, startKey, endKey);
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete range from database: " + dbName, e);
        }
    }
    
    /**
     * Serializes an object to a byte array.
     *
     * @param obj The object to serialize
     * @return The serialized byte array
     */
    private byte[] serialize(Object obj) throws IOException {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(obj);
            return bos.toByteArray();
        }
    }
    
    /**
     * Deserializes a byte array to an object.
     *
     * @param data The byte array to deserialize
     * @return The deserialized object
     */
    private Object deserialize(byte[] data) throws IOException, ClassNotFoundException {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(data);
             ObjectInputStream ois = new ObjectInputStream(bis)) {
            return ois.readObject();
        }
    }
    
    /**
     * Closes the LMDB environment.
     */
    public void close() {
        if (initialized) {
            try {
                databases.clear();
                initialized = false;
                logger.debug("LMDB environment closed for path: {}", dbPath);
            } catch (Exception e) {
                logger.error("Error closing LMDB environment", e);
            }
        }
    }
    
    /**
     * Checks if the environment is initialized.
     *
     * @return true if initialized, false otherwise
     */
    public boolean isInitialized() {
        return initialized;
    }
}
