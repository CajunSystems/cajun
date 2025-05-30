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
- [Request-Response with Ask Pattern](#request-response-with-ask-pattern)
- [Error Handling and Supervision Strategy](#error-handling-and-supervision-strategy)
- [Stateful Actors and Persistence](#stateful-actors-and-persistence)
  - [State Persistence](#state-persistence)
  - [Message Persistence and Replay](#message-persistence-and-replay)
  - [Stateful Actor Recovery](#stateful-actor-recovery)
  - [Adaptive Snapshot Strategy](#adaptive-snapshot-strategy)
- [Backpressure Support in Actors](#backpressure-support-in-actors)
  - [Backpressure States](#backpressure-states)
  - [Backpressure Strategies](#backpressure-strategies)
  - [Using Backpressure in StatefulActor](#using-backpressure-in-statefulactor)
  - [Custom Backpressure Handlers](#custom-backpressure-handlers)
  - [Backpressure Monitoring and Callbacks](#backpressure-monitoring-and-callbacks)
  - [High Priority Messages](#high-priority-messages)
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

Cajun provides a clean, interface-based approach for creating actors. This approach separates the message handling logic from the actor lifecycle management, making your code more maintainable and testable.

#### 1. Stateless Actors with Handler Interface

For stateless actors, implement the `Handler<Message>` interface:

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

public record HelloCount(int count) {
}

public class GreetingHandler implements Handler<GreetingMessage> {
    private int helloCount = 0;
    
    @Override
    public void receive(GreetingMessage message, ActorContext context) {
        switch (message) {
            case HelloMessage ignored -> {
                // Updating state of the handler
                helloCount++;
            }
            case GetHelloCount ghc -> {
                // Replying back to calling actor
                context.tell(ghc.replyTo(), new HelloCount(helloCount));
            }
            case ByeMessage ignored -> {
                // Sending a message to self
                context.tellSelf(new Shutdown());
            }
            case Shutdown ignored -> {
                // Stopping actor
                context.stop();
            }
        }
    }
    
    // Optional lifecycle methods
    @Override
    public void preStart(ActorContext context) {
        // Initialization logic
    }
    
    @Override
    public void postStop(ActorContext context) {
        // Cleanup logic
    }
    
    @Override
    public boolean onError(GreetingMessage message, Throwable exception, ActorContext context) {
        // Custom error handling
        return false; // Return true to reprocess the message
    }
}
```

Create and start the actor:

```java
// Create an actor with a handler class (instantiated automatically)
Pid actorPid = system.actorOf(GreetingHandler.class)
    .withId("greeter-1")  // Optional: specify ID (otherwise auto-generated)
    .spawn();

// Or create with a handler instance
GreetingHandler handler = new GreetingHandler();
Pid actorPid = system.actorOf(handler).spawn();

// Send messages
actorPid.tell(new HelloMessage());
```

#### 2. Stateful Actors with StatefulHandler Interface

For actors that need to maintain and persist state, implement the `StatefulHandler<State, Message>` interface:

```java
public class CounterHandler implements StatefulHandler<Integer, CounterMessage> {
    
    @Override
    public Integer receive(CounterMessage message, Integer state, ActorContext context) {
        return switch (message) {
            case Increment ignored -> state + 1;
            case Decrement ignored -> state - 1;
            case Reset ignored -> 0;
            case GetCount gc -> {
                context.tell(gc.replyTo(), new CountResult(state));
                yield state; // Return unchanged state
            }
        };
    }
    
    @Override
    public Integer preStart(Integer state, ActorContext context) {
        // Optional initialization logic
        return state;
    }
    
    @Override
    public void postStop(Integer state, ActorContext context) {
        // Optional cleanup logic
    }
    
    @Override
    public boolean onError(CounterMessage message, Integer state, Throwable exception, ActorContext context) {
        // Custom error handling
        return false; // Return true to reprocess the message
    }
}
```

Create and start the stateful actor:

```java
// Create a stateful actor with a handler class and initial state
Pid counterPid = system.statefulActorOf(CounterHandler.class, 0)
    .withId("counter-1")  // Optional: specify ID (otherwise auto-generated)
    .spawn();

// Or create with a handler instance
CounterHandler handler = new CounterHandler();
Pid counterPid = system.statefulActorOf(handler, 0).spawn();

// Send messages
counterPid.tell(new Increment());
```

#### 3. Advanced Configuration

Both actor builders support additional configuration options:

```java
// Configure backpressure, mailbox, and persistence
Pid actorPid = system.actorOf(GreetingHandler.class)
    .withBackpressureConfig(new BackpressureConfig())
    .withMailboxConfig(new ResizableMailboxConfig())
    .spawn();

// Configure stateful actor with persistence
Pid counterPid = system.statefulActorOf(CounterHandler.class, 0)
    .withPersistence(
        PersistenceFactory.createBatchedFileMessageJournal(),
        PersistenceFactory.createFileSnapshotStore()
    )
    .spawn();
```

#### 4. Creating Actor Hierarchies

You can create parent-child relationships between actors:

```java
// Create a parent actor
Pid parentPid = system.actorOf(ParentHandler.class).spawn();

// Create a child actor through the parent
Pid childPid = system.actorOf(ChildHandler.class)
    .withParent(system.getActor(parentPid))
    .spawn();

// Or create a child directly from another handler
public class ParentHandler implements Handler<ParentMessage> {
    @Override
    public void receive(ParentMessage message, ActorContext context) {
        if (message instanceof CreateChild) {
            // Create a child actor
            Pid childPid = context.createChild(ChildHandler.class, "child-1");
            // Send message to the child
            context.tell(childPid, new ChildMessage());
        }
    }
}
```

#### Key Features of Stateful Actors

- **Persistent State**: State is automatically persisted using configurable storage backends
- **State Recovery**: Automatically recovers state when an actor restarts
- **Type Safety**: Generic type parameters for both state and message types
- **Pluggable Storage**: Supports different state storage implementations:
  - In-memory storage (default)
  - File-based storage
  - Custom storage implementations

#### Advanced Stateful Actor Features

1. **Configuring State Persistence**

```java
// Create a stateful actor with file-based persistence
Pid counterPid = system.statefulActorOf(CounterHandler.class, 0)
    .withPersistence(
        PersistenceFactory.createFileSnapshotStore("/path/to/snapshots"),
        PersistenceFactory.createBatchedFileMessageJournal("/path/to/journal")
    )
    .spawn();
```

2. **State Recovery Options**

```java
// Configure recovery options
Pid counterPid = system.statefulActorOf(CounterHandler.class, 0)
    .withRecoveryConfig(RecoveryConfig.builder()
        .withRecoveryStrategy(RecoveryStrategy.SNAPSHOT_THEN_JOURNAL)
        .withMaxMessagesToRecover(1000)
        .build())
    .spawn();
```

3. **Snapshot Strategies**

```java
// Configure snapshot strategy
Pid counterPid = system.statefulActorOf(CounterHandler.class, 0)
    .withSnapshotStrategy(SnapshotStrategy.builder()
        .withInterval(Duration.ofMinutes(5))
        .withThreshold(100) // Take snapshot every 100 state changes
        .build())
    .spawn();
```

#### Lifecycle Methods

The `StatefulHandler` interface provides lifecycle methods:

- `preStart(State state, ActorContext context)`: Called when the actor starts, returns the initial state
- `postStop(State state, ActorContext context)`: Called when the actor stops
- `onError(Message message, State state, Throwable exception, ActorContext context)`: Called when message processing fails

### Using the Actor System

After creating your handlers, use the actor system to spawn actors and send messages:

```java
public class CountResultHandler implements Handler<HelloCount> {
    @Override
    public void receive(HelloCount message, ActorContext context) {
        System.out.println("Count: " + message.count());
    }
}

public static void main(String[] args) {
    // Create the actor system
    var actorSystem = new ActorSystem();
    
    // Create a greeting actor
    var greetingPid = actorSystem.actorOf(GreetingHandler.class)
        .withId("greeting-actor-1")
        .spawn();
    
    // Create a receiver actor
    var receiverPid = actorSystem.actorOf(CountResultHandler.class)
        .withId("count-receiver")
        .spawn();
    
    // Send messages
    greetingPid.tell(new HelloMessage());
    greetingPid.tell(new GetHelloCount(receiverPid)); // Will print "Count: 1"
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

## Request-Response with Ask Pattern

While actors typically communicate through one-way asynchronous messages, Cajun provides an "ask pattern" for request-response interactions where you need to wait for a reply.

### Basic Usage

The ask pattern allows you to send a message to an actor and receive a response as a `CompletableFuture`:

```java
// Send a request to an actor and get a future response
CompletableFuture<String> future = actorSystem.ask(
    targetActorPid,       // The actor to ask
    "ping",               // The message to send
    Duration.ofSeconds(3) // Timeout for the response
);

// Process the response when it arrives
future.thenAccept(response -> {
    System.out.println("Received response: " + response);
});

// Or wait for the response (blocking)
try {
    String response = future.get(5, TimeUnit.SECONDS);
    System.out.println("Received response: " + response);
} catch (ExecutionException | InterruptedException | TimeoutException e) {
    System.err.println("Error getting response: " + e.getMessage());
}
```

### Implementing Responders

Actors that respond to ask requests must handle the special `AskPayload` wrapper message:

```java
public class ResponderActor extends Actor<ActorSystem.AskPayload<String>> {
    
    public ResponderActor(ActorSystem system, String actorId) {
        super(system, actorId);
    }
    
    @Override
    protected void receive(ActorSystem.AskPayload<String> payload) {
        // Extract the original message
        String message = payload.message();
        
        // Process the message
        String response = processMessage(message);
        
        // Send the response back to the temporary reply actor
        payload.replyTo().tell(response);
    }
    
    private String processMessage(String message) {
        if ("ping".equals(message)) {
            return "pong";
        }
        return "unknown command";
    }
}
```

### Error Handling

The ask pattern includes robust error handling to manage various failure scenarios:

1. **Timeout Handling**: If no response is received within the specified timeout, the future completes exceptionally with a `TimeoutException`.

2. **Type Mismatch Handling**: If the response type doesn't match the expected type, the future completes exceptionally with a wrapped `ClassCastException`.

3. **Actor Failure Handling**: If the target actor fails while processing the message, the error is propagated to the future based on the actor's supervision strategy.

```java
try {
    String response = actorSystem.ask(actorPid, message, Duration.ofSeconds(2)).get();
    // Process successful response
} catch (ExecutionException ex) {
    Throwable cause = ex.getCause();
    if (cause instanceof TimeoutException) {
        // Handle timeout
    } else if (cause instanceof RuntimeException && cause.getCause() instanceof ClassCastException) {
        // Handle type mismatch
    } else {
        // Handle other errors
    }
} catch (InterruptedException e) {
    // Handle interruption
}
```

### Implementation Details

Internally, the ask pattern works by:

1. Creating a temporary actor to receive the response
2. Wrapping the original message in an `AskPayload` that includes the temporary actor's PID
3. Sending the wrapped message to the target actor
4. Setting up a timeout to complete the future exceptionally if no response arrives in time
5. Completing the future when the temporary actor receives a response

This implementation ensures that resources are properly cleaned up, even in failure scenarios, by automatically stopping the temporary actor after processing the response or timeout.

## Error Handling and Supervision Strategy

Cajun provides a robust error handling system with supervision strategies inspired by Erlang OTP. This allows actors to recover from failures gracefully without crashing the entire system.

### Supervision Strategies

The `SupervisionStrategy` enum defines how an actor should respond to failures:

- **RESUME**: Continue processing messages, ignoring the error (best for non-critical errors)
- **RESTART**: Restart the actor, resetting its state (good for recoverable errors)
- **STOP**: Stop the actor completely (for unrecoverable errors)
- **ESCALATE**: Escalate the error to the parent actor (for system-wide issues)

```java
// Configure an actor with a specific supervision strategy
MyActor actor = new MyActor(system, "my-actor");
actor.withSupervisionStrategy(SupervisionStrategy.RESTART);

// Method chaining for configuration
MyActor actor = new MyActor(system, "my-actor")
    .withSupervisionStrategy(SupervisionStrategy.RESTART)
    .withErrorHook(ex -> logger.error("Actor error", ex));
```

### Lifecycle Hooks

Actors provide lifecycle hooks that are called during error handling and recovery:

- **preStart()**: Called before the actor starts processing messages
- **postStop()**: Called when the actor is stopped
- **onError(Throwable)**: Called when an error occurs during message processing

```java
public class ResilientActor extends Actor<String> {
    
    public ResilientActor(ActorSystem system, String actorId) {
        super(system, actorId);
    }
    
    @Override
    protected void preStart() {
        // Initialize resources
        logger.info("Actor starting: {}", self().id());
    }
    
    @Override
    protected void postStop() {
        // Clean up resources
        logger.info("Actor stopping: {}", self().id());
    }
    
    @Override
    protected void onError(Throwable error) {
        // Custom error handling
        logger.error("Error in actor: {}", self().id(), error);
    }
    
    @Override
    protected void receive(String message) {
        // Message processing logic
    }
}
```

### Exception Handling

The `handleException` method provides centralized error management:

```java
@Override
protected SupervisionStrategy handleException(Throwable exception) {
    if (exception instanceof TemporaryException) {
        // Log and continue for temporary issues
        logger.warn("Temporary error, resuming", exception);
        return SupervisionStrategy.RESUME;
    } else if (exception instanceof RecoverableException) {
        // Restart for recoverable errors
        logger.error("Recoverable error, restarting", exception);
        return SupervisionStrategy.RESTART;
    } else {
        // Stop for critical errors
        logger.error("Critical error, stopping", exception);
        return SupervisionStrategy.STOP;
    }
}
```

### Integration with Ask Pattern

The error handling system integrates seamlessly with the ask pattern, propagating exceptions to the future:

```java
try {
    // If the actor throws an exception while processing this message,
    // it will be propagated to the future based on the supervision strategy
    String result = actorSystem.ask(actorPid, "risky-operation", Duration.ofSeconds(5)).get();
    System.out.println("Success: " + result);
} catch (ExecutionException ex) {
    // The original exception is wrapped in an ExecutionException
    Throwable cause = ex.getCause();
    System.err.println("Actor error: " + cause.getMessage());
}
```

### Logging Integration

Cajun integrates with SLF4J and Logback for comprehensive logging:

```java
// Configure logging in your application
private static final Logger logger = LoggerFactory.getLogger(MyActor.class);

// Errors are automatically logged with appropriate levels
@Override
protected void receive(Message msg) {
    try {
        // Process message
    } catch (Exception e) {
        // This will be logged and handled according to the supervision strategy
        throw new ActorException("Failed to process message", e);
    }
}
```

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

## Backpressure Support in Actors

Cajun provides a sophisticated backpressure system to help actors manage high load scenarios gracefully. The system has been streamlined to improve maintainability, simplify the API, and enhance performance by reducing reflection usage and providing type-safe configuration options.

### Backpressure States

The backpressure system operates with four distinct states:

1. **NORMAL**: The actor is operating with sufficient capacity
2. **WARNING**: The actor is approaching capacity limits but not yet applying backpressure
3. **CRITICAL**: The actor is at or above its high watermark and actively applying backpressure
4. **RECOVERY**: The actor was recently in a CRITICAL state but is now recovering (below high watermark but still above low watermark)

### Backpressure Strategies

Cajun supports multiple strategies for handling backpressure:

1. **BLOCK**: Block the sender until space is available in the mailbox (default behavior)
2. **DROP_NEW**: Drop new messages when the mailbox is full, prioritizing older messages
3. **DROP_OLDEST**: Remove oldest messages from the mailbox using the direct Actor.dropOldestMessage method
4. **CUSTOM**: Use a custom strategy by implementing a `CustomBackpressureHandler`

### Using the Enhanced BackpressureBuilder

```java
// Direct actor configuration with type safety
BackpressureBuilder<MyMessage> builder = new BackpressureBuilder<>(myActor)
    .withStrategy(BackpressureStrategy.DROP_OLDEST)
    .withWarningThreshold(0.7f)
    .withCriticalThreshold(0.9f)
    .withRecoveryThreshold(0.5f);

// Apply the configuration
builder.apply();

// PID-based configuration through ActorSystem
BackpressureBuilder<MyMessage> builder = system.getBackpressureMonitor()
    .configureBackpressure(actorPid)
    .withStrategy(BackpressureStrategy.DROP_OLDEST)
    .withWarningThreshold(0.7f)
    .withCriticalThreshold(0.9f);

builder.apply();

// Using preset configurations for common scenarios
BackpressureBuilder<MyMessage> timeCriticalBuilder = new BackpressureBuilder<>(myActor)
    .presetTimeCritical()
    .apply();

BackpressureBuilder<MyMessage> reliableBuilder = new BackpressureBuilder<>(myActor)
    .presetReliable()
    .apply();

BackpressureBuilder<MyMessage> highThroughputBuilder = new BackpressureBuilder<>(myActor)
    .presetHighThroughput()
    .apply();

// Check backpressure status
BackpressureStatus status = actor.getBackpressureStatus();
BackpressureState currentState = status.getCurrentState();
float fillRatio = status.getFillRatio();
```

### Custom Backpressure Handlers

For advanced backpressure control, you can implement a custom handler and apply it using the BackpressureBuilder:

```java
CustomBackpressureHandler<MyMessage> handler = new CustomBackpressureHandler<>() {
    @Override
    public boolean handleMessage(Actor<MyMessage> actor, MyMessage message, BackpressureSendOptions options) {
        // Custom logic to decide whether to accept the message
        if (message.isPriority()) {
            return true; // Always accept priority messages
        }
        return actor.getCurrentSize() < actor.getCapacity() * 0.9;
    }
    
    @Override
    public boolean makeRoom(Actor<MyMessage> actor) {
        // Custom logic to make room in the mailbox
        // Return true if room was successfully made
        return actor.dropOldestMessage();
    }
};

// Configure with custom handler
new BackpressureBuilder<>(myActor)
    .withStrategy(BackpressureStrategy.CUSTOM)
    .withCustomHandler(handler)
    .apply();
```

### Backpressure Monitoring and Callbacks

The backpressure system provides monitoring capabilities and callback notifications through the BackpressureBuilder:

```java
// Register for backpressure event notifications using the builder
new BackpressureBuilder<>(myActor)
    .withStrategy(BackpressureStrategy.DROP_OLDEST)
    .withWarningThreshold(0.7f)
    .withCriticalThreshold(0.9f)
    .withCallback(event -> {
        logger.info("Backpressure event: {} state, fill ratio: {}", 
                    event.getState(), event.getFillRatio());
        
        // Take action based on backpressure events
        if (event.isBackpressureActive()) {
            // Notify monitoring system, scale resources, etc.
        }
    })
    .apply();

// Access detailed backpressure metrics and history
BackpressureStatus status = actor.getBackpressureStatus();
List<BackpressureEvent> recentEvents = status.getRecentEvents();
List<StateTransition> stateTransitions = status.getStateTransitions();

// Monitor state transition history
for (StateTransition transition : stateTransitions) {
    logger.debug("Transition from {} to {} at {} due to: {}", 
                transition.getFromState(), 
                transition.getToState(),
                transition.getTimestamp(),
                transition.getReason());
}
```

### High Priority Messages

You can send messages with special options to control backpressure behavior:

```java
// Create options for high priority messages that bypass backpressure
BackpressureSendOptions highPriority = new BackpressureSendOptions()
    .setHighPriority(true)
    .setTimeout(Duration.ofSeconds(5));

// Send with high priority
actor.tell(urgentMessage, highPriority);

// Or use the system to send with options
boolean accepted = system.tellWithOptions(actorPid, message, highPriority);

// Block until message is accepted or timeout occurs
BackpressureSendOptions blockingOptions = new BackpressureSendOptions()
    .setBlockUntilAccepted(true)
    .setTimeout(Duration.ofSeconds(3));
```

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

## Feature Roadmap

1. Actor system and actor lifecycle
   - [x] Create Actor and Actor System
   - [x] Support message to self for actor
   - [x] Support hooks for start and shutdown of actor
   - [x] Stateful functional style actor
   - [x] Timed messages
   - [x] Error handling with supervision strategies
   - [x] Request-response pattern with ask functionality
   - [x] Robust exception handling and propagation
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
   - [x] Explicit state initialization and force initialization methods
   - [x] Proper handling of null initial states for recovery cases
   - [x] Adaptive snapshot strategy with time-based and change-count-based options
   - [ ] Customizable backends for snapshots and Write-Ahead Log (WAL)
   - [ ] RocksDB backend for state persistence
   - [ ] Segregation of runtime implementations (file store, in-memory store, etc.) from the actor system
5. Backpressure and load management
   - [x] Integrated backpressure support in StatefulActor
   - [x] Configurable mailbox capacity for backpressure control
   - [x] Load monitoring (queue size, processing times)
   - [x] Configurable retry mechanisms with exponential backoff
   - [x] Error recovery with custom error hooks
   - [x] Processing metrics and backpressure level monitoring
   - [ ] Circuit breaker pattern implementation
   - [ ] Rate limiting strategies

6. Partitioned state and sharding strategy
   - [x] Rendezvous hashing for actor assignment
7. Cluster mode
   - [x] Distributed actor systems
   - [x] Remote messaging between actor systems
   - [x] Actor reassignment on node failure
   - [x] Pluggable messaging system
   - [x] Configurable message delivery guarantees (EXACTLY_ONCE, AT_LEAST_ONCE, AT_MOST_ONCE)