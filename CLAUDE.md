# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Cajun is a high-performance, distributed actor system for Java 21+ that provides a modern concurrency model inspired by Erlang OTP. It features persistence, clustering, backpressure management, and both traditional inheritance-based and modern interface-based actor programming styles.

## Development Commands

### Building and Testing
```bash
# Build the project
./gradlew build

# Run unit tests (excludes performance tests by default)
./gradlew test

# Run performance tests specifically
./gradlew performanceTest

# Run a specific test
./gradlew test --tests "com.cajunsystems.ActorSystemTest"

# Run examples
./gradlew -PmainClass=examples.TimedCounter run
```

### Java Configuration
- **Java Version**: 21+ with `--enable-preview` flag (automatically configured in Gradle)
- **Main Module**: `lib/` contains the actor system implementation
- **Source Layout**: Standard Gradle structure under `lib/src/main/java/com/cajunsystems/`

## Architecture Overview

### Core Actor Programming Models

#### Interface-Based Actors (Preferred)
- **`Handler<Message>`**: For stateless message handling
- **`StatefulHandler<State, Message>`**: For stateful actors with persistence
- Created via `ActorBuilder` and `StatefulActorBuilder` with fluent API
- Separation of business logic (handlers) from infrastructure (actors)

#### Traditional Inheritance-Based Actors
- **`Actor<Message>`**: Base class for custom actor implementations
- **`StatefulActor<State, Message>`**: For actors requiring state persistence
- Direct inheritance approach with lifecycle methods

### Key Architectural Components

#### Persistence System
- **Provider Pattern**: `PersistenceProvider` interface with `PersistenceProviderRegistry`
- **Event Sourcing**: Message journals with state snapshots for recovery
- **Runtime Implementations**: File-based persistence in `runtime/persistence/`
- **Recovery Strategy**: Hybrid approach using latest snapshot + message replay

#### Cluster Mode
- **Distributed Architecture**: `ClusterActorSystem` for multi-node deployments
- **Leader Election**: Etcd-based metadata store with automatic failover
- **Message Routing**: Transparent local/remote message delivery
- **Actor Placement**: Rendezvous hashing for consistent distribution

#### Backpressure System
- **State Management**: NORMAL → WARNING → CRITICAL → RECOVERY state flow
- **Strategies**: BLOCK, DROP_NEW, DROP_OLDEST, or CUSTOM handling
- **Configuration**: `BackpressureBuilder` with fluent API and preset configurations
- **Monitoring**: Event tracking with callbacks and metrics

### Package Organization
```
com.cajunsystems/
├── core/                   # Core abstractions and interfaces
├── handler/                # Handler interfaces and adapters
├── internal/              # Internal actor implementations
├── builder/               # Fluent builder APIs
├── persistence/           # Persistence abstractions
│   └── impl/             # Concrete persistence providers
├── cluster/              # Clustering support
├── backpressure/         # Backpressure management
├── config/               # Configuration classes
└── runtime/              # Runtime implementations
    ├── cluster/          # Cluster runtime components
    └── persistence/      # Persistence runtime components
```

## Development Patterns

### Actor Creation
```java
// Interface-based (preferred)
Pid actorPid = system.actorOf(MyHandler.class)
    .withId("my-actor")
    .withBackpressureConfig(config)
    .withThreadPoolFactory(customThreadFactory)
    .spawn();

// Stateful with persistence
Pid statefulPid = system.statefulActorOf(MyStatefulHandler.class, initialState)
    .withPersistence(journal, snapshotStore)
    .withThreadPoolFactory(customThreadFactory)
    .spawn();

// Thread pool factory configuration examples
ThreadPoolFactory ioOptimized = new ThreadPoolFactory()
    .optimizeFor(ThreadPoolFactory.WorkloadType.IO_BOUND);

ThreadPoolFactory cpuOptimized = new ThreadPoolFactory()
    .optimizeFor(ThreadPoolFactory.WorkloadType.CPU_BOUND);

ThreadPoolFactory customFactory = new ThreadPoolFactory()
    .setExecutorType(ThreadPoolFactory.ThreadPoolType.FIXED)
    .setFixedPoolSize(4)
    .setPreferVirtualThreads(false);
```

### Message Handling Patterns
- Use sealed interfaces for message types with pattern matching
- Implement `Handler<Message>` for stateless processing
- Implement `StatefulHandler<State, Message>` for stateful processing
- Return new state from stateful handlers (immutable state pattern)

### Testing Approach
- Unit tests use JUnit 5 with Mockito
- Performance tests are tagged with `@Tag("performance")`
- Test helpers available in `helper/` and `mocks/` packages
- Examples demonstrate usage patterns in `examples/` test directory

### Configuration Management
- Use builder patterns for component configuration
- Persistence providers are registered system-wide
- Backpressure configured per-actor with preset options
- Thread pools configured per-actor via `withThreadPoolFactory()` or system-wide via `ThreadPoolFactory`
- Thread pool factory supports workload optimization (IO_BOUND, CPU_BOUND, MIXED)
- Default behavior uses virtual threads for high concurrency

## Integration Points

### Persistence Integration
- Actors automatically use registered `PersistenceProvider`
- State recovery is transparent during actor startup
- Snapshots taken adaptively based on time/change thresholds

### Cluster Integration
- Message routing works transparently for local/remote actors
- Actor assignment handled automatically via consistent hashing
- Leader election manages cluster-wide actor reassignment

### Backpressure Integration
- Monitoring integrated at both actor and system levels
- Strategies configurable per-actor with preset options
- Events and metrics available for external monitoring systems

## Important Implementation Notes

- Always use `--enable-preview` flag for Java 21 preview features
- Prefer interface-based actors over inheritance-based for new development
- State in stateful actors should be immutable for thread safety
- Use sealed interfaces for message types to enable exhaustive pattern matching
- Performance tests require separate execution due to longer runtime
- Cluster mode requires external etcd instance for metadata storage
- ThreadPoolFactory configuration is optional - actors default to virtual threads if not specified
- Different actors can use different thread pool configurations for workload optimization
- Custom thread factories enable fine-grained control over actor execution context