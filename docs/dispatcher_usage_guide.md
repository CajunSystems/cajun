# Dispatcher Usage Guide

## Overview

Cajun now supports dispatcher-style threading as an alternative to the traditional thread-per-actor model. This provides better scalability and performance for applications with many actors.

## Mailbox Types

### BLOCKING (Default - Traditional)
Thread-per-actor model with dedicated thread for each actor.
```java
// Default - no configuration needed
ActorSystem system = new ActorSystem();
Pid actor = system.actorOf(MyHandler.class).spawn();
```

### DISPATCHER_LBQ (Dispatcher + LinkedBlockingQueue)
Actors share a virtual thread pool, using LinkedBlockingQueue for mailboxes.
```java
MailboxConfig config = new MailboxConfig()
    .setMailboxType(MailboxType.DISPATCHER_LBQ)
    .setThroughput(64);  // Messages per activation

ActorSystem system = new ActorSystem(
    new ThreadPoolFactory(),
    null,  // no backpressure
    config,
    new DefaultMailboxProvider<>()
);

Pid actor = system.actorOf(MyHandler.class).spawn();
```

### DISPATCHER_MPSC (Dispatcher + Lock-Free Queue)
Highest performance option using JCTools MpscArrayQueue.
```java
MailboxConfig config = new MailboxConfig()
    .setMailboxType(MailboxType.DISPATCHER_MPSC)
    .setInitialCapacity(4096)  // Must be power of 2
    .setThroughput(128)
    .setOverflowStrategy(OverflowStrategy.BLOCK);

ActorSystem system = new ActorSystem(
    new ThreadPoolFactory(),
    null,
    config,
    new DefaultMailboxProvider<>()
);

Pid actor = system.actorOf(MyHandler.class).spawn();
```

## Configuration Options

### Throughput (Batch Size)
Controls how many messages an actor processes before yielding to other actors.
- **Lower (16-32)**: Better latency, more fair scheduling
- **Higher (64-128)**: Better throughput, less scheduling overhead
- **Default**: 64

```java
config.setThroughput(128);
```

### Overflow Strategy
What happens when the mailbox is full.
- **BLOCK**: Sender blocks until space available (backpressure-friendly)
- **DROP**: Message is dropped (non-blocking, lossy)
- **Default**: BLOCK

```java
config.setOverflowStrategy(OverflowStrategy.DROP);
```

### Capacity
Maximum number of messages in the mailbox.
- For DISPATCHER_MPSC: **Must be power of 2** (e.g., 1024, 2048, 4096)
- For DISPATCHER_LBQ: Any positive integer

```java
config.setMaxCapacity(4096);
```

## Per-Actor Configuration

You can configure dispatcher mode per actor using the builder:

```java
ActorSystem system = new ActorSystem();

// Actor 1: Traditional blocking
Pid actor1 = system.actorOf(Handler1.class).spawn();

// Actor 2: Dispatcher with LBQ
MailboxConfig config2 = new MailboxConfig()
    .setMailboxType(MailboxType.DISPATCHER_LBQ)
    .setThroughput(64);
    
Pid actor2 = system.actorOf(Handler2.class)
    .withMailboxConfig(config2)
    .spawn();

// Actor 3: Dispatcher with MPSC (high throughput)
MailboxConfig config3 = new MailboxConfig()
    .setMailboxType(MailboxType.DISPATCHER_MPSC)
    .setInitialCapacity(2048)
    .setThroughput(128);
    
Pid actor3 = system.actorOf(Handler3.class)
    .withMailboxConfig(config3)
    .spawn();
```

## Performance Characteristics

### Traditional BLOCKING Mode
- **Latency**: ~1-2ms (after Phase 1 optimization)
- **Throughput**: Moderate
- **Scalability**: Limited by OS thread count
- **Use case**: Simple applications, predictable behavior

### DISPATCHER_LBQ Mode
- **Latency**: ~1-2ms
- **Throughput**: 2-3x better than BLOCKING
- **Scalability**: Excellent (virtual threads)
- **Use case**: Many actors, balanced workload

### DISPATCHER_MPSC Mode
- **Latency**: ~0.5-1ms (lock-free)
- **Throughput**: 5-10x better than BLOCKING
- **Scalability**: Excellent
- **Use case**: High-throughput scenarios, many producers

## Example: High-Throughput Actor

```java
import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.config.MailboxConfig;
import com.cajunsystems.dispatcher.MailboxType;
import com.cajunsystems.dispatcher.OverflowStrategy;
import com.cajunsystems.handler.Handler;

public class HighThroughputExample {
    
    static class MessageProcessor implements Handler<String> {
        @Override
        public void handle(String message) {
            // Process message
            System.out.println("Processing: " + message);
        }
    }
    
    public static void main(String[] args) {
        // Configure for high throughput
        MailboxConfig config = new MailboxConfig()
            .setMailboxType(MailboxType.DISPATCHER_MPSC)
            .setInitialCapacity(8192)  // Power of 2
            .setThroughput(256)
            .setOverflowStrategy(OverflowStrategy.BLOCK);
        
        ActorSystem system = new ActorSystem(
            new ThreadPoolFactory(),
            null,
            config,
            new DefaultMailboxProvider<>()
        );
        
        // Spawn high-throughput actor
        Pid processor = system.actorOf(MessageProcessor.class).spawn();
        
        // Send many messages efficiently
        for (int i = 0; i < 1_000_000; i++) {
            processor.tell("Message " + i);
        }
        
        // Cleanup
        system.shutdown();
    }
}
```

## Migration Guide

### From Traditional to Dispatcher

1. **No code changes needed** - default remains BLOCKING mode
2. **Opt-in per actor** - configure via MailboxConfig
3. **Test incrementally** - start with DISPATCHER_LBQ, then try DISPATCHER_MPSC

### Recommended Migration Path

1. **Baseline**: Measure current performance with BLOCKING mode
2. **Phase 1**: Try DISPATCHER_LBQ on high-message-volume actors
3. **Phase 2**: Switch to DISPATCHER_MPSC for highest throughput needs
4. **Tune**: Adjust throughput and capacity based on workload

## Troubleshooting

### "Capacity must be power of 2" Error
**Cause**: DISPATCHER_MPSC requires power-of-2 capacity
**Fix**: Use 1024, 2048, 4096, 8192, etc.

```java
// Bad
config.setInitialCapacity(5000);

// Good
config.setInitialCapacity(4096);
```

### Messages Being Dropped
**Cause**: Mailbox full with OverflowStrategy.DROP
**Fix**: 
- Increase capacity
- Switch to OverflowStrategy.BLOCK
- Implement backpressure

### High Latency with Dispatcher
**Cause**: Throughput too high, actors monopolizing scheduler
**Fix**: Reduce throughput to 16-32 for latency-sensitive actors

```java
config.setThroughput(16);  // Lower for better latency
```

## Best Practices

1. **Start simple**: Use default BLOCKING mode initially
2. **Profile first**: Measure before optimizing
3. **Incremental adoption**: Migrate high-volume actors first
4. **Tune throughput**: Balance latency vs throughput needs
5. **Monitor mailbox size**: Watch for backlog growth
6. **Use power-of-2 capacities**: For MPSC queues
7. **Test under load**: Verify performance improvements

## Performance Tuning Tips

### For Low Latency
- Use throughput: 16-32
- Smaller mailbox capacity
- DISPATCHER_LBQ is sufficient

### For High Throughput
- Use throughput: 128-256
- Larger mailbox capacity (8192-16384)
- Use DISPATCHER_MPSC

### For Many Actors
- Dispatcher modes scale better
- Virtual threads handle millions of actors
- Monitor overall memory usage

## See Also

- [Dispatcher Integration Proposal](./dispatcher_integration_proposal.md)
- [Performance Optimizations](../performance_optimizations.md)
- [Backpressure System](./backpressure_system.md)
