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

Cajun provides built-in backpressure capabilities directly in the `StatefulActor` class to handle high load scenarios gracefully. This allows actors to manage their message processing rate and apply backpressure when they're receiving messages faster than they can process them.

### Using Backpressure in StatefulActor

```java
// Create a stateful actor with backpressure enabled (using default settings)
StatefulActor<MyState, MyMessage> actor = 
    new MyStatefulActor(system, initialState, true); // Enable backpressure with default settings

// Or with custom backpressure settings
StatefulActor<MyState, MyMessage> actor = 
    new MyStatefulActor(
        system,
        initialState,
        true,        // Enable backpressure
        100,         // Initial capacity
        10000        // Maximum capacity
    );

// Check if a message would be accepted before sending
if (actor.tryTell(message)) {
    // Message was accepted
} else {
    // Message was rejected due to backpressure
    // Handle rejection (e.g., retry later, drop, or apply other strategies)
}

// Get backpressure metrics
Actor.BackpressureMetrics metrics = actor.getBackpressureMetrics();
int currentSize = metrics.getCurrentSize();
double fillRatio = metrics.getFillRatio();
```

### Error Handling and Retry Strategy

The `StatefulActor` also supports error handling and retry capabilities:

```java
// Add custom error handling
actor.withErrorHook(ex -> {
    logger.error("Error in actor processing", ex);
    // Perform custom error handling
});

// Add retry capabilities with exponential backoff
actor.withRetryStrategy(new RetryStrategy()
    .withMaxRetries(5)                 // Maximum number of retries
    .withInitialDelay(100)             // Initial delay in milliseconds
    .withMaxDelay(5000)                // Maximum delay in milliseconds
    .withBackoffMultiplier(2.0)        // Exponential backoff multiplier
    .withRetryableExceptionPredicate(  // Custom predicate for retryable exceptions
        ex -> ex instanceof TemporaryException
    ));
```

### Backpressure Features

The backpressure system in Cajun actors provides several key features:

1. **Configurable Mailbox Capacity**:
   - Set initial and maximum capacity for actor mailboxes
   - Control when backpressure is applied based on queue size

2. **Message Rejection**:
   - `tryTell()` method allows senders to handle rejection gracefully
   - Rejected messages can be queued elsewhere, dropped, or handled with custom logic

3. **Retry Strategy with Exponential Backoff**:
   - Automatically retry failed operations with configurable policies
   - Exponential backoff to prevent overwhelming the system during recovery
   - Custom predicates to determine which exceptions are retryable

4. **Custom Error Hooks**:
   - Register custom error handlers to be called when exceptions occur
   - Implement specific error handling behaviors without modifying actor code

5. **Metrics and Monitoring**:
   - Track mailbox size and fill ratio
   - Monitor backpressure levels to detect system stress
   

### Example: Creating a Backpressure-Aware Actor

Here's a complete example of creating a stateful actor with backpressure, retry, and error handling capabilities:

```java
import systems.cajun.ActorSystem;
import systems.cajun.StatefulActor;
import systems.cajun.persistence.RetryStrategy;
import systems.cajun.persistence.BatchedMessageJournal;
import systems.cajun.persistence.SnapshotStore;

import java.io.Serializable;
import java.util.concurrent.TimeUnit;

// Define message type
public class OrderMessage implements Serializable {
    private static final long serialVersionUID = 1L;
    private final String orderId;
    private final double amount;
    
    public OrderMessage(String orderId, double amount) {
        this.orderId = orderId;
        this.amount = amount;
    }
    
    public String getOrderId() { return orderId; }
    public double getAmount() { return amount; }
}

// Define state type
public class OrderProcessorState implements Serializable {
    private static final long serialVersionUID = 1L;
    private final int processedCount;
    private final double totalAmount;
    
    public OrderProcessorState(int processedCount, double totalAmount) {
        this.processedCount = processedCount;
        this.totalAmount = totalAmount;
    }
    
    public OrderProcessorState addOrder(double amount) {
        return new OrderProcessorState(processedCount + 1, totalAmount + amount);
    }
    
    public int getProcessedCount() { return processedCount; }
    public double getTotalAmount() { return totalAmount; }
}

// Create the backpressure-aware actor
public class OrderProcessorActor extends StatefulActor<OrderProcessorState, OrderMessage> {
    
    public OrderProcessorActor(
            ActorSystem system,
            String actorId,
            OrderProcessorState initialState,
            BatchedMessageJournal<OrderMessage> messageJournal,
            SnapshotStore<OrderProcessorState> snapshotStore) {
        // Enable backpressure with custom settings
        super(system, actorId, initialState, messageJournal, snapshotStore, 5, true, 100, 1000);
        
        // Configure retry strategy
        withRetryStrategy(new RetryStrategy()
            .withMaxRetries(3)
            .withInitialDelay(100)
            .withMaxDelay(2000)
            .withBackoffMultiplier(2.0)
            .withRetryableExceptionPredicate(ex -> {
                // Only retry certain types of exceptions
                return ex instanceof TemporaryDatabaseException;
            }));
        
        // Add custom error handling
        withErrorHook(ex -> {
            System.err.println("Error processing order: " + ex.getMessage());
            // Could notify monitoring systems, log to special error queue, etc.
        });
    }
    
    @Override
    protected OrderProcessorState processMessage(OrderProcessorState state, OrderMessage message) {
        // Simulate database operation that might fail
        try {
            // In a real system, this would be a database call
            if (Math.random() < 0.1) { // 10% chance of failure
                throw new TemporaryDatabaseException("Database connection error");
            }
            
            // Process the order
            System.out.println("Processing order: " + message.getOrderId() + 
                             " for $" + message.getAmount());
            
            // Update state with new order
            return state.addOrder(message.getAmount());
        } catch (Exception e) {
            // This will be caught by the retry mechanism
            throw e;
        }
    }
    
    // Custom exception for demonstration
    private static class TemporaryDatabaseException extends RuntimeException {
        public TemporaryDatabaseException(String message) {
            super(message);
        }
    }
}

// Usage example
public class BackpressureExample {
    public static void main(String[] args) throws Exception {
        ActorSystem system = new ActorSystem();
        
        // Create actor with initial state
        OrderProcessorActor actor = new OrderProcessorActor(
            system, 
            "order-processor", 
            new OrderProcessorState(0, 0.0),
            null, // Use default message journal
            null  // Use default snapshot store
        );
        
        // Start the actor
        actor.start();
        
        // Wait for actor to initialize
        actor.waitForStateInitialization(1000);
        
        // Send messages with backpressure handling
        for (int i = 1; i <= 1000; i++) {
            OrderMessage order = new OrderMessage("ORD-" + i, 100.0 * i);
            
            // Try to send with backpressure awareness
            if (!actor.tryTell(order)) {
                System.out.println("Backpressure applied, order " + order.getOrderId() + " rejected");
                
                // Wait and retry later
                TimeUnit.MILLISECONDS.sleep(50);
                i--; // Retry this order
            }
        }
        
        // Get backpressure metrics
        Actor.BackpressureMetrics metrics = actor.getBackpressureMetrics();
        System.out.println("Current mailbox size: " + metrics.getCurrentSize());
        System.out.println("Fill ratio: " + metrics.getFillRatio());
        
        // Shutdown
        system.shutdown();
    }
}
```

This example demonstrates:

1. Creating a stateful actor with backpressure enabled and custom capacity settings
2. Configuring a retry strategy with exponential backoff for transient failures
3. Adding a custom error hook for specialized error handling
4. Using the `tryTell()` method to handle backpressure rejection
5. Monitoring backpressure metrics
2. **Error hooks**: Custom error handlers for specific error scenarios
3. **Processing metrics**: Tracks and exposes processing performance metrics
4. **Retry logic**: Automatically retries failed operations with configurable policies

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
