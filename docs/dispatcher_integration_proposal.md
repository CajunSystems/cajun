# Dispatcher Integration Proposal for Cajun

## Executive Summary

This proposal outlines a **minimal, non-invasive** approach to integrate dispatcher-style threading with configurable mailbox types (LinkedBlockingQueue and JCTools MpscArrayQueue) into the Cajun actor system.

## Current Architecture Analysis

### Key Components
- **Actor**: Core actor class with mailbox and message processing
- **MailboxProcessor**: Handles message polling with 100ms timeout in a blocking loop
- **MailboxProvider**: Factory for creating mailbox implementations
- **ActorSystem**: Manages actor lifecycle and system-wide configuration

### Current Limitations
1. **Blocking mailbox processor**: Uses `poll(100ms)` causing high latency
2. **Thread-per-actor model**: Each actor has dedicated thread via MailboxProcessor
3. **No dispatcher pattern**: Cannot share thread pool efficiently across actors
4. **Limited queue options**: Only LinkedBlockingQueue and ArrayBlockingQueue

## Proposed Solution

### Design Principles
1. **Backward compatibility**: Existing actors continue to work
2. **Opt-in model**: New dispatcher mode is configurable
3. **Non-invasive**: Minimal changes to core Actor class
4. **Flexible queues**: Support both LinkedBlockingQueue (default) and MpscArrayQueue (high throughput)

### Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                      ActorSystem                            │
│  ┌──────────────────────────────────────────────────────┐   │
│  │           Dispatcher (Virtual Thread Pool)           │   │
│  │  - schedules ActorRunner tasks                      │   │
│  │  - uses virtual threads by default                  │   │
│  └──────────────────────────────────────────────────────┘   │
│                                                              │
│  Actor 1           Actor 2           Actor 3                │
│  ┌────────┐       ┌────────┐       ┌────────┐              │
│  │Mailbox │       │Mailbox │       │Mailbox │              │
│  │MPSC/LBQ│       │MPSC/LBQ│       │MPSC/LBQ│              │
│  └────┬───┘       └────┬───┘       └────┬───┘              │
│       │                │                │                   │
│  ┌────▼─────┐     ┌────▼─────┐    ┌────▼─────┐            │
│  │Runner    │     │Runner    │    │Runner    │            │
│  │(batch=64)│     │(batch=64)│    │(batch=64)│            │
│  └──────────┘     └──────────┘    └──────────┘            │
└─────────────────────────────────────────────────────────────┘
```

## Implementation Plan

### Phase 1: Infrastructure (Non-Breaking)

#### 1.1 Add JCTools Dependency
**File**: `gradle/libs.versions.toml`
```toml
jctools = "4.0.5"
```

**File**: `lib/build.gradle`
```gradle
implementation libs.jctools
```

#### 1.2 Create Dispatcher Class
**New File**: `lib/src/main/java/com/cajunsystems/dispatcher/Dispatcher.java`
- Virtual thread-based executor (default)
- Fixed thread pool fallback option
- Simple `schedule(Runnable)` API

#### 1.3 Create DispatcherMailbox Interface
**New File**: `lib/src/main/java/com/cajunsystems/dispatcher/DispatcherMailbox.java`
- Wraps either LinkedBlockingQueue or MpscArrayQueue
- Implements coalesced scheduling (scheduled flag pattern)
- Non-blocking enqueue with overflow strategies (BLOCK/DROP)

#### 1.4 Create ActorRunner
**New File**: `lib/src/main/java/com/cajunsystems/dispatcher/ActorRunner.java`
- Processes up to `throughput` messages per activation
- Re-schedules if more messages remain
- Batch processing (default: 64 messages)

### Phase 2: Integration (Opt-In)

#### 2.1 MailboxConfig Enhancement
**File**: `lib/src/main/java/com/cajunsystems/config/MailboxConfig.java`
- Add `mailboxType` field: `BLOCKING` (default), `DISPATCHER_LBQ`, `DISPATCHER_MPSC`
- Add `throughput` field (default: 64)
- Add `overflowStrategy` field: `BLOCK` (default), `DROP`

#### 2.2 Update DefaultMailboxProvider
**File**: `lib/src/main/java/com/cajunsystems/config/DefaultMailboxProvider.java`
- Detect `MailboxConfig.mailboxType`
- Return appropriate mailbox implementation
- For dispatcher types, return `DispatcherMailbox` wrapper

#### 2.3 Create DispatcherMailboxProcessor
**New File**: `lib/src/main/java/com/cajunsystems/dispatcher/DispatcherMailboxProcessor.java`
- Implements same interface as `MailboxProcessor`
- Uses ActorRunner + Dispatcher instead of dedicated thread
- Delegates to ActorRunner for batch processing

#### 2.4 Update Actor Constructor
**File**: `lib/src/main/java/com/cajunsystems/Actor.java`
- Detect if mailbox is dispatcher-enabled (via config or mailbox type)
- Create `DispatcherMailboxProcessor` instead of `MailboxProcessor`
- Pass system-level dispatcher instance

### Phase 3: Performance Optimizations

#### 3.1 Reduce Mailbox Polling Timeout
**File**: `lib/src/main/java/com/cajunsystems/MailboxProcessor.java` (line 178)
```java
// Change from:
T first = mailbox.poll(100, TimeUnit.MILLISECONDS);
// To:
T first = mailbox.poll(1, TimeUnit.MILLISECONDS);
```

#### 3.2 Constructor Caching (Already mentioned in performance doc)
**File**: `lib/src/main/java/com/cajunsystems/ActorSystem.java`
- Add `ConcurrentHashMap<Class<?>, Constructor<?>>` cache
- Cache handler constructors in `actorOf()` method

## Configuration Examples

### Default (Existing Behavior)
```java
ActorSystem system = new ActorSystem();
Pid actor = system.actorOf(MyHandler.class).spawn();
// Uses LinkedBlockingQueue with MailboxProcessor (100ms→1ms polling)
```

### Dispatcher with LinkedBlockingQueue
```java
MailboxConfig config = new MailboxConfig()
    .setMailboxType(MailboxType.DISPATCHER_LBQ)
    .setInitialCapacity(1024)
    .setThroughput(64);

ActorSystem system = new ActorSystem(
    new ThreadPoolFactory(),
    null,
    config,
    new DefaultMailboxProvider<>()
);

Pid actor = system.actorOf(MyHandler.class).spawn();
// Uses dispatcher + LinkedBlockingQueue
```

### Dispatcher with MPSC (High Throughput)
```java
MailboxConfig config = new MailboxConfig()
    .setMailboxType(MailboxType.DISPATCHER_MPSC)
    .setInitialCapacity(4096) // Must be power of 2
    .setThroughput(128)
    .setOverflowStrategy(OverflowStrategy.DROP);

Pid actor = system.actorOf(MyHandler.class)
    .withMailboxConfig(config)
    .spawn();
// Uses dispatcher + MpscArrayQueue (lock-free)
```

## Migration Strategy

### Backward Compatibility
- Default remains `MailboxType.BLOCKING` (existing behavior)
- No changes required for existing code
- Performance optimization (1ms timeout) applies to all

### Gradual Adoption
1. **Phase 1**: Apply 1ms timeout fix to existing MailboxProcessor
2. **Phase 2**: Test dispatcher mode with LinkedBlockingQueue
3. **Phase 3**: Migrate high-throughput actors to MPSC queues

## Performance Expectations

### Current Baseline
- **Latency**: ~100ms (due to polling timeout)
- **Throughput**: Limited by thread-per-actor model
- **Contention**: Low (dedicated threads)

### After 1ms Timeout Fix
- **Latency**: ~1-2ms (100x improvement)
- **Throughput**: Similar
- **Contention**: Low

### After Dispatcher + LinkedBlockingQueue
- **Latency**: ~1-2ms (maintained)
- **Throughput**: 2-3x improvement (shared thread pool)
- **Contention**: Low-Medium (scheduler coordination)
- **Scalability**: Better (fewer OS threads)

### After Dispatcher + MpscArrayQueue
- **Latency**: ~0.5-1ms (lock-free)
- **Throughput**: 5-10x improvement (lock-free + batching)
- **Contention**: Minimal (lock-free CAS)
- **Scalability**: Excellent

## Risk Assessment

### Low Risk
- ✅ Adding JCTools dependency (mature library)
- ✅ Creating new classes (no existing code changes)
- ✅ 1ms timeout fix (isolated change)

### Medium Risk
- ⚠️ Dispatcher scheduling logic (needs thorough testing)
- ⚠️ MPSC queue overflow handling (new behavior)

### Mitigation
- Comprehensive unit tests for dispatcher scheduling
- Benchmark comparison: old vs new
- Gradual rollout with feature flags
- Extensive documentation and examples

## Files to Create (New)

1. `lib/src/main/java/com/cajunsystems/dispatcher/Dispatcher.java`
2. `lib/src/main/java/com/cajunsystems/dispatcher/DispatcherMailbox.java`
3. `lib/src/main/java/com/cajunsystems/dispatcher/ActorRunner.java`
4. `lib/src/main/java/com/cajunsystems/dispatcher/DispatcherMailboxProcessor.java`
5. `lib/src/main/java/com/cajunsystems/dispatcher/MailboxType.java` (enum)
6. `lib/src/main/java/com/cajunsystems/dispatcher/OverflowStrategy.java` (enum)

## Files to Modify (Minimal Changes)

1. `gradle/libs.versions.toml` - Add JCTools version
2. `lib/build.gradle` - Add JCTools dependency
3. `lib/src/main/java/com/cajunsystems/config/MailboxConfig.java` - Add dispatcher fields
4. `lib/src/main/java/com/cajunsystems/config/DefaultMailboxProvider.java` - Support dispatcher mailboxes
5. `lib/src/main/java/com/cajunsystems/Actor.java` - Detect and use dispatcher processor
6. `lib/src/main/java/com/cajunsystems/ActorSystem.java` - Constructor caching optimization
7. `lib/src/main/java/com/cajunsystems/MailboxProcessor.java` - 100ms → 1ms timeout fix

## Testing Strategy

### Unit Tests
- Dispatcher scheduling correctness
- DispatcherMailbox enqueue/dequeue
- ActorRunner batch processing
- Overflow strategy handling

### Integration Tests
- Actor message delivery with dispatcher
- Backpressure compatibility
- Supervision compatibility
- Ask pattern compatibility

### Benchmarks
- Message latency (ping-pong)
- Message throughput (producer-consumer)
- Actor creation overhead
- Scalability (1k, 10k, 100k actors)

## Timeline Estimate

- **Phase 1 (Infrastructure)**: 2-3 days
- **Phase 2 (Integration)**: 3-4 days
- **Phase 3 (Optimization)**: 1 day
- **Testing & Documentation**: 2-3 days
- **Total**: ~8-11 days

## Conclusion

This proposal provides a **minimal, non-invasive** path to integrate dispatcher-style threading into Cajun while maintaining backward compatibility. The opt-in model allows gradual adoption, and the configurable mailbox types (LinkedBlockingQueue vs MpscArrayQueue) provide flexibility for different workload characteristics.

**Key Benefits**:
- ✅ Backward compatible (default behavior unchanged)
- ✅ Performance improvements (100x latency reduction, 5-10x throughput increase)
- ✅ Flexible configuration (choose mailbox type per actor)
- ✅ Minimal code changes (7 file modifications, 6 new files)
- ✅ Modern architecture (virtual threads, lock-free queues)

**Recommendation**: Proceed with implementation, starting with Phase 1 (infrastructure) to validate the approach before full integration.
