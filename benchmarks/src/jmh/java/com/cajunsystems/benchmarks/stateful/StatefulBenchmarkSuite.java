package com.cajunsystems.benchmarks.stateful;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Stateful benchmarks that create files and require cleanup.
 * 
 * These benchmarks may create WAL files, snapshots, or other persistent state.
 * They automatically clean up created files after each run.
 * 
 * Run with:
 * java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*StatefulBenchmarkSuite.*"
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 1, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 3, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class StatefulBenchmarkSuite {

    private static final String TEMP_DIR = System.getProperty("java.io.tmpdir");
    private static final String CAJUN_TEMP_PREFIX = "cajun-benchmark-";

    /**
     * Main method to run stateful benchmarks independently.
     */
    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(".*StatefulBenchmarkSuite.*")
                .build();
        
        new Runner(opt).run();
    }

    /**
     * Clean up any temporary files created during benchmarks.
     * This runs after each benchmark iteration.
     */
    @TearDown(Level.Trial)
    public void cleanupTempFiles() {
        try {
            try (Stream<Path> paths = Files.list(Paths.get(TEMP_DIR))) {
                paths.filter(path -> {
                    String fileName = path.getFileName().toString();
                    return fileName.startsWith(CAJUN_TEMP_PREFIX) || 
                           fileName.endsWith(".wal") || 
                           fileName.endsWith(".snapshot") ||
                           fileName.contains("cajun-test");
                })
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                        System.out.println("Cleaned up: " + path);
                    } catch (IOException e) {
                        System.err.println("Failed to delete: " + path + " - " + e.getMessage());
                    }
                });
            }
        } catch (IOException e) {
            System.err.println("Error during cleanup: " + e.getMessage());
        }
    }

    /**
     * Delegate to the comparison benchmark for actual testing.
     */
    @Benchmark
    public void runComparisonBenchmark() throws Exception {
        // This delegates to the ComparisonBenchmark for traditional actor vs thread comparisons
        ComparisonBenchmark comparison = new ComparisonBenchmark();
        comparison.setup();
        
        try {
            // Run a representative sample of comparison benchmarks
            comparison.singleTask_Actors();
            comparison.batchProcessing_Actors();
            comparison.requestReply_Actors();
        } finally {
            comparison.tearDown();
        }
    }
    
    /**
     * LMDB integration benchmark - compares file-based vs LMDB persistence
     */
    @Benchmark
    public void runLmdbComparisonBenchmark() throws Exception {
        LmdbStatefulBenchmark lmdbBenchmark = new LmdbStatefulBenchmark();
        lmdbBenchmark.setup();
        
        try {
            // Run a representative sample of LMDB benchmarks
            lmdbBenchmark.benchmarkMessageJournalOperations();
            lmdbBenchmark.benchmarkSnapshotStoreOperations();
            lmdbBenchmark.benchmarkHealthCheck();
            lmdbBenchmark.benchmarkSyncOperation();
        } finally {
            lmdbBenchmark.tearDown();
        }
    }
}
