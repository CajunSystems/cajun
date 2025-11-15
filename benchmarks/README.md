# Cajun Actor System Benchmarks

This module contains comprehensive benchmarks comparing the performance of **Actors**, **Threads**, and **Structured Concurrency** (Java 21+) for various concurrent programming patterns.

## Overview

The benchmarks use [JMH (Java Microbenchmark Harness)](https://github.com/openjdk/jmh) to provide accurate, statistically sound performance measurements. They test common concurrency patterns across all three approaches to provide apples-to-apples comparisons.

## Benchmark Categories

### 1. Actor Benchmarks (`ActorBenchmark.java`)

Tests actor-specific performance characteristics:

- **Message Throughput**: Fire-and-forget message passing
- **Ping-Pong Latency**: Round-trip message exchange between actors
- **Stateful Updates**: State-changing operations in stateful actors
- **Actor Creation**: Overhead of spawning new actors
- **Request-Reply**: End-to-end latency including computation
- **Message Bursts**: High-volume message handling
- **Multi-Actor Concurrency**: Scalability with multiple actors

### 2. Thread Benchmarks (`ThreadBenchmark.java`)

Tests traditional thread-based concurrency:

- **Atomic Operations**: Lock-free counter increments
- **Locked Operations**: Explicit locking overhead
- **Task Submission**: Thread pool scheduling overhead
- **Virtual Thread Creation**: Java 21 virtual thread performance
- **Platform Thread Creation**: Traditional thread overhead
- **Blocking Queues**: Inter-thread communication
- **CompletableFuture**: Async computation patterns
- **Lock Contention**: Shared state under contention

### 3. Structured Concurrency Benchmarks (`StructuredConcurrencyBenchmark.java`)

Tests Java 21 structured concurrency features:

- **Single Task**: Basic StructuredTaskScope overhead
- **Multiple Tasks**: Parallel task execution
- **Task Racing**: ShutdownOnSuccess pattern
- **Aggregation**: Parallel work with result collection
- **Nested Scopes**: Hierarchical task organization
- **Error Handling**: Fail-fast behavior
- **Mixed Workloads**: CPU and IO-bound task handling

### 4. Comparison Benchmarks (`ComparisonBenchmark.java`)

Direct comparisons running identical workloads:

- **Single Task**: Simple computation across all approaches
- **Batch Processing**: 100 parallel tasks
- **Request-Reply**: Async request/response pattern
- **Pipeline**: Sequential processing stages
- **Scatter-Gather**: Parallel work with result aggregation

### 5. Mailbox Type Benchmarks (`MailboxTypeBenchmark.java`)

Compares different actor mailbox implementations:

- **BLOCKING**: Traditional thread-per-actor with LinkedBlockingQueue (baseline)
- **DISPATCHER_LBQ**: Dispatcher-based with LinkedBlockingQueue
- **DISPATCHER_MPSC**: Dispatcher-based with lock-free JCTools MpscArrayQueue

**Benchmark Patterns:**

- **Single Actor Throughput**: Raw message processing performance
- **Multiple Actors**: Concurrent actor scalability with dispatcher
- **Fan-Out Pattern**: Broadcasting messages to multiple actors
- **Burst Sending**: Handling rapid message bursts from producer

**Parameters:**

- Message counts: 1,000 and 10,000 messages
- Actor counts: 1 and 4 actors
- All combinations tested across all mailbox types

### 6. LMDB Stateful Benchmarks (`LmdbStatefulBenchmark.java`)

Tests LMDB persistence performance for stateful actors:

- **Configuration Access**: LMDB configuration and environment setup overhead
- **Health Check**: LMDB environment health monitoring performance
- **Persistence Metrics**: Metrics collection and reporting overhead
- **Message Journal Operations**: Append and read operations with LMDB storage
- **Snapshot Store Operations**: State persistence and recovery performance
- **Sync Operations**: Database durability and flush performance

**LMDB Features Tested:**

- ACID transaction guarantees
- Memory-mapped storage operations
- Concurrent read/write access
- Environment management and cleanup
- Metrics and monitoring capabilities

**Performance Characteristics:**

- Ultra-fast configuration access (>1M ops/ms)
- Efficient health checking (~6K ops/ms)
- Reliable sync operations (~35 ops/ms)
- Production-ready snapshot persistence (~17 ops/ms)

**Docker Requirement**: LMDB benchmarks require Docker execution to avoid ARM64 native library compatibility issues. Use `./run-benchmark.sh quick` or `./run-benchmark.sh lmdb` for reliable results.

### 7. Filesystem vs LMDB Comparison (`OptimizedFileStatefulBenchmark.java`)

Direct comparison between filesystem-based and LMDB persistence for stateful actors:

- **Single State Update**: Individual state change operations
- **State Read**: Reading current actor state
- **Small Batch Updates**: Batched state operations (20 operations)
- **Stateful Computation**: State changes with computation
- **Few Stateful Actors**: Multiple concurrent stateful actors (5 actors)
- **Medium Frequency Updates**: Higher frequency state changes (100 operations)

**Comparison Metrics:**

- **Throughput**: Operations per second for both persistence types
- **Latency**: Average time per operation
- **Resource Usage**: Disk space and memory consumption
- **Scalability**: Performance under concurrent load
- **Durability**: Data persistence and recovery characteristics

**Expected Results:**

- **LMDB**: Higher throughput, lower latency, better concurrency
- **Filesystem**: Simpler implementation, more disk space usage
- **Use Case**: Choose based on performance vs simplicity requirements

**Docker Recommended**: Filesystem benchmarks can create many temporary files. Use `./run-benchmark.sh persistence-quick` for a quick comparison or `./run-benchmark.sh persistence` for full analysis.

## Running Benchmarks

### Method 1: Direct JAR Execution (Recommended)

**Step 1:** Build the JMH JAR:

```bash
./gradlew :benchmarks:jmhJar
```

**Step 2:** Run benchmarks:

```bash
# Run all benchmarks
java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar

# Run specific benchmark class
java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*ActorBenchmark.*"

# Quick test (1 warmup, 2 iterations, 1 fork)
java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*ActorBenchmark.*" -wi 1 -i 2 -f 1

# Save results to JSON
java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*" -rf json -rff results.json
```

### Method 2: Using Gradle Tasks

Run all benchmarks with full statistical rigor:

```bash
./gradlew :benchmarks:jmh
```

This will:
- Run 3 warmup iterations (2 seconds each)
- Run 5 measurement iterations (3 seconds each)
- Fork the JVM 2 times for better accuracy
- Output results to `benchmarks/build/reports/jmh/`

Quick development benchmarks:

```bash
./gradlew :benchmarks:jmhQuick
```

This runs with reduced iterations (1 warmup, 2 measurements, 1 fork).

### Method 3: Docker-Based Flexible Benchmark Runner (Recommended for Cross-Platform)

For consistent results across different platforms and to avoid ARM64 native library issues, use the Docker-based benchmark runner:

#### Quick Start

```bash
# Make script executable (if not already)
chmod +x run-benchmark.sh

# Show help and available options
./run-benchmark.sh help

# Run quick LMDB benchmarks
./run-benchmark.sh quick

# Run in interactive mode
./run-benchmark.sh interactive
```

#### Available Commands

| **Command** | **Description** | **Use Case** |
|-------------|-----------------|--------------|
| `./run-benchmark.sh help` | Show help and options | First time usage |
| `./run-benchmark.sh list` | List all available benchmarks | Discover benchmarks |
| `./run-benchmark.sh quick` | Quick LMDB benchmarks | Fast validation |
| `./run-benchmark.sh lmdb` | Full LMDB benchmarks | Complete LMDB analysis |
| `./run-benchmark.sh all` | All benchmarks | Full system profile |
| `./run-benchmark.sh lightweight` | Lightweight stateful benchmarks | Stateful actor tests |
| `./run-benchmark.sh actor` | Actor benchmarks | General actor performance |
| `./run-benchmark.sh mailbox` | Mailbox benchmarks | Queue implementation tests |
| `./run-benchmark.sh persistence` | Filesystem vs LMDB comparison | Persistence performance analysis |
| `./run-benchmark.sh persistence-quick` | Quick persistence comparison | Fast filesystem vs LMDB test |
| `./run-benchmark.sh results` | Show latest results | Analyze output |

#### Custom Benchmark Execution

```bash
# Run specific benchmark with custom parameters
./run-benchmark.sh custom LmdbStatefulBenchmark.benchmarkMessageJournalOperations -wi 2 -i 3 -f 1 -t 4

# Run with different thread counts
./run-benchmark.sh custom ActorBenchmark -wi 1 -i 2 -f 1 -t 8

# Run with profilers
./run-benchmark.sh custom LmdbStatefulBenchmark -prof gc

# Run multiple benchmarks with regex
./run-benchmark.sh custom ".*Benchmark.*" -wi 1 -i 2 -f 1
```

#### Interactive Mode

```bash
./run-benchmark.sh interactive
```

Interactive mode guides you through:
1. Selecting benchmark type
2. Configuring JMH parameters  
3. Running the benchmark
4. Viewing results

#### Docker Benefits

- ✅ **Cross-Platform**: Works on any system with Docker, bypasses ARM64 issues
- ✅ **Consistent Environment**: Same results across different machines
- ✅ **Isolated Execution**: Clean benchmark environment
- ✅ **Easy Setup**: No native library installation required
- ✅ **Portable**: Can be shared and run anywhere

#### Result Management

Results are automatically saved to `benchmark-results/` directory:

```bash
# View latest results
./run-benchmark.sh results

# Analyze JSON results
cat benchmark-results/latest_benchmark.json | jq .

# Export to CSV (requires jq)
cat benchmark-results/latest_benchmark.json | \
  jq -r '.[] | [.benchmark, .primaryMetric.score, .primaryMetric.scoreUnit] | @csv' > results.csv
```

#### Advanced Usage

```bash
# Parameterized benchmarks
./run-benchmark.sh custom ActorBenchmark -p actorCount=10 -p messageSize=1024

# Multiple thread comparison
for threads in 1 2 4 8; do
  ./run-benchmark.sh custom ActorBenchmark -t $threads -rff actor_${threads}threads.json
done

# Clean up Docker resources
./run-benchmark.sh clean
```

#### JMH Parameters Reference

| **Parameter** | **Description** | **Default** | **Example** |
|---------------|-----------------|-------------|-------------|
| `-wi N` | Warmup iterations | 3 | `-wi 2` |
| `-i N` | Measurement iterations | 5 | `-i 3` |
| `-f N` | Number of forks | 1 | `-f 2` |
| `-t N` | Number of threads | 1 | `-t 4` |
| `-rf FORMAT` | Result format | text | `-rf json` |
| `-rff FILE` | Result file | - | `-rff results.json` |
| `-prof PROFILER` | Enable profilers | - | `-prof gc` |

#### Direct Docker Usage

If you prefer to use Docker directly:

```bash
# Build and run any benchmark
docker-compose --profile runner build benchmark-runner
docker-compose --profile runner run --rm benchmark-runner \
  java --enable-preview --add-opens=java.base/java.nio=ALL-UNNAMED \
  -jar benchmarks/benchmarks-jmh.jar \
  LmdbStatefulBenchmark \
  -wi 2 -i 3 -f 1 -rf json -rff /app/results/custom.json
```

#### Troubleshooting

```bash
# Clean and rebuild
./run-benchmark.sh clean
./run-benchmark.sh quick

# Check container logs
docker-compose --profile runner logs benchmark-runner

# Verify benchmark exists
./run-benchmark.sh list
```

**Note**: The Docker-based runner is the recommended approach for consistent, cross-platform benchmarking, especially on ARM64 systems where native library compatibility issues may occur.

### Running Specific Benchmarks

**Using JAR (Recommended):**

```bash
# Run specific benchmark class
java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*ActorBenchmark.*"
java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*ComparisonBenchmark.*"
java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*MailboxTypeBenchmark.*"

# Run specific benchmark method
java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*ActorBenchmark.messageThroughput"
java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*MailboxTypeBenchmark.singleActorThroughput"
```

**Using Gradle (if needed):**

```bash
./gradlew :benchmarks:jmh -Pjmh.include='.*ActorBenchmark.*'
```

### Running Mailbox Type Comparison

**Using JAR (Recommended):**

```bash
# All mailbox types
java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*MailboxTypeBenchmark.*"

# Only DISPATCHER_MPSC
java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*MailboxTypeBenchmark.*" \
  -p mailboxType=DISPATCHER_MPSC

# Only high message count
java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*MailboxTypeBenchmark.*" \
  -p messageCount=10000

# Specific combo
java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*singleActorThroughput" \
  -p mailboxType=DISPATCHER_MPSC \
  -p messageCount=10000
```

### Benchmark Reports

After running benchmarks, results are available in:

- **JSON format**: `benchmarks/build/reports/jmh/results.json`
- **Human-readable**: `benchmarks/build/reports/jmh/human.txt`

## Understanding the Results

### Benchmark Modes

Benchmarks run in two modes:

1. **Throughput** (`thrpt`): Operations per millisecond - higher is better
2. **Average Time** (`avgt`): Time per operation in milliseconds - lower is better

### Reading the Output

Example output:

```
Benchmark                                    Mode  Cnt   Score   Error  Units
ActorBenchmark.messageThroughput            thrpt   10  1234.5 ± 45.2  ops/ms
ThreadBenchmark.atomicIncrement             thrpt   10  2345.6 ± 67.8  ops/ms
ComparisonBenchmark.singleTask_Actors        avgt   10     0.123 ± 0.01  ms/op
ComparisonBenchmark.singleTask_Threads       avgt   10     0.098 ± 0.02  ms/op
```

- **Mode**: Throughput or average time
- **Cnt**: Number of measurements
- **Score**: Average result
- **Error**: 99.9% confidence interval
- **Units**: Operations per millisecond or milliseconds per operation

### What to Look For

1. **Throughput Comparisons**: Which approach handles the most operations?
2. **Latency Patterns**: Which has the lowest and most consistent latency?
3. **Scalability**: How does performance change with more concurrent work?
4. **Use Case Fit**: Which approach works best for your specific pattern?

## Benchmark Design Considerations

### Why These Patterns?

The benchmarks test patterns commonly found in real applications:

- **Message Passing**: Core actor pattern
- **Request-Reply**: Synchronous-style interactions
- **Batch Processing**: High-volume parallel work
- **Pipeline**: Sequential processing stages
- **Scatter-Gather**: Parallel work with aggregation

### Fairness Across Approaches

All benchmarks:
- Use Java 21 virtual threads where applicable
- Test equivalent workloads across all three approaches
- Account for setup/teardown overhead appropriately
- Use statistically sound measurement techniques

### Limitations

Keep in mind:

- Microbenchmarks don't capture all real-world factors
- Results vary by hardware and JVM configuration
- Some patterns favor certain approaches naturally
- Real applications have additional considerations (monitoring, debugging, etc.)

## Customizing Benchmarks

### Adding New Benchmarks

Create a new class in `src/jmh/java/com/cajunsystems/benchmarks/`:

```java
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
public class MyBenchmark {

    @Setup
    public void setup() {
        // Initialize resources
    }

    @Benchmark
    public void myBenchmark() {
        // Your benchmark code
    }

    @TearDown
    public void tearDown() {
        // Clean up resources
    }
}
```

### Customizing JMH Parameters

Edit `benchmarks/build.gradle` to change:

- Iteration counts
- Warmup duration
- Fork count
- Profilers
- Output formats

## Profiling

Enable profilers for deeper analysis:

```bash
./gradlew :benchmarks:jmh -Pjmh.profilers='gc,stack'
```

Available profilers:
- `gc`: Garbage collection impact
- `stack`: Hottest stack traces  
- `perf`: Linux perf integration (Linux only)
- `async`: Async-profiler integration (requires setup)
- `cl`: Classloader profiling
- `comp`: JIT compilation profiling

## System Requirements

- **Java**: 21 or higher with preview features enabled
- **Memory**: At least 2GB heap recommended for benchmarks
- **CPU**: Multi-core processor for parallel benchmark accuracy
- **OS**: Any platform supporting Java 21 (Linux, macOS, Windows)
- **Docker**: Required for LMDB benchmarks (recommended for cross-platform consistency)
  - Docker Desktop or Docker Engine
  - Docker Compose (or `docker compose` command)
  - For ARM64 systems: Docker automatically handles x86_64 emulation for LMDB compatibility

## Interpreting Results for Your Use Case

Consider these factors when choosing an approach:

### Choose Actors When

- You need isolation and fault tolerance
- State management is complex
- You want location transparency (local/remote)
- Message-based thinking fits your domain

### Choose Threads When

- You need maximum raw throughput
- Shared state with locks is acceptable
- You're integrating with existing thread-based code
- Simplicity is paramount

### Choose Structured Concurrency When
- Task relationships are hierarchical
- You need guaranteed cleanup
- Error handling scope is important
- Task cancellation is critical

## Contributing

To add new benchmarks:

1. Follow the existing pattern structure
2. Ensure fair comparison across approaches
3. Document what the benchmark measures
4. Test that benchmarks complete successfully
5. Include in the appropriate category

## References

- [JMH Documentation](https://github.com/openjdk/jmh)
- [Java Virtual Threads](https://openjdk.org/jeps/444)
- [Structured Concurrency](https://openjdk.org/jeps/453)
- [Cajun Actor System Documentation](../README.md)
