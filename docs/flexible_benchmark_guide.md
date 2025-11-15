# Flexible Benchmark Runner Guide

## 🎯 Overview

The flexible Docker-based benchmark runner allows you to execute any JMH benchmark in the Cajun Actor System with custom parameters, solving ARM64 native library compatibility issues.

## 🚀 Quick Start

### Using the Enhanced Script

```bash
# Make script executable (if not already)
chmod +x run-benchmark.sh

# Show help and available options
./run-benchmark.sh help

# List all available benchmarks
./run-benchmark.sh list

# Run quick LMDB benchmarks
./run-benchmark.sh quick

# Run in interactive mode
./run-benchmark.sh interactive
```

### Using Docker Compose Directly

```bash
# Build and run any benchmark with custom parameters
docker-compose --profile runner build benchmark-runner
docker-compose --profile runner run --rm benchmark-runner \
  java --enable-preview --add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
  -jar benchmarks/benchmarks-jmh.jar \
  LmdbStatefulBenchmark.benchmarkMessageJournalOperations \
  -wi 2 -i 3 -f 1 -rf json -rff /app/results/custom_benchmark.json
```

## 📋 Available Benchmarks

### Predefined Benchmark Types

| **Command** | **Description** | **Use Case** |
|-------------|-----------------|--------------|
| `./run-benchmark.sh lmdb` | Full LMDB benchmarks | Complete LMDB performance analysis |
| `./run-benchmark.sh quick` | Quick LMDB benchmarks | Fast LMDB validation |
| `./run-benchmark.sh all` | All benchmarks | Full system performance profile |
| `./run-benchmark.sh lightweight` | Lightweight stateful benchmarks | Stateful actor performance |
| `./run-benchmark.sh actor` | Actor benchmarks | General actor performance |
| `./run-benchmark.sh mailbox` | Mailbox benchmarks | Queue implementation comparison |

### Individual Benchmark Classes

- **`LmdbStatefulBenchmark`** - LMDB persistence operations
- **`LightweightStatefulBenchmark`** - Stateful actor performance
- **`ActorBenchmark`** - General actor system performance
- **`MailboxTypeBenchmark`** - Mailbox implementation comparison
- **`ThreadBenchmark`** - Thread performance comparison
- **`StructuredConcurrencyBenchmark`** - Structured concurrency performance

## 🎮 Custom Benchmark Execution

### Method 1: Script-Based Custom Execution

```bash
# Run a specific benchmark method with custom parameters
./run-benchmark.sh custom LmdbStatefulBenchmark.benchmarkMessageJournalOperations -wi 2 -i 3 -f 1 -t 4

# Run multiple benchmarks with regex pattern
./run-benchmark.sh custom ".*Benchmark.*" -wi 1 -i 2 -f 1

# Run with specific output format and file
./run-benchmark.sh custom ActorBenchmark -rf json -rff /app/results/my_actor_test.json
```

### Method 2: Interactive Mode

```bash
./run-benchmark.sh interactive
```

Interactive mode guides you through:

1. Selecting benchmark type
2. Configuring JMH parameters
3. Running the benchmark
4. Viewing results

### Method 3: Direct Docker Execution

```bash
# Build the benchmark container
docker-compose --profile runner build benchmark-runner

# Run any benchmark with full control
docker-compose --profile runner run --rm benchmark-runner \
  java --enable-preview \
  --add-opens=java.base/java.nio=ALL-UNNAMED \
  --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
  -jar benchmarks/benchmarks-jmh.jar \
  <BENCHMARK_NAME> \
  [JMH_OPTIONS]
```

## ⚙️ JMH Configuration Options

### Common Parameters

| **Parameter** | **Description** | **Default** | **Example** |
|---------------|-----------------|-------------|-------------|
| `-wi N` | Warmup iterations | 3 | `-wi 2` |
| `-i N` | Measurement iterations | 5 | `-i 3` |
| `-f N` | Number of forks | 1 | `-f 2` |
| `-t N` | Number of threads | 1 | `-t 4` |
| `-rf FORMAT` | Result format | text | `-rf json` |
| `-rff FILE` | Result file | - | `-rff results.json` |
| `-to TIMEOUT` | Timeout per iteration | 10 min | `-to 300s` |

### Advanced Parameters

| **Parameter** | **Description** | **Use Case** |
|---------------|-----------------|--------------|
| `-bm MODE` | Benchmark mode (thrpt, avgt, sampletime) | Different performance metrics |
| `-tu TIMEUNIT` | Time unit (ms, us, ns, s) | Result precision |
| `-p PARAM=VALUE` | Benchmark parameters | Parameterized tests |
| `-prof PROFILER` | Enable profilers (gc, stack, etc.) | Deep performance analysis |

## 📊 Result Analysis

### Viewing Results

```bash
# Show latest results summary
./run-benchmark.sh results

# View raw JSON results
cat benchmark-results/latest_benchmark.json | jq .

# Export to CSV (requires jq)
cat benchmark-results/latest_benchmark.json | \
  jq -r '.[] | [.benchmark, .primaryMetric.score, .primaryMetric.scoreUnit] | @csv' > results.csv
```

### Result Structure

Each benchmark result contains:

- **Benchmark name** and class
- **Performance metrics** (throughput, latency, etc.)
- **JVM configuration** and version
- **Measurement parameters** (iterations, forks, etc.)
- **Statistical data** (percentiles, confidence intervals)

## 🐳 Docker Architecture

### Multi-Stage Build Process

1. **Builder Stage**: Compiles the project with all dependencies
2. **Runtime Stage**: Lightweight execution environment
3. **Platform Cross-Compilation**: Ensures x86_64 compatibility

### Container Benefits

- ✅ **Consistent Environment**: Same results across platforms
- ✅ **No Native Dependencies**: Solves ARM64 compatibility issues
- ✅ **Isolated Execution**: Clean benchmark environment
- ✅ **Easy Distribution**: Shareable benchmark setup

## 🛠️ Advanced Usage

### Parameterized Benchmarks

```bash
# Run benchmarks with different parameters
./run-benchmark.sh custom ActorBenchmark -p actorCount=10 -p messageSize=1024

# Multiple parameter combinations
./run-benchmark.sh custom ActorBenchmark -p actorCount=1,10,100 -p messageSize=512,1024
```

### Profiling Integration

```bash
# Run with GC profiler
./run-benchmark.sh custom LmdbStatefulBenchmark -prof gc

# Run with stack profiler
./run-benchmark.sh custom LmdbStatefulBenchmark -prof stack

# Multiple profilers
./run-benchmark.sh custom LmdbStatefulBenchmark -prof gc,stack
```

### Comparative Benchmarks

```bash
# Compare different mailbox implementations
./run-benchmark.sh custom MailboxTypeBenchmark -wi 3 -i 5 -f 2

# Compare with different thread counts
for threads in 1 2 4 8; do
  ./run-benchmark.sh custom ActorBenchmark -t $threads -rff /app/results/actor_${threads}threads.json
done
```

## 🔧 Troubleshooting

### Common Issues

#### Container Build Fails

```bash
# Clean and rebuild
./run-benchmark.sh clean
./run-benchmark.sh quick
```

#### No Results Generated

```bash
# Check container logs
docker-compose --profile runner logs benchmark-runner

# Verify benchmark name exists
./run-benchmark.sh list
```

#### Permission Issues

```bash
# Ensure script is executable
chmod +x run-benchmark.sh

# Fix results directory permissions
chmod 755 benchmark-results
```

### Performance Tips

1. **Use appropriate warmup**: Allow JVM optimization
2. **Choose right iterations**: Balance accuracy vs time
3. **Consider thread count**: Match your target use case
4. **Use profilers sparingly**: They impact performance
5. **Monitor system resources**: Avoid contention

## 📈 Best Practices

### Benchmark Design

- **Focus on specific operations**: One metric per benchmark
- **Use realistic parameters**: Match production scenarios
- **Include warmup**: Account for JVM warmup
- **Run multiple iterations**: Ensure statistical significance

### Result Analysis

- **Compare relative performance**: Focus on improvements
- **Consider confidence intervals**: Account for variance
- **Monitor system metrics**: CPU, memory, I/O
- **Document test conditions**: JVM version, hardware, etc.

### Continuous Integration

```bash
# Quick validation for CI
./run-benchmark.sh quick

# Full performance test for releases
./run-benchmark.sh all

# Custom regression test
./run-benchmark.sh custom LmdbStatefulBenchmark -wi 1 -i 1 -f 1
```

---

**🎯 This flexible benchmark system provides comprehensive performance testing capabilities for the Cajun Actor System!**
