# Cajun

<div style="text-align:center">
    <p>A pluggable actor system written in java leveraging modern features from JDK21+</p>
    <img src="docs/logo.png" alt="Alt Text" style="width:50%; height:auto;">
</div>

An actor is a concurrent unit of computation which guarantees serial processing of messages with no need for state
synchronization and coordination. This guarantee of actors mainly comes from the way actors communicate with each other,
each actor send asynchronous messages to other actors and each actor only reads messages from its mailbox.

<img src="docs/actor_arch.png" alt="Actor architecture" style="height:auto;">

## Prerequisites
- Java 21+ (with --enable-preview flag)

## Usage

### Creating actors

There are two styles of creating actors, one is the Object-oriented style and Functional style

**Caution: When dealing with state of actors, be sure not to allow mutable objects to escape to external actors, 
this could cause unwanted state mutations, this is mainly due to the nature of referential objects in Java.**

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

2. Functional style actor

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

3. StatefulActor - Persistent State Management

The `StatefulActor` extends the base Actor class by adding state management capabilities with persistence. It allows actors to maintain state that survives actor restarts and system shutdowns.

```java
public class CounterActor extends StatefulActor<Integer, CounterMessage> {
    
    // Create with in-memory persistence
    public CounterActor(ActorSystem system, Integer initialState) {
        super(system, initialState);
    }
    
    // Create with custom persistence store
    public CounterActor(ActorSystem system, Integer initialState, StateStore<String, Integer> stateStore) {
        super(system, initialState, stateStore);
    }
    
    @Override
    protected Integer processMessage(Integer state, CounterMessage message) {
        if (message instanceof CounterMessage.Increment increment) {
            return state + increment.amount();
        } else if (message instanceof CounterMessage.Reset) {
            return 0;
        } else if (message instanceof CounterMessage.GetCount getCount) {
            getCount.callback().accept(state);
        }
        return state;
    }
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
./gradlew test --tests "systems.cajun.ActorPerformanceTest.testActorChainThroughput"
```

The performance tests measure:

1. **Actor Chain Throughput**: Tests message passing through a chain of actors
2. **Many-to-One Throughput**: Tests many sender actors sending to a single receiver
3. **Actor Lifecycle Performance**: Tests creation and stopping of large numbers of actors

These tests can help you determine optimal configurations for your specific use case.

## Error Handling and Supervision Strategy

Cajun provides robust error handling capabilities for actors with a supervision strategy system inspired by Erlang/OTP and Akka.

### Supervision Strategies

The following supervision strategies are available:

- **RESUME**: Continue processing the next message after an error, ignoring the failure (default strategy)
- **RESTART**: Stop and restart the actor after an error, optionally reprocessing the failed message
- **STOP**: Stop the actor completely when an error occurs
- **ESCALATE**: Propagate the error to the parent/system, stopping the current actor

### Example Usage

To set a supervision strategy for an actor:

```
// Create an actor system
ActorSystem system = new ActorSystem();

// Create an actor
Pid actorPid = system.register(MyActor.class, "my-actor-id");

// Get the actor reference and set its supervision strategy
MyActor myActor = (MyActor) system.getActor(actorPid);
myActor.withSupervisionStrategy(Actor.SupervisionStrategy.RESTART);
```

### Hierarchical Supervision

Cajun supports hierarchical supervision, allowing actors to be organized in a parent-child hierarchy. When a child actor fails, the error can be handled by its parent according to the parent's supervision strategy.

#### Creating Child Actors

You can create child actors from within a parent actor:

```
// Inside a parent actor class method
public void createChildren() {
    // Create a child actor with a specific ID
    Pid childPid = this.createChild(ChildActor.class, "child-actor-id");

    // Create a child actor with an auto-generated ID
    Pid anotherChildPid = this.createChild(AnotherChildActor.class);
}
```

You can also register a child actor through the ActorSystem:

```
// Create an actor system
ActorSystem actorSystem = new ActorSystem();

// Create a parent actor
Pid parentPid = actorSystem.register(ParentActor.class, "parent-actor");
ParentActor parent = (ParentActor) actorSystem.getActor(parentPid);

// Register a child actor with the parent
Pid childPid = actorSystem.registerChild(ChildActor.class, "child-actor", parent);
```

#### Supervision Hierarchy

When a child actor fails with the ESCALATE strategy, the error is propagated to its parent. The parent then applies its own supervision strategy to handle the child's failure:

1. If the parent uses RESUME, the child actor is restarted and continues processing
2. If the parent uses RESTART, the child actor is restarted
3. If the parent uses STOP, the child actor remains stopped
4. If the parent uses ESCALATE, the error continues up the hierarchy

#### Hierarchical Shutdown

When a parent actor is stopped, all its child actors are automatically stopped as well, ensuring proper cleanup of resources.

### Lifecycle Hooks

Actors provide lifecycle hooks that you can override for custom behavior:

- `preStart()`: Called before the actor starts processing messages
- `postStop()`: Called after the actor has stopped processing messages
- `onError(Message message, Throwable exception)`: Called when an exception occurs during message processing

### Error Propagation

The `ActorException` class is used for error propagation, particularly when using the ESCALATE supervision strategy. It captures the actor ID and original exception for better error handling.

## Feature roadmap

1. Actor system and actor lifecycle
   - [x] Create Actor and Actor System
   - [x] Support message to self for actor
   - [x] Support hooks for start and shutdown of actor
   - [x] Stateful functional style actor
   - [x] Timed messages
   - [x] Error handling with supervision strategies
2. Actor metadata management with etcd
3. Actor supervision hierarchy and fault tolerance
   - [x] Basic supervision strategies (RESUME, RESTART, STOP, ESCALATE)
   - [x] Hierarchical supervision
   - [ ] Custom supervision policies
4. Persistent state and messaging for actors
   - [x] StatefulActor with persistent state management
   - [x] Pluggable state storage backends (in-memory, file-based)
   - [ ] Message persistence and replay
5. Partitioned state and sharding strategy
