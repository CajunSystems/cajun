# Mailbox Type Benchmark Guide

## Quick Reference

```bash
# 1. Build the JAR
./gradlew :benchmarks:jmhJar

# 2. Run all mailbox benchmarks (quick test)
java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*MailboxTypeBenchmark.*" -wi 1 -i 2 -f 1

# 3. Run production-quality benchmark
java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*MailboxTypeBenchmark.*"

# 4. Test only DISPATCHER_MPSC (highest performance)
java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*MailboxTypeBenchmark.*" -p mailboxType=DISPATCHER_MPSC

# 5. Save results to JSON
java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*MailboxTypeBenchmark.*" -rf json -rff results.json
```

**Note:** `--enable-preview` is required because Cajun uses Java 21 preview features.

## Overview

The `MailboxTypeBenchmark` provides comprehensive performance comparisons between three different mailbox implementations in the Cajun actor system:

1. **BLOCKING** - Traditional thread-per-actor with `LinkedBlockingQueue`
2. **DISPATCHER_LBQ** - Dispatcher-based with `LinkedBlockingQueue`
3. **DISPATCHER_MPSC** - Dispatcher-based with lock-free JCTools `MpscArrayQueue`

## Quick Start

### Method 1: Direct JAR Execution (Recommended)

First, build the JMH JAR:

```bash
./gradlew :benchmarks:jmhJar
```

Then run specific benchmarks:

```bash
# Run all mailbox type benchmarks
java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*MailboxTypeBenchmark.*"

# Run quick test (1 warmup, 2 iterations, 1 fork)
java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*MailboxTypeBenchmark.*" -wi 1 -i 2 -f 1

# Run specific benchmark method
java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*singleActorThroughput"
```

### Method 2: Using Gradle (May Have Parameter Issues)

```bash
./gradlew :benchmarks:jmh -Pjmh.include='.*MailboxTypeBenchmark.*'
```

## Benchmark Scenarios

### 1. Single Actor Throughput
**What it measures:** Raw message processing performance with a single actor

**Good for:** Understanding baseline throughput for each mailbox type

```bash
java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*singleActorThroughput"
```

### 2. Multiple Actors Throughput
**What it measures:** Concurrent actor performance with shared dispatcher

**Good for:** Understanding how dispatcher scales with multiple actors

**Parameters:** Tests with 1 and 4 actors

```bash
java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*multipleActorsThroughput"
```

### 3. Fan-Out Pattern
**What it measures:** Broadcasting the same messages to multiple actors

**Good for:** Scheduler efficiency when many actors need servicing

```bash
java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*fanOutPattern"
```

### 4. Burst Sending Pattern
**What it measures:** Handling rapid message bursts from a single producer

**Good for:** Queue and overflow handling under pressure

```bash
java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*burstSendingPattern"
```

## Benchmark Parameters

### Message Counts
- **1,000 messages**: Quick test, lower overhead
- **10,000 messages**: Sustained load, more realistic

### Actor Counts
- **1 actor**: Single-threaded performance
- **4 actors**: Multi-actor concurrency

### Mailbox Types
- **BLOCKING**: Baseline (traditional approach)
- **DISPATCHER_LBQ**: Dispatcher with standard blocking queue
- **DISPATCHER_MPSC**: Dispatcher with lock-free queue (highest performance)

## Mailbox Configuration

Each mailbox type uses optimal settings:

### BLOCKING
```java
config.setMailboxType(MailboxType.BLOCKING)
      .setInitialCapacity(1024);
```

### DISPATCHER_LBQ
```java
config.setMailboxType(MailboxType.DISPATCHER_LBQ)
      .setInitialCapacity(1024)
      .setThroughput(64)
      .setOverflowStrategy(OverflowStrategy.BLOCK);
```

### DISPATCHER_MPSC
```java
config.setMailboxType(MailboxType.DISPATCHER_MPSC)
      .setInitialCapacity(2048)  // Power of 2 required
      .setThroughput(128)
      .setOverflowStrategy(OverflowStrategy.BLOCK);
```

## Expected Performance Characteristics

### BLOCKING (Baseline)
- ✅ **Predictable latency**
- ✅ **Simple implementation**
- ❌ Thread overhead per actor
- ❌ Context switching cost

### DISPATCHER_LBQ
- ✅ **Shared thread pool** (lower overhead)
- ✅ **Better scalability** with many actors
- ✅ **Virtual thread support**
- ⚠️ Still uses locks for queue operations

### DISPATCHER_MPSC (Highest Performance)
- ✅ **Lock-free queue** (best throughput)
- ✅ **Lowest latency** under load
- ✅ **Best multi-producer scalability**
- ⚠️ Requires power-of-2 capacity
- ⚠️ Slightly higher memory usage

## Interpreting Results

### Throughput Mode (ops/ms)
**Higher is better**

Example output:
```
Benchmark                                      (mailboxType)  Mode  Score    Error  Units
MailboxTypeBenchmark.singleActorThroughput       BLOCKING  thrpt  1234.5 ± 45.2  ops/ms
MailboxTypeBenchmark.singleActorThroughput  DISPATCHER_LBQ  thrpt  2345.6 ± 67.8  ops/ms
MailboxTypeBenchmark.singleActorThroughput DISPATCHER_MPSC  thrpt  3456.7 ± 89.0  ops/ms
```

### Average Time Mode (ms/op)
**Lower is better**

Example output:
```
Benchmark                                      (mailboxType)  Mode  Score    Error  Units
MailboxTypeBenchmark.singleActorThroughput       BLOCKING  avgt  0.810 ± 0.030  ms/op
MailboxTypeBenchmark.singleActorThroughput  DISPATCHER_LBQ  avgt  0.426 ± 0.012  ms/op
MailboxTypeBenchmark.singleActorThroughput DISPATCHER_MPSC  avgt  0.289 ± 0.009  ms/op
```

## Use Case Recommendations

### Use BLOCKING When:
- You have few actors (< 10)
- Predictable, consistent latency is critical
- Simplicity is more important than peak performance
- You don't need dispatcher features

### Use DISPATCHER_LBQ When:
- You have many actors (> 100)
- You want dispatcher benefits with standard queue
- You're transitioning from BLOCKING
- Memory efficiency is important

### Use DISPATCHER_MPSC When:
- You need **maximum throughput**
- You have **many concurrent producers**
- Lock contention is a bottleneck
- You can use power-of-2 capacity

## Advanced Usage

### Filter by Specific Parameters

Test only high message counts:
```bash
java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*MailboxTypeBenchmark.*" \
  -p messageCount=10000
```

Test only multiple actors:
```bash
java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*MailboxTypeBenchmark.*" \
  -p actorCount=4
```

Test specific mailbox type:
```bash
java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*MailboxTypeBenchmark.*" \
  -p mailboxType=DISPATCHER_MPSC
```

Combine multiple parameters:
```bash
java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*singleActorThroughput" \
  -p mailboxType=DISPATCHER_MPSC \
  -p messageCount=10000
```

### Control Iterations and Forks

Quick test (1 warmup, 2 measurements, 1 fork):
```bash
java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*MailboxTypeBenchmark.*" \
  -wi 1 -i 2 -f 1
```

Production-quality (5 warmups, 10 measurements, 3 forks):
```bash
java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*MailboxTypeBenchmark.*" \
  -wi 5 -i 10 -f 3
```

### Enable Profiling

Check garbage collection impact:
```bash
java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*MailboxTypeBenchmark.*" \
  -prof gc
```

Find hotspots:
```bash
java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*MailboxTypeBenchmark.*" \
  -prof stack
```

### Output Formats

Save results to JSON:
```bash
java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*MailboxTypeBenchmark.*" \
  -rf json -rff results.json
```

Save results to CSV:
```bash
java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*MailboxTypeBenchmark.*" \
  -rf csv -rff results.csv
```

### List Available Benchmarks

See all available benchmarks:
```bash
java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar -l
```

List only mailbox benchmarks:
```bash
java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar -l ".*MailboxTypeBenchmark.*"
```

### Get Help

Show all JMH options:
```bash
java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar -h
```

## Results Analysis

### Comparing Results

1. **Build the JAR**
   ```bash
   ./gradlew :benchmarks:jmhJar
   ```

2. **Run the benchmark**
   ```bash
   java --enable-preview -jar benchmarks/build/libs/benchmarks-jmh.jar ".*MailboxTypeBenchmark.*" \
     -rf json -rff mailbox-results.json
   ```

3. **View results**
   - Console output shows results immediately
   - JSON file: `mailbox-results.json` (in current directory)
   - Can also save to CSV: `-rf csv -rff results.csv`

4. **Look for patterns:**
   - DISPATCHER_MPSC should show **2-5x higher throughput** than BLOCKING
   - Fan-out pattern shows dispatcher scheduling efficiency
   - Multiple actors scenario shows scalability

### Key Metrics to Watch

| Metric | What It Shows |
|--------|---------------|
| **Throughput (ops/ms)** | How many messages/second each type handles |
| **Error margin (±)** | Consistency of performance |
| **Relative speedup** | DISPATCHER_MPSC vs BLOCKING ratio |
| **Scalability** | Performance with 1 vs 4 actors |

## Troubleshooting

### Benchmark Timeout
If benchmarks timeout, reduce message count:
```bash
./gradlew :benchmarks:jmh \
  -Pjmh.include='.*MailboxTypeBenchmark.*' \
  -Pjmh.params='messageCount=1000'
```

### Inconsistent Results
Run with more iterations:
```bash
./gradlew :benchmarks:jmh \
  -Pjmh.include='.*MailboxTypeBenchmark.*' \
  -Pjmh.wi=5 -Pjmh.i=10
```

### Memory Issues
Increase JVM heap:
```bash
export JAVA_OPTS="-Xmx4g"
./gradlew :benchmarks:jmh -Pjmh.include='.*MailboxTypeBenchmark.*'
```

## See Also

- [Dispatcher Usage Guide](./dispatcher_usage_guide.md)
- [Testing Dispatcher](./testing_dispatcher.md)
- [Dispatcher Integration Proposal](./dispatcher_integration_proposal.md)
- [Benchmarks README](../benchmarks/README.md)
