# Performance Optimization Proposal for Cajun

## Executive Summary

This document outlines performance optimization recommendations for the Cajun actor system, focusing on three key areas: persistence backends, threading/scheduling improvements, and actor passivation mechanisms.

## 1. Persistence Backend Optimization

### Current State Analysis

The current persistence implementation uses a file-based system with significant performance limitations:

- **FileSystemPersistenceProvider**: Simple file storage with individual journal files per message
- **FileMessageJournal**: Append-only files with one file per message entry
- **BatchedFileMessageJournal**: Basic batching with synchronous I/O operations

**Performance Issues Identified:**

- Individual file creation per message entry (high overhead)
- Synchronous I/O operations blocking actor threads
- No memory-mapped file utilization
- Poor compression and storage efficiency
- No built-in caching mechanisms

### Recommended High-Performance Backends

#### 1.1 LMDB (Lightning Memory-Mapped Database)

**Advantages:**

- Exceptional read performance (2-10x faster than alternatives in benchmarks)
- Memory-mapped I/O for zero-copy access
- ACID transactions with copy-on-write semantics
- Single file storage (operational simplicity)
- Mature and battle-tested (used in OpenLDAP, Postfix)

**Implementation Approach:**

```java
public class LmdbPersistenceProvider implements PersistenceProvider {
    private final Env<ByteBuffer> env;
    
    public LmdbPersistenceProvider(Path dbPath) {
        this.env = Env.create()
            .setMapSize(10_485_760_000L) // 10GB
            .setMaxDbs(100)
            .open(dbPath.toFile(), EnvFlags.MDB_NOSYNC);
    }
    
    @Override
    public <M> MessageJournal<M> createMessageJournal() {
        return new LmdbMessageJournal<>(env);
    }
}
```

#### 1.2 RocksDB

**Advantages:**

- Excellent write performance with compaction
- Configurable compression (Snappy, Zlib)
- Column family support for data organization
- Built-in caching with block cache
- Tunable for different workloads

**Implementation Approach:**

```java
public class RocksDBPersistenceProvider implements PersistenceProvider {
    private final RocksDB db;
    
    public RocksDBPersistenceProvider(Path dbPath) throws RocksDBException {
        Options options = new Options()
            .setCreateIfMissing(true)
            .setCompressionType(CompressionType.SNAPPY_COMPRESSION)
            .setWriteBufferSize(64 * 1024 * 1024)
            .setMaxWriteBufferNumber(3);
        
        this.db = RocksDB.open(options, dbPath.toString());
    }
}
```

#### 1.3 Chronicle Map

**Advantages:**

- Pure Java implementation (no native dependencies)
- Off-heap memory-mapped storage
- Extremely low latency for in-memory datasets
- Concurrent reads without locking

**Implementation Approach:**

```java
public class ChronicleMapPersistenceProvider implements PersistenceProvider {
    private final ChronicleMap<String, byte[]> map;
    
    public ChronicleMapPersistenceProvider(Path dbPath) throws IOException {
        this.map = ChronicleMap
            .of(String.class, byte[].class)
            .name("cajun-persistence")
            .entries(1_000_000)
            .averageValueSize(1024)
            .createPersistedTo(dbPath.toFile());
    }
}
```

### Migration Strategy

1. **Phase 1**: Implement PersistenceProvider interface for LMDB
2. **Phase 2**: Add RocksDB and Chronicle Map implementations
3. **Phase 3**: Performance benchmarking and selection
4. **Phase 4**: Gradual migration with backward compatibility

## 2. Threading and Scheduling Optimizations

### Threading Current State Analysis

The current threading implementation is well-designed but has optimization opportunities:

**Strengths:**

- Virtual thread utilization (Project Loom)
- Coalesced scheduling pattern in DispatcherMailbox
- Configurable throughput in ActorRunner
- Proper exception handling

**Areas for Improvement:**

#### 2.1 Work-Stealing Thread Pool Enhancement

**Current Issue**: Fixed dispatcher may not optimize thread locality

**Solution**: Implement work-stealing for better load balancing

```java
public class WorkStealingDispatcher extends Dispatcher {
    private final ForkJoinPool workStealingPool;
    
    public static WorkStealingDispatcher create(int parallelism) {
        ForkJoinPool pool = new ForkJoinPool(
            parallelism,
            ForkJoinPool.defaultForkJoinWorkerThreadFactory,
            null,
            false // asyncMode for better actor message processing
        );
        return new WorkStealingDispatcher(pool);
    }
}
```

#### 2.2 Affinity-Based Scheduling

**Current Issue**: No thread affinity for actors

**Solution**: Implement sticky scheduling for cache locality

```java
public class AffinityDispatcher extends Dispatcher {
    private final Map<String, Integer> actorAffinity = new ConcurrentHashMap<>();
    private final AtomicInteger roundRobin = new AtomicInteger(0);
    
    @Override
    public void schedule(Runnable task) {
        if (task instanceof ActorRunner) {
            String actorId = ((ActorRunner<?>) task).getActorId();
            int threadIndex = actorAffinity.computeIfAbsent(actorId, 
                k -> roundRobin.getAndIncrement() % threadCount);
            affinityExecutors[threadIndex].execute(task);
        } else {
            super.schedule(task);
        }
    }
}
```

#### 2.3 Reactive Streams Integration

**Current Issue**: Limited backpressure propagation

**Solution**: Integrate with Project Reactor for better flow control

```java
public class ReactiveMailbox<T> {
    private final EmitterProcessor<T> processor = EmitterProcessor.create();
    private final FluxSink<T> sink = processor.sink();
    
    public boolean enqueue(T message) {
        if (processor.downstreamCount() == 0) {
            return false; // No subscribers
        }
        sink.next(message);
        return true;
    }
    
    public Flux<T> messages() {
        return processor.onBackpressureBuffer();
    }
}
```

## 3. Actor Passivation and Activation

### Passivation Current State Analysis

**Current Gap**: No passivation mechanism exists

**Impact**: Memory usage grows indefinitely with actor count

### Proposed Passivation System

#### 3.1 Passivation Strategy

```java
public enum PassivationStrategy {
    TIME_BASED,     // Passivate after inactivity period
    MEMORY_BASED,   // Passivate under memory pressure
    LOAD_BASED,     // Passivate based on message rate
    HYBRID          // Combination of strategies
}
```

#### 3.2 Passivation Manager

```java
public class PassivationManager {
    private final Map<String, ActorMetadata> activeActors = new ConcurrentHashMap<>();
    private final ScheduledExecutorService passivationScheduler;
    private final PersistenceProvider persistenceProvider;
    
    public void registerActor(String actorId, Actor<?> actor) {
        ActorMetadata metadata = new ActorMetadata(actorId, actor, System.currentTimeMillis());
        activeActors.put(actorId, metadata);
        schedulePassivationCheck(actorId);
    }
    
    private void schedulePassivationCheck(String actorId) {
        passivationScheduler.schedule(() -> {
            ActorMetadata metadata = activeActors.get(actorId);
            if (metadata != null && shouldPassivate(metadata)) {
                passivateActor(actorId);
            }
        }, 30, TimeUnit.SECONDS);
    }
    
    private boolean shouldPassivate(ActorMetadata metadata) {
        long inactiveTime = System.currentTimeMillis() - metadata.lastActivityTime();
        return inactiveTime > PASSIVATION_TIMEOUT_MS && 
               metadata.getMailboxSize() == 0;
    }
    
    private void passivateActor(String actorId) {
        ActorMetadata metadata = activeActors.remove(actorId);
        if (metadata != null) {
            // Persist actor state
            CompletableFuture<Void> persistFuture = persistActorState(metadata);
            persistFuture.thenRun(() -> {
                // Remove from active registry
                actors.remove(actorId);
                logger.info("Passivated actor: {}", actorId);
            });
        }
    }
}
```

#### 3.3 Activation System

```java
public class ActivationManager {
    private final PersistenceProvider persistenceProvider;
    
    public CompletableFuture<Actor<?>> activateActor(String actorId, Class<? extends Actor<?>> actorClass) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Check if actor is already active
                Actor<?> existing = actors.get(actorId);
                if (existing != null) {
                    return existing;
                }
                
                // Load persisted state
                Optional<ActorState> state = loadActorState(actorId);
                
                // Create new actor instance
                Actor<?> actor = createActorInstance(actorClass, actorId);
                
                // Restore state if available
                state.ifPresent(actor::restoreState);
                
                // Register as active
                actors.put(actorId, actor);
                passivationManager.registerActor(actorId, actor);
                
                logger.info("Activated actor: {}", actorId);
                return actor;
                
            } catch (Exception e) {
                throw new RuntimeException("Failed to activate actor: " + actorId, e);
            }
        });
    }
}
```

#### 3.4 Enhanced PID with Passivation Support

```java
public record Pid(String actorId, ActorSystem system) implements Serializable {
    
    public <Message> void tell(Message message) {
        // Check if actor is active
        Optional<Actor<?>> actor = system.getActorOptional(this);
        
        if (actor.isPresent()) {
            // Actor is active, route directly
            system.routeMessage(actorId, message);
        } else {
            // Actor may be passivated, trigger activation
            system.activateAndRouteMessage(actorId, message);
        }
    }
}
```

## 4. Implementation Roadmap

### Phase 1: Persistence Backend (Weeks 1-4)

- [ ] Implement LMDB persistence provider
- [ ] Add RocksDB persistence provider  
- [ ] Performance benchmarking
- [ ] Configuration system for backend selection

### Phase 2: Threading Optimizations (Weeks 5-6)

- [ ] Implement work-stealing dispatcher
- [ ] Add affinity-based scheduling
- [ ] Reactive streams integration

### Phase 3: Passivation System (Weeks 7-8)

- [ ] Implement passivation manager
- [ ] Add activation system
- [ ] Enhanced PID with activation support
- [ ] Configuration for passivation strategies

### Phase 4: Integration and Testing (Weeks 9-10)

- [ ] End-to-end integration testing
- [ ] Performance regression testing
- [ ] Documentation updates
- [ ] Migration guides

## 5. Expected Performance Improvements

### Persistence Improvements

- **Write throughput**: 5-10x improvement with LMDB/RocksDB
- **Read latency**: 2-5x improvement with memory-mapped access
- **Storage efficiency**: 30-50% reduction with compression
- **Recovery time**: 90% reduction with single-file storage

### Threading Improvements  

- **CPU utilization**: 15-25% improvement with work-stealing
- **Cache locality**: 10-20% improvement with affinity scheduling
- **Backpressure handling**: Significantly improved with reactive streams

### Memory Improvements

- **Memory usage**: 40-60% reduction for idle actor systems
- **Startup time**: 30-50% faster with lazy activation
- **Scalability**: Support for 10x more actors in same memory footprint

## 6. Risk Assessment and Mitigation

### Risks

1. **Native library dependencies** (LMDB/RocksDB)
2. **Complexity increase** in passivation logic
3. **Performance regression** during migration

### Mitigation Strategies

1. Provide pure Java fallback (Chronicle Map)
2. Comprehensive testing and gradual rollout
3. Performance monitoring and rollback capabilities

## 7. Conclusion

The proposed optimizations address the three key performance bottlenecks in Cajun:

1. **Persistence**: Move from file-based to high-performance embedded databases
2. **Threading**: Implement work-stealing and affinity-based scheduling
3. **Memory**: Add passivation for scalable actor management

These improvements will significantly enhance Cajun's performance, scalability, and production readiness while maintaining its lock-free concurrency principles.
