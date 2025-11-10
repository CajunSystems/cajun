# LMDB Stateful Actor Benchmark Guide

## Overview

This guide provides comprehensive instructions for running and analyzing the LMDB stateful actor benchmarks. The benchmarks compare the performance of stateful actors using:

1. **File-based persistence** (default Cajun implementation)
2. **LMDB-based persistence** (Phase 1 implementation)

## Benchmark Components

### 1. LmdbStatefulBenchmark

**Location**: `benchmarks/src/jmh/java/com/cajunsystems/benchmarks/stateful/LmdbStatefulBenchmark.java`

**Purpose**: Direct comparison of file-based vs LMDB persistence for stateful actors.

**Test Scenarios**:

| Test | Description | Operations | What it Measures |
|------|-------------|------------|------------------|
| `singleStateUpdate` | Single state update operation | 1 | Basic state persistence overhead |
| `stateRead` | State read operation | 1 | State retrieval performance |
| `batchStateUpdates` | Batch state updates | 100 | Batch processing efficiency |
| `statefulComputation` | State-dependent computation | 1 | Computation with state access |
| `multipleStatefulActors` | Concurrent stateful actors | 10×10 | Scalability with multiple actors |
| `highFrequencyUpdates` | High-frequency state changes | 1000 | Performance under load |
| `stateReset` | State reset operation | 1 | State clearing performance |

### 2. LmdbComparisonAnalyzer

**Location**: `benchmarks/src/main/java/com/cajunsystems/benchmarks/LmdbComparisonAnalyzer.java`

**Purpose**: Processes JMH benchmark output and generates comprehensive comparison reports.

**Features**:

- Automatic parsing of JMH benchmark results
- Performance improvement calculations
- Impact assessment (Positive/Neutral/Negative)
- Summary statistics and recommendations
- CSV export for further analysis

## Running the Benchmarks

### Prerequisites

Ensure you have:

- Java 21+ with virtual threads enabled
- Gradle build system
- JMH dependencies configured

### Option 1: Run LMDB Benchmarks Only

```bash
# Navigate to project root
cd /Users/pradeep.samuel/cajun

# Build benchmarks
./gradlew :benchmarks:build

# Run LMDB stateful benchmarks
java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*LmdbStatefulBenchmark.*"
```

### Option 2: Run All Stateful Benchmarks

```bash
# Run complete stateful benchmark suite
java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*StatefulBenchmarkSuite.*"
```

### Option 3: Run Specific Test Categories

```bash
# Single operations only
java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*singleStateUpdate.*"

# Batch operations only
java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*batch.*"

# Multi-actor scenarios only
java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*multiple.*"
```

### Option 4: Custom Benchmark Parameters

```bash
# Custom warmup and measurement parameters
java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*LmdbStatefulBenchmark.*" \
  -wi 5 -i 10 -t 4
```

Where:

- `-wi 5` = 5 warmup iterations
- `-i 10` = 10 measurement iterations  
- `-t 4` = 4 threads

## Analyzing Results

### Step 1: Save Benchmark Output

```bash
# Run benchmarks and save output
java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*LmdbStatefulBenchmark.*" > lmdb_results.txt
```

### Step 2: Run Analysis Tool

```bash
# Analyze results
java -cp benchmarks/build/classes/java/main:benchmarks/build/libs/benchmarks.jar \
  com.cajunsystems.benchmarks.LmdbComparisonAnalyzer lmdb_results.txt
```

### Step 3: Review Generated Reports

The analyzer will produce:

1. **Console output** with formatted comparison table
2. **CSV file** (`lmdb_benchmark_results.csv`) with raw data

## Understanding the Results

### Performance Metrics

| Metric | Interpretation | Good For |
|--------|----------------|----------|
| **Throughput** (ops/s) | Higher is better | High-volume workloads |
| **Average Time** (μs) | Lower is better | Latency-sensitive applications |

### Impact Assessment

| Indicator | Meaning | Recommendation |
|-----------|---------|----------------|
| 🟢 Positive | >5% improvement | Consider LMDB for this use case |
| 🟡 Neutral | ±5% change | Performance is comparable |
| 🔴 Negative | >5% degradation | Investigate or avoid LMDB for this case |

### Key Performance Factors

1. **State Update Frequency**
   - High-frequency updates may benefit from LMDB's in-memory operations
   - Low-frequency updates may not see significant benefits

2. **Concurrent Actor Count**
   - More actors = more potential benefit from LMDB's concurrency
   - Single actor scenarios may not leverage LMDB's advantages

3. **State Size**
   - Large state objects benefit from LMDB's efficient serialization
   - Small state objects may have similar performance

4. **Read/Write Patterns**
   - Read-heavy workloads benefit from LMDB's fast reads
   - Write-heavy workloads benefit from LMDB's batching

## Expected Results (Phase 1)

Based on the Phase 1 LMDB implementation:

### Anticipated Improvements

- **State Reads**: 2-5x faster due to in-memory simulation
- **Batch Operations**: 1.5-3x faster due to reduced I/O overhead
- **Concurrent Access**: 2-4x better scalability with multiple actors

### Limitations to Consider

- **Memory Usage**: Higher memory consumption due to in-memory simulation
- **Startup Time**: Slightly slower due to LMDB initialization
- **Disk Persistence**: Not true persistence (simulation) in Phase 1

## Troubleshooting

### Common Issues

1. **Out of Memory Errors**

   ```bash
   Solution: Reduce JVM heap size or LMDB map size
   java -Xmx2g ... (instead of default)
   ```

2. **File Permission Errors**

   ```bash
   Solution: Ensure write permissions to temp directory
   export JAVA_TMPDIR="/path/to/writable/temp"
   ```

3. **Benchmark Timeouts**

   ```bash
   Solution: Increase timeout values in benchmark annotations
   @Measurement(time = 10) // Increase from 3 to 10 seconds
   ```

### Performance Debugging

1. **Enable JMH Profiling**

   ```bash
   java -jar benchmarks-jmh.jar ".*LmdbStatefulBenchmark.*" -prof gc
   ```

2. **Monitor System Resources**

   ```bash
   # Monitor memory usage
   jstat -gc <pid> 1s
   
   # Monitor I/O
   iostat -x 1
   ```

3. **Check LMDB Statistics**

   ```bash
   # Add logging to LmdbEnvironmentManager
   logger.info("LMDB stats: {}", envManager.getStatistics());
   ```

## Next Steps

### Phase 2 Optimizations

When Phase 2 LMDB implementation is available:

1. **True Memory-Mapped I/O**
   - Replace in-memory simulation with real LMDB
   - Expect better disk persistence and lower memory usage

2. **Advanced Batching**
   - Fix race conditions in batched operations
   - Implement more sophisticated batching strategies

3. **Production Features**
   - Add monitoring and metrics
   - Implement backup and recovery procedures

### Production Deployment

1. **Configuration Tuning**

   ```java
   // Optimize LMDB for your workload
   LmdbPersistenceProvider provider = new LmdbPersistenceProvider(
       "/path/to/persistence",
       10_485_760_000L, // 10GB map size
       100 // max databases
   );
   ```

2. **Monitoring Setup**

   - Track memory usage
   - Monitor disk I/O
   - Set up performance alerts

3. **Gradual Migration**

   - Start with non-critical actors
   - Monitor performance impact
   - Gradually migrate critical workloads

## Interpreting Sample Results

Here's how to interpret typical benchmark output:

```text
Benchmark                                          Mode  Score    Units
singleStateUpdate_FileBased.throughput            1500.123  ops/s
singleStateUpdate_LMDB.throughput                2100.456  ops/s

Improvement: +40.0% (Positive)
Impact: LMDB shows 40% better throughput for single state updates
```

**Key Takeaways**:

- Positive improvement > 5% = Consider LMDB
- Neutral change ±5% = Either system works
- Negative impact > 5% = Investigate or avoid

## Conclusion

The LMDB stateful actor benchmarks provide comprehensive performance data to help you decide whether to migrate from file-based to LMDB persistence. Use the analysis tools to make data-driven decisions based on your specific workload patterns and performance requirements.
