package com.cajunsystems.runtime.persistence;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Configuration for LMDB-based persistence with enhanced features.
 * 
 * This configuration class provides comprehensive options for LMDB environment
 * setup, performance tuning, and operational parameters.
 */
public class LmdbConfig {
    
    // Database configuration
    private Path dbPath;
    private long mapSize = 10_485_760_000L; // 10GB default
    private int maxDatabases = 100;
    private int maxReaders = 126;
    private boolean readOnly = false;
    
    // Performance tuning
    private boolean noSync = false;
    private boolean noMetaSync = false;
    private boolean writeMap = true;
    private boolean mapAsync = false;
    
    // Batch operations
    private int batchSize = 1000;
    private Duration syncTimeout = Duration.ofSeconds(5);
    private Duration transactionTimeout = Duration.ofSeconds(30);
    private boolean autoCommit = true;
    
    // Monitoring and recovery
    private boolean enableMetrics = true;
    private Duration checkpointInterval = Duration.ofMinutes(5);
    private int maxRetries = 3;
    private Duration retryDelay = Duration.ofMillis(100);
    
    // Serialization
    private SerializationFormat serializationFormat = SerializationFormat.JAVA;
    private boolean enableCompression = false;
    
    public enum SerializationFormat {
        JAVA,
        JSON
    }
    
    // Constructors
    public LmdbConfig() {
        this.dbPath = Path.of(System.getProperty("java.io.tmpdir"), "cajun-lmdb");
    }
    
    public LmdbConfig(Path dbPath) {
        this.dbPath = dbPath;
    }
    
    // Builders
    public static LmdbConfig.Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private final LmdbConfig config = new LmdbConfig();
        
        public Builder dbPath(Path path) {
            config.dbPath = path;
            return this;
        }
        
        public Builder mapSize(long size) {
            config.mapSize = size;
            return this;
        }
        
        public Builder maxDatabases(int max) {
            config.maxDatabases = max;
            return this;
        }
        
        public Builder maxReaders(int max) {
            config.maxReaders = max;
            return this;
        }
        
        public Builder readOnly(boolean readOnly) {
            config.readOnly = readOnly;
            return this;
        }
        
        public Builder noSync(boolean noSync) {
            config.noSync = noSync;
            return this;
        }
        
        public Builder noMetaSync(boolean noMetaSync) {
            config.noMetaSync = noMetaSync;
            return this;
        }
        
        public Builder writeMap(boolean writeMap) {
            config.writeMap = writeMap;
            return this;
        }
        
        public Builder mapAsync(boolean mapAsync) {
            config.mapAsync = mapAsync;
            return this;
        }
        
        public Builder batchSize(int size) {
            config.batchSize = size;
            return this;
        }
        
        public Builder syncTimeout(Duration timeout) {
            config.syncTimeout = timeout;
            return this;
        }
        
        public Builder transactionTimeout(Duration timeout) {
            config.transactionTimeout = timeout;
            return this;
        }
        
        public Builder autoCommit(boolean autoCommit) {
            config.autoCommit = autoCommit;
            return this;
        }
        
        public Builder enableMetrics(boolean enable) {
            config.enableMetrics = enable;
            return this;
        }
        
        public Builder checkpointInterval(Duration interval) {
            config.checkpointInterval = interval;
            return this;
        }
        
        public Builder maxRetries(int max) {
            config.maxRetries = max;
            return this;
        }
        
        public Builder retryDelay(Duration delay) {
            config.retryDelay = delay;
            return this;
        }
        
        public Builder serializationFormat(SerializationFormat format) {
            config.serializationFormat = format;
            return this;
        }
        
        public Builder enableCompression(boolean enable) {
            config.enableCompression = enable;
            return this;
        }
        
        public LmdbConfig build() {
            config.validate();
            return config;
        }
    }
    
    // Validation
    private void validate() {
        if (dbPath == null) {
            throw new IllegalArgumentException("Database path cannot be null");
        }
        if (mapSize <= 0) {
            throw new IllegalArgumentException("Map size must be positive");
        }
        if (maxDatabases <= 0) {
            throw new IllegalArgumentException("Max databases must be positive");
        }
        if (maxReaders <= 0) {
            throw new IllegalArgumentException("Max readers must be positive");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("Batch size must be positive");
        }
        if (syncTimeout == null || transactionTimeout == null || checkpointInterval == null || retryDelay == null) {
            throw new IllegalArgumentException("Timeout durations cannot be null");
        }
    }
    
    // Getters
    public Path getDbPath() { return dbPath; }
    public long getMapSize() { return mapSize; }
    public int getMaxDatabases() { return maxDatabases; }
    public int getMaxReaders() { return maxReaders; }
    public boolean isReadOnly() { return readOnly; }
    public boolean isNoSync() { return noSync; }
    public boolean isNoMetaSync() { return noMetaSync; }
    public boolean isWriteMap() { return writeMap; }
    public boolean isMapAsync() { return mapAsync; }
    public int getBatchSize() { return batchSize; }
    public Duration getSyncTimeout() { return syncTimeout; }
    public Duration getTransactionTimeout() { return transactionTimeout; }
    public boolean isAutoCommit() { return autoCommit; }
    public boolean isEnableMetrics() { return enableMetrics; }
    public Duration getCheckpointInterval() { return checkpointInterval; }
    public int getMaxRetries() { return maxRetries; }
    public Duration getRetryDelay() { return retryDelay; }
    public SerializationFormat getSerializationFormat() { return serializationFormat; }
    public boolean isEnableCompression() { return enableCompression; }
    
    // Setters (for programmatic configuration)
    public void setDbPath(Path dbPath) { this.dbPath = dbPath; }
    public void setMapSize(long mapSize) { this.mapSize = mapSize; }
    public void setMaxDatabases(int maxDatabases) { this.maxDatabases = maxDatabases; }
    public void setMaxReaders(int maxReaders) { this.maxReaders = maxReaders; }
    public void setReadOnly(boolean readOnly) { this.readOnly = readOnly; }
    public void setNoSync(boolean noSync) { this.noSync = noSync; }
    public void setNoMetaSync(boolean noMetaSync) { this.noMetaSync = noMetaSync; }
    public void setWriteMap(boolean writeMap) { this.writeMap = writeMap; }
    public void setMapAsync(boolean mapAsync) { this.mapAsync = mapAsync; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public void setSyncTimeout(Duration syncTimeout) { this.syncTimeout = syncTimeout; }
    public void setTransactionTimeout(Duration transactionTimeout) { this.transactionTimeout = transactionTimeout; }
    public void setAutoCommit(boolean autoCommit) { this.autoCommit = autoCommit; }
    public void setEnableMetrics(boolean enableMetrics) { this.enableMetrics = enableMetrics; }
    public void setCheckpointInterval(Duration checkpointInterval) { this.checkpointInterval = checkpointInterval; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    public void setRetryDelay(Duration retryDelay) { this.retryDelay = retryDelay; }
    public void setSerializationFormat(SerializationFormat serializationFormat) { this.serializationFormat = serializationFormat; }
    public void setEnableCompression(boolean enableCompression) { this.enableCompression = enableCompression; }
    
    @Override
    public String toString() {
        return "LmdbConfig{" +
                "dbPath=" + dbPath +
                ", mapSize=" + mapSize +
                ", maxDatabases=" + maxDatabases +
                ", maxReaders=" + maxReaders +
                ", readOnly=" + readOnly +
                ", noSync=" + noSync +
                ", noMetaSync=" + noMetaSync +
                ", writeMap=" + writeMap +
                ", mapAsync=" + mapAsync +
                ", batchSize=" + batchSize +
                ", syncTimeout=" + syncTimeout +
                ", transactionTimeout=" + transactionTimeout +
                ", autoCommit=" + autoCommit +
                ", enableMetrics=" + enableMetrics +
                ", checkpointInterval=" + checkpointInterval +
                ", maxRetries=" + maxRetries +
                ", retryDelay=" + retryDelay +
                ", serializationFormat=" + serializationFormat +
                ", enableCompression=" + enableCompression +
                '}';
    }
}
