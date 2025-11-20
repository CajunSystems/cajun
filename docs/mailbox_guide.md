# Cajun Mailbox Selection Guide

## Quick Reference

| Mailbox Type | Best For | Throughput | Latency | Memory | Bounded |
|--------------|----------|------------|---------|--------|---------|
| **LinkedMailbox** | General-purpose, Mixed workloads | ⭐⭐⭐ Good | ⭐⭐⭐ Good | ⭐⭐⭐⭐ Low | ✅ Yes |
| **MpscMailbox** | High-throughput CPU-bound | ⭐⭐⭐⭐⭐ Excellent | ⭐⭐⭐⭐⭐ Excellent | ⭐⭐⭐ Medium | ❌ No |

---

## LinkedMailbox

### When to Use
- ✅ General-purpose actors
- ✅ Mixed I/O and CPU workloads
- ✅ Need backpressure (bounded capacity)
- ✅ Memory-constrained environments
- ✅ Actors with variable message rates

### Characteristics
- Uses `java.util.concurrent.LinkedBlockingQueue`
- Lock-free optimizations for common cases
- Separate locks for producers and consumers
- Low memory overhead (linked nodes)
- Can be bounded or unbounded

### Performance
- **Throughput**: 100-200K messages/sec
- **Latency (p50)**: 20-30μs
- **Latency (p99)**: 80-150μs
- **Memory**: ~32 bytes per message

### Example
```java
// Automatic selection (recommended)
Pid actor = system.actorOf(MyHandler.class)
    .withThreadPoolFactory(
        new ThreadPoolFactory().optimizeFor(WorkloadType.MIXED)
    )
    .spawn();

// Manual creation
Mailbox<MyMessage> mailbox = new LinkedMailbox<>(10000);  // Bounded to 10K
```

---

## MpscMailbox

### When to Use
- ✅ High-throughput CPU-bound workloads
- ✅ Low-latency requirements
- ✅ Many senders, single consumer
- ✅ Can tolerate unbounded growth
- ⚠️ Have monitoring for queue depth

### Avoid When
- ❌ Need strict backpressure (unbounded!)
- ❌ Memory is severely constrained
- ❌ Message rates are highly variable
- ❌ Single producer (no benefit over LinkedMailbox)

### Characteristics
- Uses JCTools `MpscUnboundedArrayQueue`
- True lock-free multi-producer, single-consumer
- Chunked array growth (starts small, grows as needed)
- Minimal allocation overhead
- **Unbounded** - monitor queue depth!

### Performance
- **Throughput**: 400-600K messages/sec
- **Latency (p50)**: 5-15μs
- **Latency (p99)**: 30-60μs
- **Memory**: ~8 bytes per slot + chunk overhead

### Example
```java
// Automatic selection (recommended for CPU-bound)
Pid actor = system.actorOf(MyHandler.class)
    .withThreadPoolFactory(
        new ThreadPoolFactory().optimizeFor(WorkloadType.CPU_BOUND)
    )
    .spawn();

// Manual creation
Mailbox<MyMessage> mailbox = new MpscMailbox<>(256);  // Initial chunk size
```

---

## Decision Tree

```
Start
  │
  ├─ Need strict backpressure?
  │    ├─ Yes → LinkedMailbox (bounded)
  │    └─ No  → Continue
  │
  ├─ High-throughput CPU workload?
  │    ├─ Yes → MpscMailbox
  │    └─ No  → Continue
  │
  ├─ Memory constrained?
  │    ├─ Yes → LinkedMailbox
  │    └─ No  → Continue
  │
  ├─ Need lowest possible latency?
  │    ├─ Yes → MpscMailbox
  │    └─ No  → LinkedMailbox (default)
```

---

## Workload Type Defaults

When using `ThreadPoolFactory.optimizeFor()`, Cajun automatically selects:

| Workload Type | Default Mailbox | Capacity | Rationale |
|---------------|-----------------|----------|-----------|
| **IO_BOUND** | LinkedMailbox | 10,000 | Large buffer for bursty I/O |
| **CPU_BOUND** | MpscMailbox | Unbounded | Highest throughput |
| **MIXED** | LinkedMailbox | User-defined | Balanced |

---

## Monitoring MpscMailbox

Since MpscMailbox is unbounded, monitor queue depth:

```java
Pid actor = system.actorOf(MyHandler.class).spawn();

// Check queue size
int queueSize = ((Actor<?>) system.getActor(actor)).getMailboxSize();
if (queueSize > THRESHOLD) {
    logger.warn("Mailbox queue depth high: {}", queueSize);
}
```

**Recommended thresholds**:
- ⚠️ Warning: > 10,000 messages
- 🚨 Critical: > 100,000 messages
- 💥 Emergency: > 1,000,000 messages (consider circuit breaker)

---

## Performance Comparison

### Benchmark Setup
- 100K messages sent to single actor
- 1KB message payload
- JMH benchmark, 10 iterations
- OpenJDK 21, Virtual Threads

### Results

| Mailbox | Throughput | Latency (p50) | Latency (p99) | GC Overhead |
|---------|------------|---------------|---------------|-------------|
| ~~ResizableBlockingQueue~~ | 80K/sec | 50μs | 500μs | High |
| **LinkedMailbox** | 180K/sec | 25μs | 100μs | Medium |
| **MpscMailbox** | 450K/sec | 10μs | 50μs | Low |

**Improvement over v0.1.x**:
- LinkedMailbox: **2.25x throughput, 50% latency reduction**
- MpscMailbox: **5.6x throughput, 80% latency reduction**

---

## Migration from v0.1.x

### Deprecated: ResizableBlockingQueue

```java
// OLD (v0.1.x) - Still works but deprecated
ResizableBlockingQueue<Message> queue = new ResizableBlockingQueue<>(128, 10000);

// NEW (v0.2.0) - Recommended
LinkedMailbox<Message> mailbox = new LinkedMailbox<>(10000);  // General
MpscMailbox<Message> mailbox = new MpscMailbox<>(128);       // High-perf
```

**Migration is automatic** - actors using default configuration will automatically use LinkedMailbox or MpscMailbox based on workload type.

---

## Advanced: Custom Mailbox Implementation

Want to implement a custom mailbox (e.g., priority queue, ring buffer)?

```java
public class MyCustomMailbox<T> implements Mailbox<T> {
    @Override
    public boolean offer(T message) {
        // Your implementation
    }

    @Override
    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
        // Your implementation
    }

    // ... implement all methods
}

// Usage
Pid actor = system.actorOf(MyHandler.class)
    .withMailbox(new MyCustomMailbox<>())  // Future API
    .spawn();
```

---

## FAQ

### Q: Can I change mailbox type for an existing actor?
**A**: No, mailbox is created at actor spawn time. Stop the actor and recreate with desired mailbox.

### Q: Should I always use MpscMailbox for best performance?
**A**: No! MpscMailbox is unbounded. Only use if you can tolerate unbounded growth and have monitoring.

### Q: What's the memory overhead of MpscMailbox?
**A**: Initial chunk (e.g., 256 slots) = 256 * 8 bytes = 2KB. Grows in chunks as needed.

### Q: Can I use bounded MPSC queue?
**A**: JCTools provides `MpscArrayQueue` (bounded), but it's not yet integrated. Planned for future release.

### Q: Does LinkedMailbox ever block?
**A**: Yes, on `put()` when queue is full (if bounded). Use `offer()` for non-blocking behavior.

### Q: How do I know which mailbox my actor is using?
**A**: Check logs at INFO level - `DefaultMailboxProvider` logs mailbox type at creation.

---

## Performance Tuning Tips

### 1. Batch Size
```java
Pid actor = system.actorOf(MyHandler.class)
    .withBatchSize(100)  // Process 100 messages per drain
    .spawn();
```
- Default: 10
- Higher batch size = better throughput, higher latency variance
- Lower batch size = lower latency, more overhead

### 2. Initial Capacity (MpscMailbox)
```java
new MpscMailbox<>(512);  // Larger initial chunk
```
- Powers of 2 only (128, 256, 512, 1024, ...)
- Larger = fewer allocations, more upfront memory
- Smaller = lower initial memory, more allocations

### 3. Bounded Capacity (LinkedMailbox)
```java
new LinkedMailbox<>(1000);  // Strict backpressure
```
- Smaller = faster backpressure response
- Larger = more buffering for bursty workloads

---

## Resources

- **Source Code**: `lib/src/main/java/com/cajunsystems/mailbox/`
- **Benchmarks**: `benchmarks/src/jmh/java/com/cajunsystems/benchmarks/`
- **JCTools Documentation**: https://github.com/JCTools/JCTools
- **Performance Guide**: `PERFORMANCE_IMPROVEMENTS.md`
