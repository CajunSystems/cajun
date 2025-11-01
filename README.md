# Cajun - **C**oncurrency **A**nd **J**ava **UN**locked

<div style="text-align:center">
    <p>Lock-free, predictable concurrency for Java applications using the actor model</p>
    <p><em>Leveraging modern features from JDK21+</em></p>
    <img src="docs/logo.png" alt="Alt Text" style="width:50%; height:auto;">
</div>

## Table of Contents
- [Introduction](#introduction)
- [Testing](#testing)
- [Prerequisites](#prerequisites)
- [Installation](#installation)
- [Usage](#usage)
  - [Creating Actors](#creating-actors)
  - [Using the Actor System](#using-the-actor-system)
  - [Running Examples](#running-examples)
- [Message Processing and Performance Tuning](#message-processing-and-performance-tuning)
- [Configurable Thread Pools](#configurable-thread-pools)
- [Mailbox Configuration](#mailbox-configuration)
- [Request-Response with Ask Pattern](#request-response-with-ask-pattern)
- [Sender Context and Message Forwarding](#sender-context-and-message-forwarding)
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

Cajun (**C**oncurrency **A**nd **J**ava **UN**locked) is a lightweight, high-performance actor system for Java applications that leverages modern Java features to provide a simple yet powerful concurrency model. It's designed to make concurrent programming easier and more reliable by using the actor model - unlocking Java's concurrency potential by removing locks.

### Lock-Free Predictable Concurrency

Cajun provides **lock-free, predictable concurrency** through the actor model. Unlike traditional threading where shared state requires locks and synchronization, actors achieve concurrency through:

- **Message Passing**: Actors communicate exclusively through asynchronous messages, eliminating shared mutable state
- **Isolated State**: Each actor owns its state privately - no locks, no synchronization primitives needed
- **Serial Message Processing**: Messages are processed one at a time in order, guaranteeing predictable behavior
- **No Race Conditions**: State isolation eliminates data races and concurrent modification issues
- **Deterministic Execution**: Message ordering ensures reproducible behavior, making testing and debugging easier

**Performance Impact**: Benchmarks show Cajun actors are **4x faster** than traditional thread-based approaches while providing complete safety without manual synchronization.

### The Actor Model

An actor is a concurrent unit of computation which guarantees serial processing of messages with no need for state synchronization and coordination. This guarantee comes from how actors communicate - each actor sends asynchronous messages to other actors and only reads messages from its own mailbox.

### Key Benefits

- **Lock-Free Concurrency**: Zero locks, zero synchronized blocks, zero race conditions - guaranteed
- **Predictable Behavior**: Deterministic message ordering makes systems easier to reason about and test
- **Scalability**: Easily scale from single-threaded to multi-threaded to distributed systems
- **Fault Tolerance**: Built-in supervision strategies for handling failures gracefully
- **Flexibility**: Multiple programming styles (OO, functional, stateful) to match your needs
- **Performance**: 4x faster than traditional threading with high-throughput message processing
- **Configurable Threading**: Per-actor thread pool configuration with workload optimization presets

<img src="docs/actor_arch.png" alt="Actor architecture" style="height:auto;">

> **Dedication**: Cajun is inspired by Erlang OTP and the actor model, and is dedicated to the late Joe Armstrong from Ericsson, whose pioneering work on Erlang and the actor model has influenced a generation of concurrent programming systems. Additional inspiration comes from Akka/Pekko.

## Testing

Cajun provides comprehensive test utilities that make actor testing clean, fast, and approachable. The test utilities eliminate common pain points like `Thread.sleep()`, `CountDownLatch` boilerplate, and polling loops.

**Key Features:**
- ✅ **No more `Thread.sleep()`** - Use `AsyncAssertion` for deterministic waiting
- ✅ **Direct state inspection** - Inspect stateful actor state without query messages
- ✅ **Mailbox monitoring** - Track queue depth, processing rates, and backpressure
- ✅ **Message capture** - Capture and inspect all messages sent to an actor
- ✅ **Simplified ask pattern** - One-line request-response testing
- ✅ **66 passing tests** - Fully tested and production-ready

**Quick Example:**
```java
@Test
void testCounter() {
    try (TestKit testKit = TestKit.create()) {
        TestPid<Object> counter = testKit.spawnStateful(CounterHandler.class, 0);
        
        counter.tell(new Increment(5));
        
        // No Thread.sleep()! Wait for exact state
        AsyncAssertion.awaitValue(
            counter.stateInspector()::current,
            5,
            Duration.ofSeconds(1)
        );
    }
}
```

**📖 Full Documentation:** See [test-utils/README.md](test-utils/README.md) for complete API documentation, examples, and best practices.

## Prerequisites
- Java 21+ (with --enable-preview flag)

## Installation

Cajun is available on Maven Central. Add it to your project using Gradle:

```gradle
dependencies {
    implementation 'com.cajunsystems:cajun:0.1.3'
}
```

Or with Maven:

```xml
<dependency>
    <groupId>com.cajunsystems</groupId>
    <artifactId>cajun</artifactId>
    <version>0.1.3</version>
</dependency>
```

**Note**: Since Cajun uses Java preview features, you need to enable preview features in your build:

**Gradle:**
```gradle
tasks.withType(JavaCompile) {
    options.compilerArgs.add('--enable-preview')
}

tasks.withType(JavaExec) {
    jvmArgs += '--enable-preview'
}

tasks.withType(Test) {
    jvmArgs += '--enable-preview'
}
```

**Maven:**
```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <version>3.11.0</version>
            <configuration>
                <compilerArgs>
                    <arg>--enable-preview</arg>
                </compilerArgs>
            </configuration>
        </plugin>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-surefire-plugin</artifactId>
            <version>3.0.0</version>
            <configuration>
                <argLine>--enable-preview</argLine>
            </configuration>
        </plugin>
    </plugins>
</build>
```

## Usage

### Quick Start Example

Here's a complete example to get you started with Cajun using the basic building blocks:

```java
import com.cajunsystems.*;
import com.cajunsystems.handler.Handler;

public class HelloWorld {
    
    // Define your message types with explicit replyTo
    public record HelloMessage(String name, Pid replyTo) {}
    public record GreetingResponse(String greeting) {}
    
    // Greeter actor that processes requests
    public static class GreeterHandler implements Handler<HelloMessage> {
        @Override
        public void receive(HelloMessage message, ActorContext context) {
            context.getLogger().info("Received greeting request for: {}", message.name());
            
            // Process and reply
            String greeting = "Hello, " + message.name() + "!";
            context.tell(message.replyTo(), new GreetingResponse(greeting));
        }
    }
    
    // Receiver actor that handles responses
    public static class ReceiverHandler implements Handler<GreetingResponse> {
        @Override
        public void receive(GreetingResponse message, ActorContext context) {
            System.out.println("Received: " + message.greeting());
        }
    }
    
    public static void main(String[] args) throws Exception {
        // 1. Create the ActorSystem
        ActorSystem system = new ActorSystem();
        
        // 2. Spawn actors
        Pid greeter = system.actorOf(GreeterHandler.class)
            .withId("greeter")
            .spawn();
        
        Pid receiver = system.actorOf(ReceiverHandler.class)
            .withId("receiver")
            .spawn();
        
        // 3. Send a message with explicit replyTo
        greeter.tell(new HelloMessage("World", receiver));
        
        // Wait a bit for async processing
        Thread.sleep(100);
        
        // 4. Shutdown the system when done
        system.shutdown();
    }
}
```

**For request-response patterns**, Cajun provides the `ask` pattern that handles the reply mechanism automatically. See [Request-Response with Ask Pattern](#request-response-with-ask-pattern) for a simpler approach when you need synchronous replies.

### Actor System Lifecycle

**Important**: The Cajun actor system keeps the JVM alive after the main method completes. This is the expected behavior for a production actor system.

- **JVM Stays Alive**: The actor system uses a non-daemon keep-alive thread that keeps the JVM running even after the main thread exits. This ensures actors can continue processing messages, even when using virtual threads (which are always daemon threads).
- **Virtual Thread Support**: Actors run on virtual threads by default for optimal I/O-bound workloads. The keep-alive mechanism ensures the JVM doesn't exit prematurely despite virtual threads being daemon threads.
- **Explicit Shutdown Required**: You must call `system.shutdown()` to gracefully shut down the actor system and allow the JVM to exit.

```java
public static void main(String[] args) {
    ActorSystem system = new ActorSystem();
    
    Pid actor = system.actorOf(new Handler<String>() {
        @Override
        public void receive(String message, ActorContext context) {
            System.out.println("Received: " + message);
        }
    }).withId("demo-actor").spawn();
    
    actor.tell("Hello");
    
    // Main thread exits here, but JVM stays alive
    System.out.println("Main exiting - JVM will continue running");
    
    // To allow JVM to exit, you must explicitly shutdown:
    // system.shutdown();
}
```

**Shutdown Options:**

```java
// Graceful shutdown - waits for actors to complete current work
system.shutdown();

// Or stop individual actors
system.stopActor(actorPid);
```

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

// Configure actor with custom thread pool for CPU-intensive work
ThreadPoolFactory cpuFactory = new ThreadPoolFactory()
    .optimizeFor(ThreadPoolFactory.WorkloadType.CPU_BOUND);

Pid computeActor = system.actorOf(ComputationHandler.class)
    .withThreadPoolFactory(cpuFactory)
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

### ActorContext Convenience Features

The `ActorContext` provides several convenience features to simplify common actor patterns:

#### 1. Sending Messages to Self

Use `tellSelf()` to send messages to the current actor:

```java
public class TimerHandler implements Handler<TimerMessage> {
    @Override
    public void receive(TimerMessage message, ActorContext context) {
        switch (message) {
            case Start ignored -> {
                // Schedule a message to self after 1 second
                context.tellSelf(new Tick(), 1, TimeUnit.SECONDS);
            }
            case Tick ignored -> {
                context.getLogger().info("Tick received");
                // Schedule next tick
                context.tellSelf(new Tick(), 1, TimeUnit.SECONDS);
            }
        }
    }
}
```

#### 2. Built-in Logger with Actor Context

Access a pre-configured logger through `context.getLogger()` that automatically includes the actor ID:

```java
public class LoggingHandler implements Handler<String> {
    @Override
    public void receive(String message, ActorContext context) {
        // Logger automatically includes actor ID in output
        context.getLogger().info("Processing message: {}", message);
        context.getLogger().debug("Debug info for actor {}", context.getActorId());
        context.getLogger().error("Error occurred", exception);
    }
}
```

Benefits:
- **Consistent logging format** across all actors
- **Automatic actor ID context** for easier debugging
- **No manual logger setup** required

#### 3. Standardized Reply Pattern with ReplyingMessage

Use the `ReplyingMessage` interface to standardize request-response patterns:

```java
// Define messages that require replies
public record GetUserRequest(String userId, Pid replyTo) implements ReplyingMessage {}
public record GetOrderRequest(String orderId, Pid replyTo) implements ReplyingMessage {}

public record UserResponse(String userId, String name) {}
public record OrderResponse(String orderId, double amount) {}

// In your handler
public class DatabaseHandler implements Handler<Object> {
    @Override
    public void receive(Object message, ActorContext context) {
        switch (message) {
            case GetUserRequest req -> {
                UserResponse user = fetchUser(req.userId());
                // Use the reply convenience method
                context.reply(req, user);
            }
            case GetOrderRequest req -> {
                OrderResponse order = fetchOrder(req.orderId());
                context.reply(req, order);
            }
        }
    }
}
```

Benefits:
- **Strong type contracts** for reply semantics
- **Cleaner code** with `context.reply()` instead of `context.tell(message.replyTo(), response)`
- **Consistent pattern** across your codebase
- **Better IDE support** with autocomplete for replyTo field

The `ReplyingMessage` interface requires implementing a single method:
```java
public interface ReplyingMessage {
    Pid replyTo();
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
- You can configure the batch size for any actor using the `withBatchSize()` method in the builder

```java
// Create an actor with custom batch size
Pid myActor = system.actorOf(MyHandler.class)
    .withId("high-throughput-actor")
    .withBatchSize(50)  // Process 50 messages at a time
    .spawn();

// For stateful actors
Pid statefulActor = system.statefulActorOf(MyStatefulHandler.class, initialState)
    .withBatchSize(100)  // Larger batches for high-throughput scenarios
    .spawn();
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

## Configurable Thread Pools

Cajun provides flexible thread pool configuration for actors, allowing you to optimize performance based on your workload characteristics. Each actor can be configured with its own ThreadPoolFactory, or use the system default (virtual threads).

### Thread Pool Types

Cajun supports multiple thread pool strategies:

- **VIRTUAL**: Uses Java 21 virtual threads for high concurrency with low overhead (default)
- **FIXED**: Uses a fixed-size platform thread pool for predictable resource usage
- **WORK_STEALING**: Uses a work-stealing thread pool for balanced workloads

### Workload Optimization Presets

```java
// Optimize for I/O-bound operations (high concurrency, virtual threads)
ThreadPoolFactory ioOptimized = new ThreadPoolFactory()
    .optimizeFor(ThreadPoolFactory.WorkloadType.IO_BOUND);

// Optimize for CPU-bound operations (fixed thread pool, platform threads)
ThreadPoolFactory cpuOptimized = new ThreadPoolFactory()
    .optimizeFor(ThreadPoolFactory.WorkloadType.CPU_BOUND);

// Optimize for mixed workloads (work-stealing pool)
ThreadPoolFactory mixedOptimized = new ThreadPoolFactory()
    .optimizeFor(ThreadPoolFactory.WorkloadType.MIXED);
```

### Configuring Actors with Custom Thread Pools

```java
// Create actors with optimized thread pools
Pid networkActor = system.actorOf(NetworkHandler.class)
    .withId("network-processor")
    .withThreadPoolFactory(ioOptimized)
    .spawn();

Pid computeActor = system.actorOf(ComputationHandler.class)
    .withId("compute-processor")
    .withThreadPoolFactory(cpuOptimized)
    .spawn();

// Stateful actors also support custom thread pools
Pid statefulActor = system.statefulActorOf(StateHandler.class, initialState)
    .withId("stateful-processor")
    .withThreadPoolFactory(mixedOptimized)
    .spawn();
```

### Custom Thread Pool Configuration

For fine-grained control, you can create custom ThreadPoolFactory configurations:

```java
// Custom configuration for specific requirements
ThreadPoolFactory customFactory = new ThreadPoolFactory()
    .setExecutorType(ThreadPoolFactory.ThreadPoolType.FIXED)
    .setFixedPoolSize(8)  // 8 platform threads
    .setPreferVirtualThreads(false)
    .setUseNamedThreads(true);

Pid customActor = system.actorOf(MyHandler.class)
    .withThreadPoolFactory(customFactory)
    .spawn();
```

### When to Use Different Thread Pool Types

#### Virtual Threads (Default - IO_BOUND)
- **Best for**: Network I/O, file operations, database calls
- **Characteristics**: Extremely lightweight, high concurrency (millions of threads)
- **Use when**: You have many actors doing I/O operations

#### Fixed Thread Pool (CPU_BOUND)
- **Best for**: CPU-intensive computations, mathematical operations
- **Characteristics**: Predictable resource usage, optimal for CPU-bound work
- **Use when**: You have fewer actors doing intensive computation

#### Work-Stealing Pool (MIXED)
- **Best for**: Mixed I/O and CPU workloads
- **Characteristics**: Dynamic load balancing, good for varied workloads
- **Use when**: Your actors have unpredictable or mixed workload patterns

### Performance Considerations

- **Default behavior**: If no ThreadPoolFactory is specified, actors use virtual threads
- **Per-actor configuration**: Different actors can use different thread pool strategies
- **Resource isolation**: Custom thread pools provide isolation between different types of work
- **Monitoring**: Thread pools can be monitored and tuned based on application metrics

## Mailbox Configuration

Actors in Cajun process messages from their mailboxes. The system provides flexibility in how these mailboxes are configured, affecting performance, resource usage, and backpressure behavior.

#### Default Mailbox Behavior

By default, if no specific mailbox configuration is provided when an actor is created, the `ActorSystem` will use its default `MailboxProvider` and default `MailboxConfig`. Typically, this results in:

*   A **`LinkedBlockingQueue`** with a default capacity (e.g., 10,000 messages). This is suitable for general-purpose actors, especially those that might perform I/O operations or benefit from the unbounded nature (up to system memory) of `LinkedBlockingQueue` when paired with virtual threads.

The exact default behavior can be influenced by the system-wide `MailboxProvider` configured in the `ActorSystem`.

#### Overriding Mailbox Configuration

You can customize the mailbox for an actor in several ways:

1.  **Using `MailboxConfig` during Actor Creation:**
    When creating an actor using the `ActorSystem.actorOf(...)` builder pattern, you can provide a specific `MailboxConfig` or `ResizableMailboxConfig`:

    ```java
    // Example: Using a ResizableMailboxConfig for an actor
    ResizableMailboxConfig customMailboxConfig = new ResizableMailboxConfig(
        100,    // Initial capacity
        1000,   // Max capacity
        50,     // Min capacity (for shrinking)
        0.8,    // Resize threshold (e.g., grow at 80% full)
        2.0,    // Resize factor (e.g., double the size)
        0.2,    // Shrink threshold (e.g., shrink at 20% full)
        0.5     // Shrink factor (e.g., halve the size)
    );

    Pid myActor = system.actorOf(MyHandler.class)
        .withMailboxConfig(customMailboxConfig)
        .spawn();
    ```
    If you provide a `ResizableMailboxConfig`, the `DefaultMailboxProvider` will typically create a `ResizableBlockingQueue` for that actor, allowing its mailbox to dynamically adjust its size based on load. Other `MailboxConfig` types might result in different queue implementations based on the provider's logic.

2.  **Providing a Custom `MailboxProvider` to the `ActorSystem`:**
    For system-wide changes or more complex mailbox selection logic, you can implement the `MailboxProvider` interface and configure your `ActorSystem` instance to use it.

    ```java
    // 1. Implement your custom MailboxProvider
    public class MyCustomMailboxProvider<M> implements MailboxProvider<M> {
        @Override
        public BlockingQueue<M> createMailbox(MailboxConfig config, ThreadPoolFactory.WorkloadType workloadTypeHint) {
            if (config instanceof MySpecialConfig) {
                // return new MySpecialQueue<>();
            }
            // Fallback to default logic or other custom queues
            return new DefaultMailboxProvider<M>().createMailbox(config, workloadTypeHint); // Assuming DefaultMailboxProvider has a no-arg constructor or a way to get a default instance
        }
    }

    // 2. Configure ActorSystem to use it
    ActorSystem system = ActorSystem.create("my-system")
        .withMailboxProvider(new MyCustomMailboxProvider<>()) // Provide an instance of your custom provider
        .build();
    ```
    When actors are created within this system, your `MyCustomMailboxProvider` will be called to create their mailboxes, unless an actor explicitly overrides it via its own builder methods (which might also accept a `MailboxProvider` instance for per-actor override).

By understanding and utilizing these configuration options, you can fine-tune mailbox behavior to match the specific needs of your actors and the overall performance characteristics of your application.

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

**Important**: Actors receive their natural message types directly - the system automatically handles the ask pattern infrastructure. You don't need to wrap messages in `AskPayload` or manually manage `replyTo` addresses.

To respond to an ask request, simply use `context.getSender()` to get the sender's Pid and send your response:

```java
public class ResponderHandler implements Handler<String> {
    
    @Override
    public void receive(String message, ActorContext context) {
        // Process the message naturally
        String response = processMessage(message);
        
        // Reply to sender if present (will be present for ask requests)
        context.getSender().ifPresent(sender -> 
            context.tell(sender, response)
        );
    }
    
    private String processMessage(String message) {
        if ("ping".equals(message)) {
            return "pong";
        }
        return "unknown command";
    }
}
```

**Key Points:**
- Your actor handles its natural message type (e.g., `String`, not `AskPayload<String>`)
- The system automatically unwraps ask messages and sets the sender context
- Use `context.getSender()` to get an `Optional<Pid>` of the sender
- `getSender()` returns `Optional.empty()` for regular `tell()` messages, contains sender PID for `ask()` messages
- No need to manually extract `replyTo` or handle `AskPayload` wrappers

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

### Complete Example

Here's a complete example showing both the requester and responder:

```java
public class AskPatternExample {
    
    public record PingMessage() {}
    public record PongMessage() {}
    
    public static class PingPongHandler implements Handler<PingMessage> {
        @Override
        public void receive(PingMessage message, ActorContext context) {
            // Automatically reply to the sender
            context.getSender().ifPresent(sender -> 
                context.tell(sender, new PongMessage())
            );
        }
    }
    
    public static void main(String[] args) throws Exception {
        ActorSystem system = new ActorSystem();
        
        // Create the responder actor
        Pid responder = system.actorOf(PingPongHandler.class).spawn();
        
        // Send an ask request
        CompletableFuture<PongMessage> future = system.ask(
            responder,
            new PingMessage(),
            Duration.ofSeconds(3)
        );
        
        // Wait for and process the response
        PongMessage response = future.get();
        System.out.println("Received pong!");
        
        system.shutdown();
    }
}
```

### Implementation Details

Internally, the ask pattern works by:

1. Creating a temporary actor to receive the response
2. Automatically wrapping your message with sender context (transparent to your code)
3. Sending the message to the target actor
4. Setting up a timeout to complete the future exceptionally if no response arrives in time
5. Completing the future when the temporary actor receives a response
6. Automatically cleaning up the temporary actor

This implementation ensures that:
- Your actors work with their natural message types
- The `replyTo` mechanism is handled automatically by the system
- Resources are properly cleaned up, even in failure scenarios
- The same actor can handle both `tell()` and `ask()` messages seamlessly

## Sender Context and Message Forwarding

Cajun provides explicit control over sender context propagation through actor hierarchies, making it easy to build request routing and processing pipelines.

### Understanding Sender Context

When an actor receives a message, it can check who sent it using `getSender()`, which returns an `Optional<Pid>`:

```java
public class ProcessorHandler implements Handler<Request> {
    @Override
    public void receive(Request message, ActorContext context) {
        // Check if there's a sender (e.g., from ask pattern)
        context.getSender().ifPresent(sender -> {
            // Reply to the sender
            context.tell(sender, new Response("processed"));
        });
    }
}
```

**Key Points:**
- `getSender()` returns `Optional<Pid>` - use `ifPresent()`, `map()`, or `orElse()` for clean handling
- Returns `Optional.empty()` for regular `tell()` messages
- Returns the sender's PID for `ask()` messages
- Sender context is automatically cleared after message processing

### Message Forwarding with `forward()`

When building actor hierarchies or routing patterns, you often want to preserve the original sender so the final handler can reply directly to the requester. Use `forward()` instead of `tell()` to preserve sender context:

```java
public class RouterHandler implements Handler<RoutableRequest> {
    @Override
    public void receive(RoutableRequest message, ActorContext context) {
        Pid targetHandler = selectHandler(message);
        
        // Forward preserves the original sender context
        context.forward(targetHandler, message);
        
        // The target handler can now reply directly to the original requester
    }
}

public class HandlerActor implements Handler<RoutableRequest> {
    @Override
    public void receive(RoutableRequest message, ActorContext context) {
        Response response = process(message);
        
        // Reply goes to original requester, not the router
        context.getSender().ifPresent(requester -> 
            context.tell(requester, response)
        );
    }
}
```

### When to Use Each Method

| Method | Use When | Sender Context |
|--------|----------|----------------|
| `tell()` | Normal message passing, no reply expected | Lost (Optional.empty()) |
| `forward()` | Acting as intermediary, want final actor to reply to original sender | Preserved |
| `ask()` | Request-response pattern, you are the requester | You become the sender |

### Complete Example: Request Pipeline

```java
// Grandparent initiates request
CompletableFuture<Result> future = system.ask(
    parentPid, 
    new ProcessRequest("data"),
    Duration.ofSeconds(3)
);

// Parent forwards to child (preserving grandparent as sender)
public class ParentHandler implements Handler<ProcessRequest> {
    @Override
    public void receive(ProcessRequest msg, ActorContext context) {
        ProcessRequest enhanced = preprocess(msg);
        context.forward(childPid, enhanced); // Sender preserved
    }
}

// Child processes and replies to grandparent
public class ChildHandler implements Handler<ProcessRequest> {
    @Override
    public void receive(ProcessRequest msg, ActorContext context) {
        Result result = process(msg);
        
        // Reply goes to grandparent (original requester)
        context.getSender().ifPresent(requester -> 
            context.tell(requester, result)
        );
    }
}
```

**For more details and advanced patterns, see [docs/sender_propagation.md](docs/sender_propagation.md)**

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
// Define a stateful handler for the counter
public class CounterHandler implements StatefulHandler<Integer, CounterMessage> {
    @Override
    public Integer processMessage(Integer count, CounterMessage message) {
        if (message instanceof IncrementMessage) {
            return count + 1;
        } else if (message instanceof GetCountMessage getCountMsg) {
            // Send the current count back to the sender
            getCountMsg.getSender().tell(count);
            return count;
        }
        return count;
    }
}

// Create a stateful actor with an initial state using the handler pattern
Pid counterPid = system.statefulActor("counter", 0, new CounterHandler());

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
- **Pluggable Persistence**: Swap out persistence implementations without changing actor code
- **Provider Pattern**: Configure system-wide persistence strategy with ease

```java
// Define a stateful handler with custom persistence (legacy approach)
public class MyStatefulHandler implements StatefulHandler<MyState, MyMessage> {
    @Override
    public MyState processMessage(MyState state, MyMessage message) {
        // Process the message and return the new state
        return newState;
    }
}

// Create the actor using the stateful handler
Pid actorPid = system.statefulActor(
    "my-actor",
    initialState,
    new MyStatefulHandler(),
    PersistenceFactory.createBatchedFileMessageJournal(),
    PersistenceFactory.createFileSnapshotStore()
);
```

#### Persistence Provider Pattern

Cajun now supports a provider pattern for persistence, allowing you to swap out persistence implementations at runtime without changing your actor code:

```java
// Register a custom persistence provider for the entire actor system
PersistenceProvider customProvider = new CustomPersistenceProvider();
ActorSystemPersistenceHelper.setPersistenceProvider(actorSystem, customProvider);

// Or use the fluent API
ActorSystemPersistenceHelper.persistence(actorSystem)
    .withPersistenceProvider(customProvider);

// Create stateful actors using the handler pattern with the configured provider
// No need to specify persistence components explicitly
public class MyStatefulHandler implements StatefulHandler<MyState, MyMessage> {
    @Override
    public MyState processMessage(MyState state, MyMessage message) {
        // Process the message and return the new state
        return newState;
    }
}

// The system will use the configured persistence provider automatically
Pid actorPid = system.statefulActor("my-actor", initialState, new MyStatefulHandler());
```

#### Creating Custom Persistence Providers

Implement the `PersistenceProvider` interface to create custom persistence backends:

```java
public class CustomPersistenceProvider implements PersistenceProvider {
    @Override
    public <M> BatchedMessageJournal<M> createBatchedMessageJournal(String actorId) {
        // Implement custom message journaling
        return new CustomBatchedMessageJournal<>(actorId);
    }
    
    @Override
    public <S> SnapshotStore<S> createSnapshotStore(String actorId) {
        // Implement custom state snapshot storage
        return new CustomSnapshotStore<>(actorId);
    }
    
    // Implement other required methods
}
```

The actor system uses `FileSystemPersistenceProvider` by default if no custom provider is specified.

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

Cajun features a robust backpressure system to help actors manage high load scenarios effectively. Backpressure is an opt-in feature, configured using `BackpressureConfig` objects.

### Enabling and Configuring Backpressure

Backpressure can be configured at the `ActorSystem` level, which then applies to actors by default, or dynamically for individual actors if specific settings are needed.

#### System-Wide Configuration
To enable and configure backpressure for all actors by default within an `ActorSystem`, provide a `BackpressureConfig` object during its creation. Actors created within this system will inherit this configuration. If no `BackpressureConfig` is supplied to the `ActorSystem`, backpressure is disabled by default for the system.

**Example:**
```java
// Define backpressure settings using BackpressureConfig
BackpressureConfig systemBpConfig = new BackpressureConfig()
    .setStrategy(BackpressureStrategy.BLOCK)      // Default strategy
    .setWarningThreshold(0.7f)                 // 70% mailbox capacity
    .setCriticalThreshold(0.9f)                // 90% mailbox capacity
    .setRecoveryThreshold(0.5f);                // 50% mailbox capacity

// Create ActorSystem with this configuration
// This also requires a ThreadPoolFactory
ActorSystem system = new ActorSystem(new ThreadPoolFactory(), systemBpConfig);

// Actors created in this system will now use these backpressure settings by default.
```

#### Actor-Specific Configuration
Actors primarily inherit their backpressure configuration from the `ActorSystem` they belong to. If you need to customize backpressure settings for a specific actor (e.g., use a different strategy or thresholds than the system default, or enable it if the system has it disabled), you can do so dynamically after the actor has been created using the `BackpressureBuilder`. See the "Dynamically Managing Backpressure" section for details.

If an actor is part of an `ActorSystem` that has backpressure disabled (no `BackpressureConfig` provided to the system), backpressure will also be disabled for that actor by default. It can then be enabled and configured specifically for that actor using the `BackpressureBuilder`.

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

### Dynamically Managing Backpressure with BackpressureBuilder

While `BackpressureConfig` sets the initial backpressure configuration (either system-wide or for an actor at creation), the `BackpressureBuilder` allows for dynamic adjustments to an actor's backpressure settings after it has been created. This is useful for overriding system defaults for a specific actor, or for enabling and configuring backpressure for an actor if its `ActorSystem` has backpressure disabled by default.

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
```