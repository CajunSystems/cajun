# Cajun Modularization Plan

## Module Structure

### cajun-core
**Purpose**: Lean core with abstractions and basic implementations
**Dependencies**: slf4j-api only
**Contents**:
- Core types: `Actor`, `ActorSystem`, `Pid`, `ActorContext`
- Handlers: `Handler`, `StatefulHandler`
- Builders: `ActorBuilder`, `StatefulActorBuilder`
- Configuration: `ThreadPoolFactory`, `BackpressureConfig`
- Mailbox abstraction: `Mailbox` interface
- Persistence interfaces: `PersistenceProvider`, `MessageJournal`, `SnapshotStore`
- Cluster interfaces: `ClusterActorSystem`, `NodeDiscovery`
- Default implementations using Java standard library

### cajun-mailbox
**Purpose**: High-performance mailbox implementations
**Dependencies**: cajun-core, jctools-core
**Contents**:
- `MpscMailbox`: JCTools MPSC queue wrapper
- `MpscBlockingAdapter`: Adapter to make MPSC work as BlockingQueue
- `LinkedMailbox`: Optimized LinkedBlockingQueue wrapper
- Mailbox factory for different workload types

### cajun-persistence
**Purpose**: Persistence backend implementations
**Dependencies**: cajun-core
**Contents**:
- File-based persistence
- In-memory persistence
- Future: Database backends (PostgreSQL, Cassandra, etc.)

### cajun-cluster
**Purpose**: Clustering support
**Dependencies**: cajun-core, etcd-client
**Contents**:
- Etcd-based cluster implementation
- Leader election
- Distributed actor placement

### cajun-all
**Purpose**: Convenience aggregator (single dependency)
**Dependencies**: All above modules
**Contents**: Empty, just aggregates transitive dependencies

## Migration Strategy

1. **Phase 1**: Create new modules with abstractions
2. **Phase 2**: Move implementations to appropriate modules
3. **Phase 3**: Update `lib/` to depend on new modules (backward compatibility)
4. **Phase 4**: Deprecate `lib/`, make it an alias to `cajun-all`

## Java Module System (JPMS)

Each module will have `module-info.java`:

```java
// cajun-core
module com.cajunsystems.core {
    requires org.slf4j;

    exports com.cajunsystems;
    exports com.cajunsystems.handler;
    exports com.cajunsystems.builder;
    exports com.cajunsystems.config;
    exports com.cajunsystems.mailbox;
    exports com.cajunsystems.persistence;
}

// cajun-mailbox
module com.cajunsystems.mailbox {
    requires com.cajunsystems.core;
    requires org.jctools.core;

    exports com.cajunsystems.mailbox.impl;
}
```

## Performance Improvements

### Immediate (Phase 1)
1. Remove `ResizableBlockingQueue` - replace with `LinkedBlockingQueue`
2. Reduce poll timeout: 100ms → 1ms
3. Remove unnecessary `Thread.yield()`

### Medium-term (Phase 2)
4. Implement JCTools MPSC adapter
5. Add batch message support
6. Optimize ask pattern with shared reply handler

### Long-term (Phase 3)
7. Adaptive polling strategy
8. Message wrapper pooling
9. Lock-free actor registry
