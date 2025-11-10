# Cajun Benchmark Suites

This document describes the organized benchmark structure for Cajun concurrency framework.

## 📁 Benchmark Organization

Benchmarks are now organized into two main categories:

### **🚀 Stateless Benchmarks** (`benchmarks.stateless`)
Pure performance tests without file I/O or persistent state. These can be run independently without cleanup.

- **`DramaticMailboxComparison`** - Mailbox type performance comparison
- **`MailboxTypeBenchmark`** - Original mailbox type tests
- **`ThreadBenchmark`** - Thread-based performance tests
- **`StructuredConcurrencyBenchmark`** - Structured concurrency tests
- **`BackpressureComparisonSuite`** - Backpressure system tests

### **💾 Stateful Benchmarks** (`benchmarks.stateful`)
Tests that may create files, WAL, snapshots, or other persistent state. These include automatic cleanup.

- **`ComparisonBenchmark`** - Actor vs Thread vs Structured Concurrency comparison
- **`ActorBenchmark`** - Core actor performance tests

## 🏃‍♂️ Running Benchmarks

### **Run All Stateless Benchmarks**
```bash
java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*stateless.*"
```

### **Run All Stateful Benchmarks**
```bash
java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*stateful.*"
```

### **Run Mailbox Comparison Only**
```bash
java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*MailboxComparison.*"
```

### **Run Comparison Benchmark in Isolation**
```bash
java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*ComparisonBenchmark.*"
```

### **Run Backpressure Tests Only**
```bash
java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*Backpressure.*"
```

### **Run Specific Benchmark**
```bash
# Example: Run just the dramatic mailbox comparison
java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar \
  "DramaticMailboxComparison.measureMailboxThroughput"
```

## 🧹 Automatic Cleanup

Stateful benchmarks automatically clean up:
- WAL files (*.wal)
- Snapshot files (*.snapshot)
- Temporary files starting with "cajun-benchmark-"
- Files containing "cajun-test" or "actor-"

Cleanup runs after each benchmark trial via `@TearDown(Level.Trial)`.

## 📊 Key Performance Results

### **Mailbox Type Performance** (After CBQ Migration)
- **BLOCKING**: 149.8 → 29.4 → 15.0 ops/s (100→500→1000 actors)
- **DISPATCHER_CBQ**: 130.9 → 29.1 → 14.6 ops/s (100→500→1000 actors)
- **DISPATCHER_MPSC**: 147.5 → 29.3 → 14.8 ops/s (100→500→1000 actors)

### **Backpressure System**
- **100 actors**: 2.6% improvement with backpressure
- **200 actors**: Intelligent throttling for system protection
- **Extreme loads**: Prevents system collapse

## 🎯 Benchmark Categories

### **Performance Validation**
- Mailbox type scalability
- Backpressure effectiveness
- Actor system throughput

### **Comparison Testing**
- Actors vs Threads vs Structured Concurrency
- Different mailbox strategies
- With/without backpressure

### **Stress Testing**
- High actor counts (1000+ actors)
- Message flooding scenarios
- Resource exhaustion handling

## 📝 Notes

- All benchmarks use JMH (Java Microbenchmark Harness)
- Results include error margins and confidence intervals
- Stateful benchmarks include automatic file cleanup
- Stateless benchmarks can be run without side effects
- Each benchmark suite can be run independently

## 🔧 Building Benchmarks

```bash
./gradlew :benchmarks:jmhJar
```

This creates the executable JAR with all benchmark classes ready for execution.
