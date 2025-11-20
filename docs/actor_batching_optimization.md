# Actor Batching Optimization Guide

## Overview

The Cajun actor framework includes a powerful **mailbox batching** feature that significantly improves throughput for high-volume message processing scenarios. This guide explains how it works and how to leverage it in your benchmarks and applications.

## How Actor Batching Works

### Internal Mechanism

Each actor's `MailboxProcessor` operates in a processing loop that:

1. **Polls** for the first message from the mailbox (with 1ms timeout)
2. **Drains** additional messages up to `batchSize - 1` from the mailbox
3. **Processes** all messages in the batch sequentially
4. **Repeats** the cycle

```java
// From MailboxProcessor.java
while (running) {
    batchBuffer.clear();
    T first = mailbox.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    if (first == null) continue;
    
    batchBuffer.add(first);
    if (batchSize > 1) {
        mailbox.drainTo(batchBuffer, batchSize - 1);  // Batch optimization!
    }
    
    for (T msg : batchBuffer) {
        lifecycle.receive(msg);  // Process each message
    }
}
```

### Benefits

**Reduced Context Switching:**
- Without batching (batchSize=1): Actor processes 1 message, then polls again
- With batching (batchSize=50): Actor processes up to 50 messages before polling
- Result: Up to 50x fewer poll operations and virtual thread park/unpark cycles

**Improved Cache Locality:**
- Sequential message processing keeps actor state in CPU cache
- Better instruction cache utilization
- Fewer memory barriers

**Lower Overhead:**
- Amortizes the cost of thread scheduling across multiple messages
- Reduces mailbox synchronization overhead

## Configuration

### Default Settings

```java
// Default batch size
private static final int DEFAULT_BATCH_SIZE = 10;  // in Actor.java
```

### Custom Batch Size

Configure batch size using `ThreadPoolFactory`:

```java
// Create a factory with custom batch size
ThreadPoolFactory factory = new ThreadPoolFactory()
    .setActorBatchSize(50);  // Process up to 50 messages per batch

// Create actor with custom batching
Pid actor = actorSystem.actorOf(MyHandler.class)
    .withId("batch-optimized-actor")
    .withThreadPoolFactory(factory)
    .spawn();
```

### Choosing the Right Batch Size

| Batch Size | Use Case | Pros | Cons |
|------------|----------|------|------|
| **1** | Interactive, low-latency | Minimum latency per message | High overhead for throughput |
| **10** (default) | General purpose | Good balance | May not maximize throughput |
| **50-100** | High-throughput batch processing | Minimal overhead, max throughput | Higher latency for individual messages |
| **500+** | Extreme bulk processing | Maximum throughput | Very high latency, memory pressure |

**Rule of Thumb:**
- **Latency-sensitive**: Use batch size 1-10
- **Throughput-optimized**: Use batch size 50-100
- **Bulk data processing**: Use batch size 100-500

## Benchmark Example

### Scenario: Processing 100 Messages

```java
@Benchmark
@OperationsPerInvocation(WORKLOAD_SIZE)  // 100 operations
public long batchProcessing_Actors_BatchOptimized() throws Exception {
    CompletableFuture<Long>[] futures = new CompletableFuture[WORKLOAD_SIZE];

    // Send all 100 messages
    for (int i = 0; i < WORKLOAD_SIZE; i++) {
        futures[i] = new CompletableFuture<>();
        batchOptimizedWorkers[i].tell(new WorkMessage.BatchProcess(futures[i]));
    }

    // Collect results
    long sum = 0;
    for (CompletableFuture<Long> future : futures) {
        sum += future.get(10, TimeUnit.SECONDS);
    }
    return sum;
}
```

### Expected Performance Impact

**Without Batching (batchSize=1):**
- 100 messages = 100 poll operations
- Each message triggers: poll → process → poll → process
- Overhead: ~1-2µs per message for mailbox operations

**With Batching (batchSize=50):**
- 100 messages = ~2-3 poll operations (50+50 messages)
- Processing pattern: poll → process 50 → poll → process 50
- Overhead: ~0.02-0.04µs per message for mailbox operations

**Theoretical Speedup:** 25-50x reduction in mailbox overhead

**Realistic Speedup:** 2-3x improvement (when work dominates over overhead)

## Real-World Applications

### 1. Event Stream Processing

```java
// High-throughput event processor
ThreadPoolFactory eventProcessorFactory = new ThreadPoolFactory()
    .setActorBatchSize(100);

Pid eventProcessor = system.actorOf(EventHandler.class)
    .withId("event-stream-processor")
    .withThreadPoolFactory(eventProcessorFactory)
    .spawn();

// Can process thousands of events efficiently
for (Event event : eventStream) {
    eventProcessor.tell(new ProcessEvent(event));
}
```

### 2. Database Batch Writes

```java
public class DatabaseWriterHandler implements Handler<WriteCommand> {
    private final List<WriteCommand> batch = new ArrayList<>();
    
    @Override
    public void receive(WriteCommand cmd, ActorContext context) {
        batch.add(cmd);
        
        // Actor batching naturally accumulates messages
        // When we receive a batch, write them all at once
        if (batch.size() >= 10) {
            database.batchWrite(batch);
            batch.clear();
        }
    }
}

// Configure with batch size matching database batch size
ThreadPoolFactory dbFactory = new ThreadPoolFactory()
    .setActorBatchSize(10);
```

### 3. Message Queue Consumer

```java
// Consume from Kafka/RabbitMQ in batches
ThreadPoolFactory consumerFactory = new ThreadPoolFactory()
    .setActorBatchSize(200);  // Match typical message queue batch size

Pid consumer = system.actorOf(MessageConsumerHandler.class)
    .withThreadPoolFactory(consumerFactory)
    .spawn();
```

## Performance Characteristics

### Latency vs Throughput Trade-off

```
Latency (per message):
    batchSize=1:    Low  (~1-2ms)
    batchSize=50:   Medium (~5-10ms)
    batchSize=200:  High (~20-50ms)

Throughput (messages/sec):
    batchSize=1:    ~1,000-5,000
    batchSize=50:   ~50,000-100,000
    batchSize=200:  ~200,000-500,000
```

### When Batching Helps Most

✅ **High message volume** (>1000 messages/sec per actor)
✅ **CPU-light processing** (overhead dominates work time)
✅ **Bursty traffic** (periods of high message arrival rate)
✅ **Sequential processing acceptable** (no need for parallel execution)

### When Batching Helps Less

⚠️ **Low message volume** (<100 messages/sec per actor)
⚠️ **CPU-heavy processing** (work dominates overhead)
⚠️ **Strict latency requirements** (<1ms response time)
⚠️ **Interactive request-reply** (users waiting for response)

## Comparison with Thread Pools

### Actors with Batching
```
Pros:
+ State encapsulation (thread-safe by design)
+ Built-in backpressure and mailbox management
+ Supervision and fault tolerance
+ Natural batching at mailbox level

Cons:
- Still some message passing overhead
- Sequential processing within an actor
- Latency increases with batch size
```

### Thread Pools
```
Pros:
+ Minimal overhead for task submission
+ True parallel execution
+ Lower latency per task

Cons:
- No built-in state management
- Manual synchronization required
- No backpressure mechanism
- No fault tolerance
```

## Best Practices

### 1. Profile First
```java
// Start with default batch size
Pid actor = system.actorOf(Handler.class).spawn();

// Measure throughput and latency
// Adjust batch size based on results
```

### 2. Match Batch Size to Workload
```java
// For interactive requests (low latency)
factory.setActorBatchSize(1);

// For background processing (high throughput)
factory.setActorBatchSize(100);
```

### 3. Monitor Mailbox Depth
```java
// If mailbox consistently fills up, increase batch size
int mailboxSize = actor.getCurrentSize();
if (mailboxSize > 100) {
    // Consider increasing batch size
}
```

### 4. Combine with Mailbox Configuration
```java
ThreadPoolFactory factory = new ThreadPoolFactory()
    .setActorBatchSize(50);

ResizableMailboxConfig mailboxConfig = new ResizableMailboxConfig()
    .setInitialCapacity(1000)
    .setMaxCapacity(10000);

Pid actor = system.actorOf(Handler.class)
    .withThreadPoolFactory(factory)
    .withMailboxConfig(mailboxConfig)
    .spawn();
```

### 5. Consider Actor Pool for Parallelism
```java
// Instead of one actor with huge batch size,
// use multiple actors with moderate batch size
ThreadPoolFactory factory = new ThreadPoolFactory()
    .setActorBatchSize(50);

int numActors = Runtime.getRuntime().availableProcessors();
Pid[] actorPool = new Pid[numActors];

for (int i = 0; i < numActors; i++) {
    actorPool[i] = system.actorOf(Handler.class)
        .withThreadPoolFactory(factory)
        .spawn();
}

// Round-robin message distribution
int next = 0;
for (Message msg : messages) {
    actorPool[next++ % numActors].tell(msg);
}
```

## Monitoring and Tuning

### Key Metrics

1. **Processing Rate** (messages/sec)
   - Measure: `actor.getProcessingRate()`
   - Target: Maximize for batch workloads

2. **Mailbox Depth**
   - Measure: `actor.getCurrentSize()`
   - Target: Keep low to avoid memory pressure

3. **Message Latency** (time from send to process)
   - Measure: Timestamp in message
   - Target: Meet SLA requirements

4. **CPU Utilization**
   - Measure: OS tools (top, htop)
   - Target: High for CPU-bound work

### Tuning Process

```
1. Start with default (batchSize=10)
2. Run load test and measure metrics
3. If throughput low and CPU low → increase batch size
4. If latency high → decrease batch size
5. If mailbox fills up → increase batch size or add actors
6. Iterate until optimal
```

## Conclusion

Actor batching is a powerful optimization for high-throughput scenarios. The key is to:

1. **Understand your workload** (latency vs throughput requirements)
2. **Configure appropriately** (match batch size to traffic patterns)
3. **Monitor metrics** (track throughput, latency, mailbox depth)
4. **Iterate and tune** (adjust based on real-world performance)

When used correctly, batching can make actors competitive with or even superior to raw thread pools for certain workloads, while maintaining the benefits of actor model abstractions (state encapsulation, fault tolerance, backpressure).

