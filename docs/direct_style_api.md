# Direct Style API for Cajun Actors

## Table of Contents

1. [Introduction and Motivation](#introduction-and-motivation)
2. [Core Concepts](#core-concepts)
3. [Getting Started](#getting-started)
4. [Supervised Scopes and Structured Concurrency](#supervised-scopes-and-structured-concurrency)
5. [Typed Channels](#typed-channels)
6. [Error Handling](#error-handling)
7. [Ask Pattern](#ask-pattern)
8. [Integration with Existing Actor System](#integration-with-existing-actor-system)
9. [Migration Guide](#migration-guide)
10. [Best Practices and Common Patterns](#best-practices-and-common-patterns)
11. [API Reference](#api-reference)

---

## Introduction and Motivation

### Why Direct Style?

Traditional actor systems use callback-based message handling: you define a behavior function that processes messages asynchronously, and responses flow through `CompletableFuture` or similar constructs. While powerful, this style introduces complexity:

- **Callback nesting** makes control flow hard to follow
- **CompletableFuture chaining** obscures the logical sequence of operations
- **Error propagation** requires explicit handling at every stage
- **Testing** demands async-aware assertions and timeouts

The **direct style API** for Cajun actors brings a fundamentally different programming model — one where concurrent code reads like sequential code, powered by Java 21+ virtual threads.

### Inspiration from Scala's Ox

This API is inspired by [Ox](https://github.com/softwaremill/ox), a safe direct-style concurrency library for Scala 3. Ox demonstrates that structured concurrency and virtual threads enable a programming model where:

- Blocking operations are cheap (virtual threads don't pin OS threads)
- Scoped lifetimes guarantee cleanup and prevent resource leaks
- Supervision strategies handle failures declaratively
- Channels provide typed, blocking communication between concurrent tasks

We bring these ideas to the Cajun actor system in idiomatic Java.

### Virtual Threads: The Enabler

Java 21's virtual threads make the direct style practical. A virtual thread that blocks on `channel.receive()` does **not** block an OS thread — the JVM parks it efficiently and resumes it when data is available. This means you can have millions of blocking actors without exhausting system resources.

```java
// This is cheap — the virtual thread parks, not an OS thread
String message = actor.receive(); // blocks until a message arrives
```

---

## Core Concepts

### ActorScope

`ActorScope` is the foundational building block. It represents a **structured concurrency scope** that manages the lifecycle of all actors and tasks spawned within it. When the scope ends, all child actors are shut down and all resources are cleaned up.

```java
ActorScope scope = ActorScope.supervised(s -> {
    // Everything spawned here is tied to this scope's lifetime
    var actor = s.spawn("worker", initialState, handler);
    // ...
});
// At this point, all actors have been shut down
```

Key properties:
- **Bounded lifetime**: The scope blocks until all children complete or a failure triggers shutdown
- **Automatic cleanup**: Actors are stopped in reverse creation order when the scope exits
- **Failure propagation**: Unhandled errors in child actors propagate to the scope

### supervised() and scoped()

Two factory methods create scopes with different failure semantics:

- **`ActorScope.supervised(body)`** — If any child actor fails with an unhandled exception, the entire scope is cancelled and the exception propagates. This is the recommended default.
- **`ActorScope.scoped(body)`** — Children run independently. A failure in one does not automatically cancel others. You handle errors explicitly.

### DirectActor

`DirectActor<S, M>` is an actor that processes messages using **blocking receive** instead of callbacks. It is parameterized by:

- `S` — the state type
- `M` — the message type

```java
DirectActor<Integer, String> counter = scope.spawn(
    "counter",
    0,
    (state, message) -> state + message.length()
);
```

### TypedChannel

`TypedChannel<T>` provides a **typed, blocking communication channel** between concurrent tasks. It supports both bounded and unbounded buffering.

```java
TypedChannel<String> channel = TypedChannel.create(10); // bounded capacity of 10
channel.send("hello");       // blocks if full
String msg = channel.receive(); // blocks if empty
```

---

## Getting Started

### Basic Actor Creation

The simplest direct-style actor receives messages and updates state:

```java
import com.cajunsystems.direct.ActorScope;
import com.cajunsystems.direct.DirectActor;

public class GettingStarted {
    public static void main(String[] args) {
        ActorScope.supervised(scope -> {
            // Create an actor that counts messages
            DirectActor<Integer, String> counter = scope.spawn(
                "message-counter",
                0,  // initial state
                (state, message) -> {
                    int newState = state + 1;
                    System.out.println("Received: " + message + " (count: " + newState + ")");
                    return newState;
                }
            );

            // Send messages — these are non-blocking fire-and-forget
            counter.send("hello");
            counter.send("world");
            counter.send("!");

            // Give the actor time to process
            Thread.sleep(100);

            // Query the current state
            int count = counter.getState();
            System.out.println("Final count: " + count); // 3
        });
    }
}
```

### Blocking Receive in Actor Logic

For more complex actors that need to coordinate or wait for specific messages, use `receive()` directly inside the actor's virtual thread:

```java
ActorScope.supervised(scope -> {
    TypedChannel<String> requests = TypedChannel.create(100);
    TypedChannel<String> responses = TypedChannel.create(100);

    // Worker actor that blocks on receive
    scope.run("worker", () -> {
        while (true) {
            String request = requests.receive(); // blocks until available
            if ("STOP".equals(request)) break;
            responses.send("Processed: " + request);
        }
    });

    // Send work
    requests.send("task-1");
    requests.send("task-2");
    requests.send("STOP");

    // Collect results
    System.out.println(responses.receive()); // "Processed: task-1"
    System.out.println(responses.receive()); // "Processed: task-2"
});
```

---

## Supervised Scopes and Structured Concurrency

### Lifecycle Management

A supervised scope ensures that all child actors and tasks are properly managed:

```java
ActorScope.supervised(scope -> {
    var actor1 = scope.spawn("actor-1", "idle", (state, msg) -> "active");
    var actor2 = scope.spawn("actor-2", 0, (state, msg) -> state + 1);

    // Both actors are running and processing messages

    actor1.send("go");
    actor2.send("increment");

    // When this block exits, both actors are shut down gracefully
});
// actor-1 and actor-2 are guaranteed stopped here
```

### Supervised Failure Propagation

In a supervised scope, if one child fails, all siblings are cancelled:

```java
try {
    ActorScope.supervised(scope -> {
        scope.spawn("reliable", 0, (state, msg) -> state + 1);

        scope.spawn("unreliable", "ok", (state, msg) -> {
            if ("crash".equals(msg)) {
                throw new RuntimeException("Something went wrong!");
            }
            return state;
        });

        // This will trigger scope-wide shutdown
        scope.actorByName("unreliable").send("crash");

        Thread.sleep(100);
    });
} catch (ScopedException e) {
    System.err.println("Scope failed: " + e.getCause().getMessage());
    // "Scope failed: Something went wrong!"
}
```

### Scoped (Unsupervised) Mode

When you want independent actors that don't affect each other on failure:

```java
ActorScope.scoped(scope -> {
    var worker1 = scope.spawn("worker-1", 0, (state, msg) -> {
        if ("fail".equals(msg)) throw new RuntimeException("worker-1 failed");
        return state + 1;
    });

    var worker2 = scope.spawn("worker-2", 0, (state, msg) -> state + 1);

    worker1.send("fail");  // worker-1 fails, but worker-2 continues
    worker2.send("work");

    Thread.sleep(100);
    System.out.println(worker2.getState()); // 1 — unaffected
});
```

### Nested Scopes

Scopes can be nested for fine-grained lifetime control:

```java
ActorScope.supervised(outer -> {
    var parentActor = outer.spawn("parent", "running", (state, msg) -> state);

    // Inner scope for a batch of temporary workers
    ActorScope.supervised(inner -> {
        for (int i = 0; i < 10; i++) {
            inner.spawn("worker-" + i, 0, (state, msg) -> state + 1);
        }
        // Workers do their jobs...
        Thread.sleep(500);
    });
    // All 10 workers are cleaned up, parent continues

    parentActor.send("workers done");
});
```

---

## Typed Channels

### Creating Channels

`TypedChannel<T>` provides type-safe, blocking communication:

```java
// Bounded channel — send blocks when full
TypedChannel<String> bounded = TypedChannel.create(100);

// Unbounded channel — send never blocks (be cautious with memory)
TypedChannel<String> unbounded = TypedChannel.createUnbounded();
```

### Send and Receive

Both operations are blocking but virtual-thread friendly:

```java
TypedChannel<Integer> channel = TypedChannel.create(10);

// Producer (in a virtual thread / actor)
scope.run("producer", () -> {
    for (int i = 0; i < 100; i++) {
        channel.send(i); // blocks if channel is full
    }
    channel.close();
});

// Consumer (in another virtual thread / actor)
scope.run("consumer", () -> {
    Integer value;
    while ((value = channel.receiveOrNull()) != null) {
        System.out.println("Got: " + value);
    }
    System.out.println("Channel closed, consumer done");
});
```

### Timed Operations

For operations that shouldn't block indefinitely:

```java
TypedChannel<String> channel = TypedChannel.create(10);

// Receive with timeout
String value = channel.receive(Duration.ofSeconds(5));
// Returns the value, or throws TimeoutException after 5 seconds

// Try receive (non-blocking)
String immediate = channel.tryReceive();
// Returns the value if available, or null
```

### Fan-Out and Fan-In Patterns

Multiple consumers can read from the same channel (fan-out), and multiple producers can write to the same channel (fan-in):

```java
ActorScope.supervised(scope -> {
    TypedChannel<String> tasks = TypedChannel.create(100);
    TypedChannel<String> results = TypedChannel.create(100);

    // Fan-out: multiple workers consuming from one channel
    for (int i = 0; i < 4; i++) {
        int workerId = i;
        scope.run("worker-" + i, () -> {
            String task;
            while ((task = tasks.receiveOrNull()) != null) {
                results.send("Worker-" + workerId + " processed: " + task);
            }
        });
    }

    // Fan-in: single collector reading all results
    scope.run("collector", () -> {
        for (int i = 0; i < 10; i++) {
            System.out.println(results.receive());
        }
    });

    // Produce tasks
    for (int i = 0; i < 10; i++) {
        tasks.send("task-" + i);
    }
    tasks.close();
});
```

---

## Error Handling

### ScopedException

When a supervised scope encounters an unhandled actor failure, it wraps the cause in a `ScopedException`:

```java
try {
    ActorScope.supervised(scope -> {
        scope.spawn("failing-actor", "init", (state, msg) -> {
            throw new IllegalStateException("bad state!");
        });

        scope.actorByName("failing-actor").send("trigger");
        Thread.sleep(100);
    });
} catch (ScopedException e) {
    Throwable cause = e.getCause();
    System.err.println("Actor failed: " + cause.getClass().getSimpleName());
    System.err.println("Message: " + cause.getMessage());
    // "Actor failed: IllegalStateException"
    // "Message: bad state!"
}
```

### Per-Actor Error Handling

You can attach error handlers to individual actors:

```java
ActorScope.supervised(scope -> {
    DirectActor<Integer, String> actor = scope.spawn(
        "resilient",
        0,
        (state, msg) -> {
            if (msg.isEmpty()) throw new IllegalArgumentException("empty message");
            return state + 1;
        }
    );

    actor.onError((error, state) -> {
        System.err.println("Error in actor: " + error.getMessage());
        return 0; // reset state to 0 on error
    });

    actor.send("");        // triggers error handler, state resets to 0
    actor.send("valid");   // state becomes 1
});
```

### Supervision Strategies

The scope can be configured with supervision strategies that mirror the existing Cajun actor supervision model:

```java
ActorScope.supervised(scope -> {
    scope.withSupervision(SupervisionStrategy.RESTART, maxRetries(3));

    scope.spawn("retryable", "init", (state, msg) -> {
        // If this throws, the actor is restarted up to 3 times
        return processMessage(state, msg);
    });
});
```

---

## Ask Pattern

### Direct Blocking Ask

The ask pattern lets you send a message and block (on the virtual thread) until a response arrives:

```java
ActorScope.supervised(scope -> {
    DirectActor<Map<String, Integer>, Object> registry = scope.spawn(
        "registry",
        new HashMap<>(),
        (state, msg) -> {
            // Message handling with reply support is built in
            return state;
        }
    );

    // Ask with blocking wait — natural in direct style
    int count = registry.ask(replyTo -> new GetCount("users", replyTo));
    System.out.println("User count: " + count);

    // Ask with timeout
    int result = registry.ask(
        replyTo -> new GetCount("orders", replyTo),
        Duration.ofSeconds(5)
    );
});
```

### Ask Between Actors

Actors can ask each other using the direct style — blocking their virtual thread while waiting:

```java
ActorScope.supervised(scope -> {
    DirectActor<Double, Object> calculator = scope.spawn(
        "calculator", 0.0,
        (state, msg) -> {
            if (msg instanceof Calculate calc) {
                double result = performCalculation(calc.expression());
                calc.replyTo().send(result);
                return result;
            }
            return state;
        }
    );

    DirectActor<String, Object> formatter = scope.spawn(
        "formatter", "",
        (state, msg) -> {
            if (msg instanceof FormatRequest req) {
                // Ask the calculator — blocks this actor's virtual thread
                double value = calculator.ask(
                    replyTo -> new Calculate(req.expression(), replyTo)
                );
                String formatted = String.format("%.2f", value);
                req.replyTo().send(formatted);
                return formatted;
            }
            return state;
        }
    );

    // From the scope, ask the formatter
    String result = formatter.ask(
        replyTo -> new FormatRequest("2 + 2", replyTo)
    );
    System.out.println(result); // "4.00"
});
```

---

## Integration with Existing Actor System

### Wrapping Existing Actors

You can wrap existing Cajun `Actor` instances inside a direct-style scope:

```java
// Existing callback-style actor
Actor<MyState, MyMessage> existingActor = Actor.create("legacy", initialState, behavior);

ActorScope.supervised(scope -> {
    // Wrap it for direct-style interaction
    DirectActor<MyState, MyMessage> wrapped = scope.wrap(existingActor);

    // Now you can use direct-style ask
    MyResponse response = wrapped.ask(replyTo -> new MyQuery("data", replyTo));
});
```

### Bridging Channels to Actors

Connect a `TypedChannel` to an existing actor's mailbox:

```java
ActorScope.supervised(scope -> {
    TypedChannel<String> input = TypedChannel.create(100);
    Actor<Integer, String> legacyActor = Actor.create("counter", 0, countBehavior);

    // Bridge: messages sent to the channel are forwarded to the actor
    scope.bridge(input, legacyActor);

    input.send("hello"); // delivered to legacyActor's mailbox
});
```

### Using CompletableFuture Results

When integrating with code that returns `CompletableFuture`, the direct style lets you block naturally:

```java
ActorScope.supervised(scope -> {
    scope.run("integration", () -> {
        // In callback style, you'd chain .thenApply().thenCompose()...
        // In direct style, just block on the future:
        CompletableFuture<String> future = externalService.fetchData("key");
        String data = future.join(); // safe — this is a virtual thread

        // Process the data with an actor
        DirectActor<String, String> processor = scope.actorByName("processor");
        processor.send(data);
    });
});
```

---

## Migration Guide

### From Callback-Style Behaviors

**Before (callback style):**

```java
Actor<Integer, String> actor = Actor.create(
    "counter",
    0,
    (state, message, context) -> {
        int newState = state + message.length();
        context.getSender().ifPresent(sender ->
            sender.tell(newState)
        );
        return Behaviors.same();
    }
);

CompletableFuture<Integer> future = actor.ask(replyTo ->
    new CountRequest("hello", replyTo)
);
future.thenAccept(count -> System.out.println("Count: " + count));
```

**After (direct style):**

```java
ActorScope.supervised(scope -> {
    DirectActor<Integer, String> actor = scope.spawn(
        "counter",
        0,
        (state, message) -> state + message.length()
    );

    actor.send("hello");
    Thread.sleep(50);
    int count = actor.getState();
    System.out.println("Count: " + count);
});
```

### From CompletableFuture Chains

**Before:**

```java
actor.ask(msg1)
    .thenCompose(result1 -> actor.ask(msg2(result1)))
    .thenCompose(result2 -> actor.ask(msg3(result2)))
    .thenAccept(finalResult -> System.out.println(finalResult))
    .exceptionally(error -> {
        System.err.println("Failed: " + error);
        return null;
    });
```

**After:**

```java
ActorScope.supervised(scope -> {
    try {
        var result1 = actor.ask(replyTo -> msg1(replyTo));
        var result2 = actor.ask(replyTo -> msg2(result1, replyTo));
        var finalResult = actor.ask(replyTo -> msg3(result2, replyTo));
        System.out.println(finalResult);
    } catch (Exception error) {
        System.err.println("Failed: " + error);
    }
});
```

### From Manual Thread Management

**Before:**

```java
ExecutorService executor = Executors.newFixedThreadPool(10);
List<Future<String>> futures = new ArrayList<>();
for (String task : tasks) {
    futures.add(executor.submit(() -> process(task)));
}
for (Future<String> f : futures) {
    System.out.println(f.get());
}
executor.shutdown();
```

**After:**

```java
ActorScope.supervised(scope -> {
    TypedChannel<String> results = TypedChannel.create(tasks.size());

    for (String task : tasks) {
        scope.run("task-" + task, () -> results.send(process(task)));
    }

    for (int i = 0; i < tasks.size(); i++) {
        System.out.println(results.receive());
    }
});
// Scope handles all cleanup — no manual shutdown needed
```

---

## Best Practices and Common Patterns

### 1. Prefer Supervised Scopes

Always start with `ActorScope.supervised()` unless you have a specific reason to tolerate independent failures. Supervised scopes prevent silent failures and ensure consistent system state.

```java
// ✅ Good — failures are visible and handled
ActorScope.supervised(scope -> { ... });

// ⚠️ Use only when you need independent actor lifetimes
ActorScope.scoped(scope -> { ... });
```

### 2. Keep Scopes Short-Lived

Scopes should correspond to logical units of work. Don't create a single scope for your entire application:

```java
// ✅ Good — scope per request
void handleRequest(Request request) {
    ActorScope.supervised(scope -> {
        var validator = scope.spawn("validator", ...);
        var processor = scope.spawn("processor", ...);
        // Process and respond
    });
}

// ❌ Avoid — scope lives forever
ActorScope.supervised(scope -> {
    while (true) {
        handleNextRequest(scope);
    }
});
```

### 3. Use Typed Channels for Coordination

When multiple actors need to coordinate, prefer channels over direct actor references:

```java
ActorScope.supervised(scope -> {
    TypedChannel<WorkItem> workQueue = TypedChannel.create(100);
    TypedChannel<Result> resultQueue = TypedChannel.create(100);

    // Workers read from workQueue, write to resultQueue
    // Coordinator reads from resultQueue
    // Clean separation of concerns
});
```

### 4. Avoid Blocking on Platform Threads

The direct style API is designed for virtual threads. If you call `channel.receive()` or `actor.ask()` from a platform thread, you'll block an OS thread. Always use these APIs from within a scope:

```java
// ✅ Good — inside a scope (virtual thread)
ActorScope.supervised(scope -> {
    String result = channel.receive();
});

// ❌ Avoid — on main/platform thread
String result = channel.receive(); // blocks an OS thread!
```

### 5. Pipeline Pattern

Chain actors in a processing pipeline using channels:

```java
ActorScope.supervised(scope -> {
    TypedChannel<String> raw = TypedChannel.create(100);
    TypedChannel<String> validated = TypedChannel.create(100);
    TypedChannel<String> enriched = TypedChannel.create(100);

    scope.run("validator", () -> {
        String item;
        while ((item = raw.receiveOrNull()) != null) {
            if (isValid(item)) validated.send(item);
        }
        validated.close();
    });

    scope.run("enricher", () -> {
        String item;
        while ((item = validated.receiveOrNull()) != null) {
            enriched.send(enrich(item));
        }
        enriched.close();
    });

    scope.run("persister", () -> {
        String item;
        while ((item = enriched.receiveOrNull()) != null) {
            persist(item);
        }
    });

    // Feed the pipeline
    for (String data : inputData) {
        raw.send(data);
    }
    raw.close();
});
```

### 6. Scatter-Gather Pattern

Fan out work and collect results:

```java
ActorScope.supervised(scope -> {
    List<TypedChannel<Result>> resultChannels = new ArrayList<>();

    for (Service service : services) {
        TypedChannel<Result> ch = TypedChannel.create(1);
        resultChannels.add(ch);
        scope.run("query-" + service.name(), () -> {
            ch.send(service.query(request));
        });
    }

    // Gather results
    List<Result> results = new ArrayList<>();
    for (TypedChannel<Result> ch : resultChannels) {
        results.add(ch.receive());
    }

    return aggregate(results);
});
```

---

## API Reference

All classes are in the `com.cajunsystems.direct` package.

### ActorScope

The structured concurrency scope that manages actor and task lifetimes.

| Method | Description |
|--------|-------------|
| `static void supervised(ScopeBody body)` | Creates a supervised scope. Blocks until all children complete. If any child fails, all are cancelled. |
| `static void scoped(ScopeBody body)` | Creates an unsupervised scope. Children run independently; failures don't propagate. |
| `<S, M> DirectActor<S, M> spawn(String name, S initialState, DirectBehavior<S, M> behavior)` | Spawns a new actor in this scope with the given name, initial state, and behavior. |
| `void run(String name, Runnable task)` | Runs a task in a new virtual thread tied to this scope. |
| `<S, M> DirectActor<S, M> wrap(Actor<S, M> actor)` | Wraps an existing Cajun actor for direct-style interaction. |
| `<M> void bridge(TypedChannel<M> channel, Actor<?, M> actor)` | Bridges a channel to an actor's mailbox. |
| `<S, M> DirectActor<S, M> actorByName(String name)` | Looks up an actor by name within this scope. |
| `void shutdown()` | Initiates graceful shutdown of all actors in the scope. |
| `boolean isActive()` | Returns `true` if the scope is still running. |

### DirectActor\<S, M\>

An actor with direct-style interaction methods.

| Method | Description |
|--------|-------------|
| `void send(M message)` | Sends a message to the actor (fire-and-forget). |
| `S getState()` | Returns the actor's current state. |
| `<R> R ask(Function<TypedChannel<R>, M> messageFactory)` | Sends a message and blocks until a response is received. |
| `<R> R ask(Function<TypedChannel<R>, M> messageFactory, Duration timeout)` | Ask with a timeout. Throws `TimeoutException` if no response within the duration. |
| `void onError(BiFunction<Throwable, S, S> handler)` | Registers an error handler that can recover state on failure. |
| `String getName()` | Returns the actor's name. |
| `void stop()` | Stops the actor gracefully. |
| `boolean isRunning()` | Returns `true` if the actor is still processing messages. |

### DirectBehavior\<S, M\>

Functional interface for actor message handlers.

```java
@FunctionalInterface
public interface DirectBehavior<S, M> {
    S handle(S state, M message);
}
```

### TypedChannel\<T\>

A typed, blocking communication channel.

| Method | Description |
|--------|-------------|
| `static <T> TypedChannel<T> create(int capacity)` | Creates a bounded channel with the given capacity. |
| `static <T> TypedChannel<T> createUnbounded()` | Creates an unbounded channel. |
| `void send(T value)` | Sends a value. Blocks if the channel is full (bounded). |
| `T receive()` | Receives a value. Blocks until one is available. |
| `T receive(Duration timeout)` | Receives with a timeout. Throws `TimeoutException`. |
| `T receiveOrNull()` | Receives a value, or returns `null` if the channel is closed and empty. |
| `T tryReceive()` | Non-blocking receive. Returns the value if immediately available, `null` otherwise. |
| `void close()` | Closes the channel. No more values can be sent. Pending receives will drain remaining values. |
| `boolean isClosed()` | Returns `true` if the channel has been closed. |

### ScopedException

Exception thrown when a supervised scope is terminated due to a child failure.

| Method | Description |
|--------|-------------|
| `Throwable getCause()` | Returns the original exception that caused the scope failure. |
| `String getActorName()` | Returns the name of the actor that failed. |
| `String getScopeName()` | Returns the name of the scope. |

### ScopeBody

Functional interface for scope bodies.

```java
@FunctionalInterface
public interface ScopeBody {
    void run(ActorScope scope) throws Exception;
}
```

### SupervisionStrategy

Enum defining how the scope reacts to child failures.

| Value | Description |
|-------|-------------|
| `RESTART` | Restart the failed actor with its initial state. |
| `STOP` | Stop the failed actor; other actors continue. |
| `ESCALATE` | Propagate the failure to the parent scope (default for supervised). |

---

## Further Reading

- [Cajun Actor System README](../README.md) — Overview of the core actor system
- [Effect Monad API](effect_monad_api.md) — Functional effect system for actors
- [Supervision Audit](supervision_audit.md) — Details on supervision strategies
- [Ox for Scala](https://github.com/softwaremill/ox) — The Scala library that inspired this API
- [JEP 444: Virtual Threads](https://openjdk.org/jeps/444) — Java virtual threads specification
- [JEP 453: Structured Concurrency](https://openjdk.org/jeps/453) — Java structured concurrency preview