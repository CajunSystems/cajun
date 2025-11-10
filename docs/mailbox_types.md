# Mailbox Types in Cajun

## 📬 Overview

Cajun provides three distinct mailbox types, each optimized for different use cases and performance characteristics. The mailbox type significantly impacts actor throughput, memory usage, and concurrency patterns.

## 🏗️ Mailbox Type Architecture

All mailboxes in Cajun implement the same core interface but use different underlying data structures and scheduling strategies:

- **BLOCKING**: Traditional blocking queue implementation
- **DISPATCHER_CBQ**: Concurrent bounded queue with dispatcher scheduling
- **DISPATCHER_MPSC**: Multi-producer single-consumer queue optimized for actor patterns

## 📊 Performance Characteristics

Based on comprehensive JMH benchmarks measuring throughput (operations per second):

| Mailbox Type | Stateless Performance | Stateful Performance | Best Use Case |
|--------------|----------------------|----------------------|---------------|
| **BLOCKING** | 3,840 ops/s | 33.16 ops/s | Legacy compatibility |
| **DISPATCHER_CBQ** | 4,143 ops/s | 32.80 ops/s | **Stateless actors** 🏆 |
| **DISPATCHER_MPSC** | 4,134 ops/s | 35.04 ops/s | **Stateful actors** 🏆 |

### Performance Insights:
- **DISPATCHER_CBQ**: 7.9% better than BLOCKING for stateless actors
- **DISPATCHER_MPSC**: 21.4% better than BLOCKING for stateful actors
- **Stateless actors**: 133x faster than stateful across all mailbox types

## 🎯 Mailbox Type Details

### 1. BLOCKING Mailbox

**Implementation**: Traditional `LinkedBlockingQueue` wrapper

**Characteristics**:
- Simple, predictable behavior
- Thread-safe blocking operations
- Good for legacy integration
- Consistent but lower performance

**Configuration**:
```java
MailboxConfig config = new MailboxConfig()
    .setMailboxType(MailboxType.BLOCKING)
    .setInitialCapacity(2048)
    .setMaxCapacity(8192);
```

**Use Cases**:
- Legacy system integration
- Simple actor patterns
- When predictable blocking behavior is desired
- Development and testing scenarios

**Pros**:
- ✅ Simple and reliable
- ✅ Well-understood semantics
- ✅ Good debugging characteristics

**Cons**:
- ❌ Lowest performance
- ❌ Higher memory overhead
- ❌ Not optimized for actor patterns

---

### 2. DISPATCHER_CBQ Mailbox

**Implementation**: Concurrent bounded queue with dispatcher-based scheduling

**Characteristics**:
- Optimized for concurrent access patterns
- Dispatcher-based message processing
- Balanced performance for mixed workloads
- Best for stateless actors

**Configuration**:
```java
MailboxConfig config = new MailboxConfig()
    .setMailboxType(MailboxType.DISPATCHER_CBQ)
    .setMaxCapacity(16384)
    .setThroughput(128);
```

**Use Cases**:
- **Stateless actors requiring maximum throughput**
- High-volume message routing
- Multi-producer environments
- Pipeline and workflow scenarios

**Pros**:
- ✅ **Best performance for stateless actors** (4,143 ops/s)
- ✅ Optimized for concurrent access
- ✅ Good for mixed workload patterns
- ✅ Dispatcher-based scheduling efficiency

**Cons**:
- ❌ Slightly higher complexity
- ❌ Requires capacity planning
- ❌ Not optimal for single-producer scenarios

---

### 3. DISPATCHER_MPSC Mailbox

**Implementation**: Multi-producer single-consumer queue optimized for actor patterns

**Characteristics**:
- Designed for actor message patterns
- Lock-free operations where possible
- Optimized for single-producer scenarios
- Best for stateful actors

**Configuration**:
```java
MailboxConfig config = new MailboxConfig()
    .setMailboxType(MailboxType.DISPATCHER_MPSC)
    .setMaxCapacity(16384)  // Power of 2 for optimal performance
    .setThroughput(256);
```

**Use Cases**:
- **Stateful actors with optimal performance** (35.04 ops/s)
- Single-producer message patterns
- Event sourcing and CQRS
- State machine implementations

**Pros**:
- ✅ **Best performance for stateful actors** (35.04 ops/s)
- ✅ Optimized for common actor patterns
- ✅ Efficient memory usage
- ✅ Lock-free operations

**Cons**:
- ❌ Optimized for specific patterns
- ❌ Power-of-2 capacity requirements
- ❌ Less flexible than CBQ

## 🛠️ Configuration Guidelines

### Capacity Planning

**DISPATCHER_CBQ**:
- Start with `maxCapacity = 16384`
- Increase for high-volume scenarios
- Monitor memory usage

**DISPATCHER_MPSC**:
- Use power-of-2 capacities: 1024, 2048, 4096, 8192, 16384
- Start with `maxCapacity = 16384`
- Adjust based on message burst patterns

**BLOCKING**:
- Conservative capacities: `initialCapacity = 2048`, `maxCapacity = 8192`
- Good for predictable workloads

### Throughput Settings

**High Throughput** (CPU-intensive work):
```java
.setThroughput(256)  // DISPATCHER_MPSC
.setThroughput(128)  // DISPATCHER_CBQ
```

**Balanced Throughput** (mixed workloads):
```java
.setThroughput(64)   // DISPATCHER_MPSC
.setThroughput(32)   // DISPATCHER_CBQ
```

**Low Throughput** (I/O-intensive work):
```java
.setThroughput(16)   // Both dispatcher types
```

## 🎯 Decision Framework

### Choose BLOCKING when:
- ✅ Integrating with legacy systems
- ✅ Simple debugging is priority
- ✅ Predictable blocking behavior needed
- ✅ Development and testing

### Choose DISPATCHER_CBQ when:
- ✅ **Stateless actors need maximum performance**
- ✅ Multiple producers sending to same actor
- ✅ Pipeline and workflow patterns
- ✅ Mixed concurrent access patterns

### Choose DISPATCHER_MPSC when:
- ✅ **Stateful actors need optimal performance**
- ✅ Single-producer scenarios
- ✅ Event sourcing and CQRS patterns
- ✅ Memory efficiency is important

## 📈 Performance Optimization Tips

### 1. Right-Size Your Mailboxes
- Too small: Frequent blocking, reduced throughput
- Too large: Memory waste, potential GC pressure
- Monitor queue sizes in production

### 2. Match Mailbox to Actor Type
- **Stateless + DISPATCHER_CBQ**: Best overall performance
- **Stateful + DISPATCHER_MPSC**: Best for stateful scenarios
- Avoid mismatching (e.g., stateful with CBQ)

### 3. Consider Backpressure
- Use BASIC backpressure for optimal balance
- Monitor queue depths and memory usage
- Adjust based on production patterns

### 4. Profile Your Workload
- Measure actual throughput in your environment
- Test different configurations
- Consider message size and processing time

## 🔧 Implementation Examples

### High-Performance Stateless Actor
```java
MailboxConfig config = new MailboxConfig()
    .setMailboxType(MailboxType.DISPATCHER_CBQ)
    .setMaxCapacity(16384)
    .setThroughput(128);

ActorSystem system = new ActorSystem(
    new ThreadPoolFactory(), 
    null, 
    config, 
    new DefaultMailboxProvider<>()
);

Pid actor = system.actorOf(StatelessHandler.class)
    .withId("high-performance-router")
    .withBackpressureConfig(new BackpressureConfig()
        .setHighWatermark(0.8f)
        .setLowWatermark(0.2f))
    .spawn();
```

### Optimized Stateful Actor
```java
MailboxConfig config = new MailboxConfig()
    .setMailboxType(MailboxType.DISPATCHER_MPSC)
    .setMaxCapacity(16384)  // Power of 2
    .setThroughput(256);

ActorSystem system = new ActorSystem(
    new ThreadPoolFactory(), 
    null, 
    config, 
    new DefaultMailboxProvider<>()
);

Pid actor = system.statefulActorOf(StatefulHandler.class, initialState)
    .withId("event-sourced-actor")
    .withBackpressureConfig(new BackpressureConfig()
        .setHighWatermark(0.7f)
        .setLowWatermark(0.1f))
    .spawn();
```

## 📊 Monitoring and Metrics

Cajun provides built-in metrics for mailbox performance:

- **Queue Depth**: Current number of pending messages
- **Throughput**: Messages processed per second
- **Latency**: Time from send to receive
- **Memory Usage**: Heap consumption by mailbox

Monitor these metrics to:
- Detect mailbox saturation
- Identify performance bottlenecks
- Optimize capacity settings
- Plan scaling decisions

## 🚀 Best Practices

1. **Start with DISPATCHER_CBQ for stateless, DISPATCHER_MPSC for stateful**
2. **Use power-of-2 capacities for MPSC mailboxes**
3. **Monitor queue depths in production**
4. **Configure backpressure to prevent memory issues**
5. **Profile actual workloads, not just benchmarks**
6. **Consider message size when setting capacities**
7. **Use appropriate throughput settings for your workload**

## 📚 Related Documentation

- [Performance Optimization Guide](performance_takeaways.md)
- [Backpressure Configuration](backpressure_configuration.md)
- [Actor System Configuration](actor_system_configuration.md)
- [Benchmark Results](../README.md#benchmarks)
