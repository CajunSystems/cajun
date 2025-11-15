package com.cajunsystems.benchmarks.stateful;

import com.cajunsystems.persistence.impl.LmdbPersistenceProvider;
import com.cajunsystems.persistence.runtime.persistence.LmdbConfig;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * LMDB Stateful Benchmark - Production Implementation.
 * 
 * Tests the performance of LMDB-backed persistence with:
 * - Enhanced configuration options
 * - Improved metrics and monitoring
 * - Better error handling and retry logic
 * - Resource management optimization
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class LmdbStatefulBenchmark {
    
    private static final Logger logger = LoggerFactory.getLogger(LmdbStatefulBenchmark.class);
    
    private LmdbPersistenceProvider persistenceProvider;
    
    // Enhanced configuration
    private final Path benchmarkPath = Path.of(System.getProperty("java.io.tmpdir"), "cajun-lmdb-benchmark");
    
    @Setup(Level.Trial)
    public void setup() throws IOException {
        logger.info("Setting up LMDB benchmark at: {}", benchmarkPath);
        
        // Enhanced LMDB configuration for benchmarking
        LmdbConfig config = LmdbConfig.builder()
                .dbPath(benchmarkPath)
                .mapSize(1_073_741_824L)  // 1GB for benchmarking
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
        
        // Create persistence provider
        persistenceProvider = new LmdbPersistenceProvider(config);
        
        logger.info("LMDB benchmark setup complete");
    }
    
    @TearDown(Level.Trial)
    public void tearDown() {
        logger.info("Tearing down LMDB benchmark");
        
        if (persistenceProvider != null) {
            persistenceProvider.close();
        }
        
        // Cleanup benchmark directory
        try {
            java.nio.file.Files.walk(benchmarkPath)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            java.nio.file.Files.delete(path);
                        } catch (IOException e) {
                            // Ignore cleanup errors
                        }
                    });
        } catch (IOException e) {
            // Ignore cleanup errors
        }
        
        logger.info("LMDB benchmark teardown complete");
    }
    
    @Benchmark
    public void benchmarkMessageJournalOperations() throws Exception {
        var journal = persistenceProvider.createMessageJournal("benchmark-actor");
        try {
            // Benchmark append operation
            journal.append("benchmark-actor", "test-message").get();
            
            // Benchmark read operation
            journal.readFrom("benchmark-actor", 0L).get();
            
            // Benchmark highest sequence operation
            journal.getHighestSequenceNumber("benchmark-actor").get();
        } finally {
            journal.close();
        }
    }
    
    @Benchmark
    public void benchmarkSnapshotStoreOperations() throws Exception {
        var snapshotStore = persistenceProvider.createSnapshotStore("benchmark-actor");
        try {
            // Benchmark save operation
            snapshotStore.saveSnapshot("benchmark-actor", "test-state", 42L).get();
            
            // Benchmark load operation
            snapshotStore.getLatestSnapshot("benchmark-actor").get();
        } finally {
            snapshotStore.close();
        }
    }
    
    @Benchmark
    public void benchmarkPersistenceMetrics(Blackhole bh) {
        // Test metrics collection overhead
        var metrics = persistenceProvider.getMetrics();
        bh.consume(metrics);
    }
    
    @Benchmark
    public void benchmarkHealthCheck() {
        // Test health check overhead
        persistenceProvider.isHealthy();
    }
    
    @Benchmark
    public void benchmarkSyncOperation() {
        // Test sync operation overhead
        persistenceProvider.sync();
    }
    
    @Benchmark
    public void benchmarkConfigurationAccess() {
        // Test configuration access overhead
        var config = persistenceProvider.getConfig();
        config.getMapSize();
        config.getMaxDatabases();
        config.getBatchSize();
    }
}
