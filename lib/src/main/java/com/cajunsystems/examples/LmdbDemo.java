package com.cajunsystems.examples;

import com.cajunsystems.persistence.JournalEntry;
import com.cajunsystems.persistence.MessageJournal;
import com.cajunsystems.persistence.SnapshotEntry;
import com.cajunsystems.persistence.SnapshotStore;
import com.cajunsystems.persistence.impl.LmdbPersistenceProvider;
import com.cajunsystems.persistence.runtime.persistence.LmdbConfig;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Demonstration of LMDB Implementation - Production Ready.
 * 
 * This example shows the enhanced architecture and features:
 * - Comprehensive configuration
 * - Async operations with CompletableFuture
 * - Health monitoring
 * - Metrics collection
 */
public class LmdbDemo {
    
    public static void main(String[] args) throws Exception {
        System.out.println("=== LMDB Implementation Demo ===\n");
        
        // Create configuration
        LmdbConfig config = LmdbConfig.builder()
                .dbPath(Path.of("/tmp/cajun-lmdb-demo"))
                .mapSize(1_073_741_824L)  // 1GB
                .maxDatabases(50)
                .maxReaders(64)
                .batchSize(1000)
                .syncTimeout(java.time.Duration.ofSeconds(5))
                .transactionTimeout(java.time.Duration.ofSeconds(30))
                .autoCommit(false)
                .enableMetrics(true)
                .checkpointInterval(java.time.Duration.ofMinutes(1))
                .maxRetries(3)
                .retryDelay(java.time.Duration.ofMillis(50))
                .serializationFormat(LmdbConfig.SerializationFormat.JAVA)
                .enableCompression(false)
                .build();
        
        System.out.println("Created configuration:");
        System.out.println("  - Database Path: " + config.getDbPath());
        System.out.println("  - Map Size: " + config.getMapSize() + " bytes");
        System.out.println("  - Max Databases: " + config.getMaxDatabases());
        System.out.println("  - Batch Size: " + config.getBatchSize());
        System.out.println("  - Metrics Enabled: " + config.isEnableMetrics());
        System.out.println();
        
        // Create persistence provider
        LmdbPersistenceProvider provider = new LmdbPersistenceProvider(config);
        
        try {
            // Health check
            System.out.println("Provider Health Check: " + (provider.isHealthy() ? "HEALTHY" : "UNHEALTHY"));
            System.out.println("Provider Name: " + provider.getProviderName());
            System.out.println();
            
            // Demonstrate message journaling
            demonstrateMessageJournal(provider);
            
            // Demonstrate snapshot storage
            demonstrateSnapshotStore(provider);
            
            // Show metrics
            demonstrateMetrics(provider);
            
        } finally {
            provider.close();
            System.out.println("\nProvider closed successfully.");
        }
    }
    
    private static void demonstrateMessageJournal(LmdbPersistenceProvider provider) throws Exception {
        System.out.println("=== Message Journal Demo ===");
        
        // Create message journal
        MessageJournal<String> journal = provider.createMessageJournal("demo-actor");
        
        try {
            // Append messages asynchronously
            System.out.println("Appending messages...");
            CompletableFuture<Long> future1 = journal.append("demo-actor", "Hello LMDB!");
            CompletableFuture<Long> future2 = journal.append("demo-actor", "LMDB integration");
            CompletableFuture<Long> future3 = journal.append("demo-actor", "Enhanced architecture");
            
            // Wait for completion
            Long seq1 = future1.get();
            Long seq2 = future2.get();
            Long seq3 = future3.get();
            
            System.out.println("Messages appended with sequence numbers: " + seq1 + ", " + seq2 + ", " + seq3);
            
            // Read messages asynchronously
            System.out.println("Reading messages from sequence 0...");
            CompletableFuture<List<JournalEntry<String>>> readFuture = journal.readFrom("demo-actor", 0L);
            List<JournalEntry<String>> entries = readFuture.get();
            
            System.out.println("Read " + entries.size() + " messages:");
            for (JournalEntry<String> entry : entries) {
                System.out.println("  - Seq: " + entry.getSequenceNumber() + 
                                 ", Actor: " + entry.getActorId() + 
                                 ", Message: " + entry.getMessage() + 
                                 ", Timestamp: " + entry.getTimestamp());
            }
            
            // Get highest sequence number
            CompletableFuture<Long> highestSeqFuture = journal.getHighestSequenceNumber("demo-actor");
            Long highestSeq = highestSeqFuture.get();
            System.out.println("Highest sequence number: " + highestSeq);
            
        } finally {
            journal.close();
        }
        
        System.out.println();
    }
    
    private static void demonstrateSnapshotStore(LmdbPersistenceProvider provider) throws Exception {
        System.out.println("=== Snapshot Store Demo ===");
        
        // Create snapshot store
        SnapshotStore<String> snapshotStore = provider.createSnapshotStore("demo-actor");
        
        try {
            // Save snapshot asynchronously
            System.out.println("Saving snapshot...");
            String state = "Demo actor state - Phase 2";
            long sequenceNumber = 42L;
            
            CompletableFuture<Void> saveFuture = snapshotStore.saveSnapshot("demo-actor", state, sequenceNumber);
            saveFuture.get();
            
            System.out.println("Snapshot saved with sequence number: " + sequenceNumber);
            
            // Load snapshot asynchronously
            System.out.println("Loading latest snapshot...");
            CompletableFuture<Optional<SnapshotEntry<String>>> loadFuture = snapshotStore.getLatestSnapshot("demo-actor");
            Optional<SnapshotEntry<String>> result = loadFuture.get();
            
            if (result.isPresent()) {
                SnapshotEntry<String> entry = result.get();
                System.out.println("Snapshot loaded:");
                System.out.println("  - Actor: " + entry.getActorId());
                System.out.println("  - Sequence: " + entry.getSequenceNumber());
                System.out.println("  - State: " + entry.getState());
                System.out.println("  - Timestamp: " + entry.getTimestamp());
            } else {
                System.out.println("No snapshot found");
            }
            
        } finally {
            snapshotStore.close();
        }
        
        System.out.println();
    }
    
    private static void demonstrateMetrics(LmdbPersistenceProvider provider) {
        System.out.println("=== Metrics Demo ===");
        
        var metrics = provider.getMetrics();
        var envMetrics = metrics.getEnvironmentMetrics();
        var envStats = metrics.getEnvironmentStats();
        
        System.out.println("Environment Metrics:");
        System.out.println("  - Transactions: " + envMetrics.getTransactionCount());
        System.out.println("  - Read Operations: " + envMetrics.getReadOperations());
        System.out.println("  - Write Operations: " + envMetrics.getWriteOperations());
        System.out.println("  - Bytes Read: " + envMetrics.getTotalBytesRead());
        System.out.println("  - Bytes Written: " + envMetrics.getTotalBytesWritten());
        System.out.println("  - Open Databases: " + envMetrics.getOpenDatabases());
        
        System.out.println("\nEnvironment Statistics:");
        System.out.println("  - Database Count: " + envStats.getDatabaseCount());
        System.out.println("  - Transaction Count: " + envStats.getTransactionCount());
        System.out.println("  - Bytes Read: " + envStats.getBytesRead());
        System.out.println("  - Bytes Written: " + envStats.getBytesWritten());
        
        System.out.println("\nConfiguration:");
        System.out.println("  - Serialization Format: " + metrics.getConfig().getSerializationFormat());
        System.out.println("  - Max Retries: " + metrics.getConfig().getMaxRetries());
        System.out.println("  - Retry Delay: " + metrics.getConfig().getRetryDelay());
    }
}
