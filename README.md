# Cajun

<div style="text-align:center">
    <p>A pluggable actor system written in Java leveraging modern features from JDK21+</p>
    <img src="docs/logo.png" alt="Alt Text" style="width:50%; height:auto;">
</div>

## Table of Contents
- [Introduction](#introduction)
- [Prerequisites](#prerequisites)
- [Installation](#installation)
- [Usage](#usage)
  - [Creating Actors](#creating-actors)
  - [Using the Actor System](#using-the-actor-system)
  - [Running Examples](#running-examples)
- [Message Processing and Performance Tuning](#message-processing-and-performance-tuning)
- [Error Handling and Supervision Strategy](#error-handling-and-supervision-strategy)
- [Stateful Actors and Persistence](#stateful-actors-and-persistence)
  - [State Persistence](#state-persistence)
  - [Message Persistence and Replay](#message-persistence-and-replay)
  - [Stateful Actor Recovery](#stateful-actor-recovery)
  - [Adaptive Snapshot Strategy](#adaptive-snapshot-strategy)
- [Backpressure Aware Stateful Actors](#backpressure-aware-stateful-actors)
  - [Backpressure Strategies](#backpressure-strategies)
  - [Retry Mechanisms](#retry-mechanisms)
  - [Error Recovery](#error-recovery)
- [Cluster Mode](#cluster-mode)
- [Feature Roadmap](#feature-roadmap)

## Introduction

Cajun is a lightweight, high-performance actor system for Java applications that leverages modern Java features to provide a simple yet powerful concurrency model. It's designed to make concurrent programming easier and more reliable by using the actor model.

An actor is a concurrent unit of computation which guarantees serial processing of messages with no need for state
synchronization and coordination. This guarantee of actors mainly comes from the way actors communicate with each other,
each actor sends asynchronous messages to other actors and each actor only reads messages from its mailbox.

Key benefits of using Cajun:
- **Simplified Concurrency**: No locks, no synchronized blocks, no race conditions
- **Scalability**: Easily scale from single-threaded to multi-threaded to distributed systems
- **Fault Tolerance**: Built-in supervision strategies for handling failures
- **Flexibility**: Multiple programming styles (OO, functional, stateful)
- **Performance**: High-throughput message processing with batching support

<img src="docs/actor_arch.png" alt="Actor architecture" style="height:auto;">

> **Dedication**: Cajun is inspired by Erlang OTP and the actor model, and is dedicated to the late Joe Armstrong from Ericsson, whose pioneering work on Erlang and the actor model has influenced a generation of concurrent programming systems. Additional inspiration comes from Akka/Pekko.

## Prerequisites
- Java 21+ (with --enable-preview flag)

## Installation

Add Cajun to your project using Gradle:

```gradle
dependencies {
    implementation 'systems.cajun:cajun-core:latest.release'
}
```

Or with Maven:

```xml
<dependency>
    <groupId>systems.cajun</groupId>
    <artifactId>cajun-core</artifactId>
    <version>latest.release</version>
</dependency>
```

## Usage

### Creating Actors

There are multiple styles of creating actors in Cajun:

1. Object-oriented style

This is the default way of creating actors, we extend from the `Actor<M>` class and we implement the `receive` method
where we add logic to handle the message and state mutations.

```java
public sealed interface GreetingMessage permits HelloMessage, ByeMessage, GetHelloCount, Shutdown {
}

public record HelloMessage() implements GreetingMessage {
}

public record ByeMessage() implements GreetingMessage {
}

public record Shutdown() implements GreetingMessage {
}

public record GetHelloCount(Pid replyTo) implements GreetingMessage {
}

public class GreetingActor extends Actor<GreetingMessage> {

    private int helloCount;

    public GreetingActor(ActorSystem system, String actorId) {
        super(system, actorId);
        this.helloCount = 0;
    }

    @Override
    public void receive(GreetingMessage message) {
        switch (message) {
            case HelloMessage ignored -> {
                // Updating state of the actor
                helloCount++;
            }
            case GetHelloCount ghc -> {
                // Replying back to calling actor
                ghc.replyTo().tell(new HelloCount(helloCount));
            }
            case ByeMessage ignored -> {
                // Sending a message to self
                self().tell(new Shutdown());
            }
            case Shutdown ignored -> {
                // Stopping actor after processing all messages
                stop();
            }
        }
    }
}
```

2. Workflow Chaining with ChainedActor

For creating workflow-style actors that process messages in a chain, use the `ChainedActor<M>` class. This provides methods for connecting actors in a sequence and forwarding messages to the next actor in the chain.

```java
public class ProcessorActor extends ChainedActor<WorkflowMessage> {
    
    public ProcessorActor(ActorSystem system, String actorId) {
        super(system, actorId);
    }
    
    @Override
    protected void receive(WorkflowMessage message) {
        // Process the message
        WorkflowMessage processedMessage = processMessage(message);
        
        // Forward to the next actor in the chain
        forward(processedMessage);
    }
    
    private WorkflowMessage processMessage(WorkflowMessage message) {
        // Your processing logic here
        return message;
    }
}
```

To set up a chain of actors:

```java
// Create a chain of processor actors
Pid firstProcessorPid = system.createActorChain(ProcessorActor.class, "processor", 3);

// Create source and sink actors
Pid sourcePid = system.register(SourceActor.class, "source");
Pid sinkPid = system.register(SinkActor.class, "sink");

// Connect source to the first processor
ChainedActor<?> sourceActor = (ChainedActor<?>) system.getActor(sourcePid);
sourceActor.withNext(firstProcessorPid);

// Connect the last processor to the sink
ChainedActor<?> lastProcessor = (ChainedActor<?>) system.getActor(new Pid("processor-3", system));
lastProcessor.withNext(sinkPid);
```

The `ActorSystem` provides a convenient method to create chains of actors:

```java
// Creates 5 processor actors and connects them in sequence
Pid firstActorPid = system.createActorChain(ProcessorActor.class, "processor", 5);
```

3. Functional style actor

When creating a `FunctionalActor` we need to know the State and Message that the actor is going to be using,
then we define call `receiveMessage` on the `FunctionalActor` to program the state changes and message handling logic.
```java
sealed interface CounterProtocol {

    record CountUp() implements CounterProtocol {
    }

    record GetCount(Pid replyTo) implements CounterProtocol {
    }
}

public static void main(String[] args) {
    var counterActor = new FunctionalActor<Integer, CounterProtocol>();
    var counter = actorSystem.register(counterActor.receiveMessage((state, message) -> {
        switch (message) {
            case CounterProtocol.CountUp ignored -> {
                return state + 1;
            }
            case CounterProtocol.GetCount gc -> gc.replyTo().tell(new HelloCount(i));
        }
        return state;
    }, 0), "Counter-Actor");
    var receiverActor = actorSystem.register(CountReceiver.class, "count-receiver-1");
    counter.tell(new CounterProtocol.CountUp());
    counter.tell(new CounterProtocol.CountUp());
    counter.tell(new CounterProtocol.CountUp());
    counter.tell(new CounterProtocol.CountUp());
    counter.tell(new CounterProtocol.GetCount(receiverActor));
}
```

#### Key Features of StatefulActor

- **Persistent State**: State is automatically persisted using configurable storage backends
- **State Recovery**: Automatically recovers state when an actor restarts
- **Type Safety**: Generic type parameters for both state and message types
- **Pluggable Storage**: Supports different state storage implementations:
  - In-memory storage (default)
  - File-based storage
  - Custom storage implementations

#### Using StatefulActor

1. **Creating a StatefulActor**

```java
// Create with default in-memory persistence
CounterActor counterActor = new CounterActor(system, "counter", 0);
counterActor.start();

// Create with file-based persistence
StateStore<String, Integer> stateStore = StateStoreFactory.createFileStore("/path/to/state/dir");
CounterActor persistentActor = new CounterActor(system, "persistent-counter", 0, stateStore);
persistentActor.start();
```

2. **Functional Style StatefulActor**

You can also create stateful actors using a functional style:

```java
// Create a stateful actor with functional style
Pid counterPid = FunctionalStatefulActor.createStatefulActor(
    system,                  // Actor system
    "counter",               // Actor ID
    0,                       // Initial state
    (state, message) -> {    // Message handler function
        if (message instanceof CounterMessage.Increment) {
            return state + 1;
        } else if (message instanceof CounterMessage.Reset) {
            return 0;
        }
        return state;
    }
);
```

3. **Creating Chains of StatefulActors**

The `FunctionalStatefulActor` utility allows creating chains of stateful actors that process messages in sequence:

```java
// Create a chain of stateful actors
Pid firstActorPid = FunctionalStatefulActor.createChain(
    system,                  // Actor system
    "counter-chain",         // Base name for the chain
    3,                       // Number of actors in the chain
    new Integer[]{0, 0, 0},  // Initial states for each actor
    new BiFunction[]{        // Message handlers for each actor
        (state, msg) -> { /* Actor 1 logic */ },
        (state, msg) -> { /* Actor 2 logic */ },
        (state, msg) -> { /* Actor 3 logic */ }
    }
);
```

#### State Management Methods

StatefulActor provides several methods for working with state:

- `getState()`: Get the current state value
- `updateState(State newState)`: Update the state with a new value
- `updateState(Function<State, State> updateFunction)`: Update the state using a function

#### Lifecycle Hooks

StatefulActor overrides the standard Actor lifecycle hooks:

- `preStart()`: Initializes the actor's state from the state store
- `postStop()`: Ensures the final state is persisted before stopping

### Using the actor system

After creating the actor we have to use the actor system to spawn them and send messages.

```java

class CountReceiver extends Actor<HelloCount> {

    public CountReceiver(ActorSystem system, String actorId) {
        super(system, actorId);
    }

    @Override
    protected void receive(HelloCount helloCount) {
        System.out.println("Count" + helloCount);
    }
}

public static void main(String[] args) {
    var actorSystem = new ActorSystem();
    var pid1 = actorSystem.register(GreetingActor.class, "greeting-actor-1");
    var receiverActor = actorSystem.register(CountReceiver.class, "count-receiver");
    pid1.tell(new HelloMessage());
    pid1.tell(new GetHelloCount(receiverActor)); // Count: 1
}
```

## Running examples
To run examples in the project, you can leverage the gradle task runner (--enable-preview flag is already enabled 
for gradle tasks)
```shell
./gradlew -PmainClass=examples.TimedCounter run
```

## Message Processing and Performance Tuning

### Batched Message Processing

Cajun supports batched processing of messages to improve throughput:

- By default, each actor processes messages in batches of 10 messages at a time
- Batch processing can significantly improve throughput by reducing context switching overhead
- You can configure the batch size for any actor using the `withBatchSize()` method

```java
// Create an actor with custom batch size
var myActor = actorSystem.register(MyActor.class, "my-actor");
((MyActor)actorSystem.getActor(myActor)).withBatchSize(50);  // Process 50 messages at a time
```

#### Tuning Considerations:

- **Larger batch sizes**: Improve throughput but may increase latency for individual messages
- **Smaller batch sizes**: Provide more responsive processing but with lower overall throughput
- **Workload characteristics**: CPU-bound tasks benefit from larger batches, while I/O-bound tasks may work better with smaller batches
- **Memory usage**: Larger batches consume more memory as messages are held in memory during processing

### Running Performance Tests

The project includes performance tests that can help you evaluate different configurations:

```shell
# Run all performance tests
./gradlew test -PincludeTags="performance"

# Run a specific performance test
./gradlew test --tests "systems.cajun.performance.ActorPerformanceTest.testActorChainThroughput"
```

The performance tests measure:

1. **Actor Chain Throughput**: Tests message passing through a chain of actors
2. **Many-to-One Throughput**: Tests many sender actors sending to a single receiver
3. **Actor Lifecycle Performance**: Tests creation and stopping of large numbers of actors
## Stateful Actors and Persistence

Cajun provides a `StatefulActor` class that maintains and persists its state. This is useful for actors that need to maintain state across restarts or system failures.

### State Persistence

Stateful actors can persist their state to disk or other storage backends. This allows actors to recover their state after a restart or crash.

```java
// Create a stateful actor with an initial state
StatefulActor<Integer, CounterMessage> counterActor = new CounterActor(system, 0);

// Register the actor with the system
Pid counterPid = system.register(counterActor);

// Send messages to the actor
counterPid.tell(new IncrementMessage());
counterPid.tell(new GetCountMessage(myPid));
```

### Message Persistence and Replay

Cajun supports message persistence and replay for stateful actors using a Write-Ahead Log (WAL) style approach. This enables actors to recover their state by replaying messages after a restart or crash.

#### Key Features

- **Message Journaling**: All messages are logged to a journal before processing
- **State Snapshots**: Periodic snapshots of actor state are taken to speed up recovery
- **Hybrid Recovery**: Uses latest snapshot plus replay of subsequent messages

```java
// Configure a stateful actor with custom persistence
StatefulActor<MyState, MyMessage> actor = new MyStatefulActor(
    system,
    initialState,
    PersistenceFactory.createBatchedFileMessageJournal(),
    PersistenceFactory.createFileSnapshotStore()
);

// Register the actor
Pid actorPid = system.register(actor);
```

### Stateful Actor Recovery

The StatefulActor implements a robust recovery mechanism that ensures state consistency after system restarts or failures:

1. **Initialization Process**:
   - On startup, the actor attempts to load the most recent snapshot
   - If a snapshot exists, it restores the state from that snapshot
   - It then replays any messages received after the snapshot was taken
   - If no snapshot exists, it initializes with the provided initial state

2. **Explicit State Initialization**:
   ```java
   // Force initialization and wait for it to complete (useful in tests)
   statefulActor.forceInitializeState().join();
   
   // Or with timeout
   boolean initialized = statefulActor.waitForStateInitialization(1000);
   ```

3. **Handling Null States**:
   - StatefulActor properly handles null initial states for recovery cases
   - State can be null during initialization and will be properly recovered if snapshots exist

### Adaptive Snapshot Strategy

StatefulActor implements an adaptive snapshot strategy to balance performance and recovery speed:

```java
// Configure snapshot strategy (time-based and change-count-based)
statefulActor.configureSnapshotStrategy(
    30000,    // Take snapshot every 30 seconds
    500       // Or after 500 state changes, whichever comes first
);

// Force an immediate snapshot
statefulActor.forceSnapshot().join();
```

Key snapshot features:
- **Time-based snapshots**: Automatically taken after a configurable time interval
- **Change-based snapshots**: Taken after a certain number of state changes
- **Dedicated thread pool**: Snapshots are taken asynchronously to avoid blocking the actor
- **Final snapshots**: A snapshot is automatically taken when the actor stops

## Backpressure Aware Stateful Actors

Cajun provides a `BackpressureAwareStatefulActor` that extends the StatefulActor with backpressure capabilities to handle high load scenarios gracefully.

```java
// Create a backpressure-aware stateful actor with default strategies
BackpressureAwareStatefulActor<MyState, MyMessage> actor = 
    new MyBackpressureAwareActor(system, initialState);

// Or with custom strategies
BackpressureAwareStatefulActor<MyState, MyMessage> actor = 
    new MyBackpressureAwareActor(
        system,
        initialState,
        new AdaptiveBackpressureStrategy(),
        RetryStrategy.builder()
            .maxRetries(5)
            .initialDelayMs(100)
            .build()
    );

// Add custom error handling
actor.withErrorHook(ex -> logger.error("Error in actor processing", ex));
```

### Backpressure Strategies

The BackpressureAwareStatefulActor monitors system load and can apply different strategies when under pressure:

1. **Load Monitoring**:
   - Tracks message queue size
   - Measures message processing times
   - Monitors persistence queue size

2. **Adaptive Backpressure**:
   - Gradually increases backpressure as load increases
   - Can throttle, delay, or reject messages based on current load
   - Provides metrics for monitoring backpressure levels

3. **Monitoring Backpressure**:
   ```java
   // Get current backpressure metrics
   BackpressureMetrics metrics = actor.getBackpressureMetrics();
   
   // Get current backpressure level (0.0-1.0)
   double level = actor.getCurrentBackpressureLevel();
   
   // Get estimated queue size
   int queueSize = actor.getEstimatedQueueSize();
   ```

### Retry Mechanisms

BackpressureAwareStatefulActor includes built-in retry capabilities for handling transient failures:

```java
// Create a custom retry strategy
RetryStrategy retryStrategy = RetryStrategy.builder()
    .maxRetries(3)
    .initialDelayMs(100)
    .maxDelayMs(5000)
    .backoffMultiplier(2.0)
    .retryableExceptions(IOException.class, TimeoutException.class)
    .build();
```

Key retry features:
- **Exponential backoff**: Increasing delays between retry attempts
- **Configurable retry count**: Set maximum number of retry attempts
- **Exception filtering**: Specify which exceptions should trigger retries
- **Asynchronous execution**: Retries are scheduled on a dedicated thread pool

### Error Recovery

The BackpressureAwareStatefulActor enhances error recovery with:

1. **Graceful degradation**: Can continue processing critical messages while under load
2. **Error hooks**: Custom error handlers for specific error scenarios
3. **Processing metrics**: Tracks and exposes processing performance metrics
4. **Adaptive behavior**: Can adjust processing strategy based on error patterns

## Cluster Mode

Cajun supports running in a distributed cluster mode, allowing actors to communicate across multiple nodes.

### Setting Up a Cluster

```java
// Create a cluster configuration
ClusterConfig config = ClusterConfig.builder()
    .nodeName("node1")
    .port(2551)
    .seedNodes(List.of("127.0.0.1:2551", "127.0.0.1:2552"))
    .build();

// Create a clustered actor system
ActorSystem system = ActorSystem.createClustered(config);

// Create and register actors as usual
Pid actorPid = system.register(MyActor.class, "my-actor");
```

### Communicating with Remote Actors

Messages can be sent to actors regardless of which node they're running on. The system automatically routes messages to the correct node.

```java
// Send a message to an actor (works the same whether the actor is local or remote)
```

#### Fault Tolerance

When a node fails, its actors are automatically reassigned to other nodes in the cluster.

### Multiple Nodes Example

```java
// Node 1
MetadataStore metadataStore1 = new EtcdMetadataStore("http://etcd-host:2379");
DirectMessagingSystem messagingSystem1 = new DirectMessagingSystem("node1", 8080);
messagingSystem1.addNode("node2", "node2-host", 8080);
ClusterActorSystem system1 = new ClusterActorSystem("node1", metadataStore1, messagingSystem1);
system1.start().get();

// Node 2
MetadataStore metadataStore2 = new EtcdMetadataStore("http://etcd-host:2379");
DirectMessagingSystem messagingSystem2 = new DirectMessagingSystem("node2", 8080);
messagingSystem2.addNode("node1", "node1-host", 8080);
ClusterActorSystem system2 = new ClusterActorSystem("node2", metadataStore2, messagingSystem2);
system2.start().get();
```
For more details refer to [Cluster Mode](docs/cluster_mode.md).

### Extending the System

#### Custom Metadata Store

You can implement your own metadata store by implementing the `MetadataStore` interface:

```java
public class CustomMetadataStore implements MetadataStore {
    // Implement the required methods
}
```

#### Custom Messaging System

You can implement your own messaging system by implementing the `MessagingSystem` interface:

```java
public class CustomMessagingSystem implements MessagingSystem {
    // Implement the required methods
}
```

For more details, see the [Cluster Mode Improvements documentation](docs/cluster_mode_improvements.md).

## Feature roadmap

1. Actor system and actor lifecycle
   - [x] Create Actor and Actor System
   - [x] Support message to self for actor
   - [x] Support hooks for start and shutdown of actor
   - [x] Stateful functional style actor
   - [x] Timed messages
   - [x] Error handling with supervision strategies
2. Actor metadata management with etcd
   - [x] Distributed metadata store with etcd support
   - [x] Leader election
   - [x] Actor assignment tracking
3. Actor supervision hierarchy and fault tolerance
   - [x] Basic supervision strategies (RESUME, RESTART, STOP, ESCALATE)
   - [x] Hierarchical supervision
   - [x] Custom supervision policies
   - [x] Lifecycle hooks (preStart, postStop, onError)
   - [x] Integrated logging with SLF4J and Logback
4. Persistent state and messaging for actors
   - [x] StatefulActor with persistent state management
   - [x] Pluggable state storage backends (in-memory, file-based)
   - [x] Message persistence and replay
   - [x] State initialization and recovery mechanisms
   - [x] Snapshot-based state persistence
   - [x] Hybrid recovery approach (snapshots + message replay)
   - [ ] Customizable backends for snapshots and Write-Ahead Log (WAL)
   - [ ] RocksDB backend for state persistence
   - [ ] Segregation of runtime implementations (file store, in-memory store, etc.) from the actor system
5. Partitioned state and sharding strategy
   - [x] Rendezvous hashing for actor assignment
6. Cluster mode
   - [x] Distributed actor systems
   - [x] Remote messaging between actor systems
   - [x] Actor reassignment on node failure
   - [x] Pluggable messaging system
   - [x] Configurable message delivery guarantees (EXACTLY_ONCE, AT_LEAST_ONCE, AT_MOST_ONCE)
