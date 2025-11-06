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

## Running Benchmarks

### Full Benchmark Suite

Run all benchmarks with full statistical rigor:

```bash
./gradlew :benchmarks:jmh
```

This will:
- Run 3 warmup iterations (2 seconds each)
- Run 5 measurement iterations (3 seconds each)
- Fork the JVM 2 times for better accuracy
- Output results to `benchmarks/build/reports/jmh/`

### Quick Development Benchmarks

For faster iteration during development:

```bash
./gradlew :benchmarks:jmhQuick
```

This runs with reduced iterations (1 warmup, 2 measurements, 1 fork).

### Running Specific Benchmarks

To run only specific benchmark classes:

```bash
./gradlew :benchmarks:jmh -Pjmh.include='.*ActorBenchmark.*'
./gradlew :benchmarks:jmh -Pjmh.include='.*ComparisonBenchmark.*'
```

To run a specific benchmark method:

```bash
./gradlew :benchmarks:jmh -Pjmh.include='.*ActorBenchmark.messageThroughput'
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
- `async`: Async-profiler integration

## System Requirements

- **Java**: 21 or higher with preview features enabled
- **Memory**: At least 2GB heap recommended for benchmarks
- **CPU**: Multi-core processor for parallel benchmark accuracy
- **OS**: Any platform supporting Java 21 (Linux, macOS, Windows)

## Interpreting Results for Your Use Case

Consider these factors when choosing an approach:

### Choose Actors When:
- You need isolation and fault tolerance
- State management is complex
- You want location transparency (local/remote)
- Message-based thinking fits your domain

### Choose Threads When:
- You need maximum raw throughput
- Shared state with locks is acceptable
- You're integrating with existing thread-based code
- Simplicity is paramount

### Choose Structured Concurrency When:
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
