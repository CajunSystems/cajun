# Effect Monad API Guide

## Overview

The Effect monad provides a composable, type-safe, **stack-safe** way to build actor behaviors using functional programming patterns. It integrates seamlessly with Java's Stream API and reactive libraries while maintaining the actor-oriented nature of Cajun.

## Key Features

- **Stack-Safe** - Uses Trampoline pattern to prevent stack overflow on deep compositions
- **Simplified Type Signature** - `Effect<State, Error, Result>` with Message type at match level
- **Idiomatic Java naming** - Uses `.of()` instead of `.pure()`, familiar to Java developers
- **Composable** - Build complex behaviors from simple building blocks
- **Type-safe** - State transitions and error types are compile-time checked
- **Rich Error Handling** - Explicit error recovery with `.recover()`, `.orElse()`, `.attempt()`, and more
- **Interruption Handling** - Virtual thread-native cancellation with `onInterrupt()` and `checkInterrupted()`
- **Parallel Execution** - Built-in `parZip`, `parSequence`, `race`, and `withTimeout` using Structured Concurrency
- **Testable** - Pure functions that are easy to test without spawning actors
- **Stream-compatible** - Works with Java's Stream API and reactive libraries

## Quick Start

### Basic Counter Example

```java
sealed interface CounterMsg {}
record Increment(int amount) implements CounterMsg {}
record Decrement(int amount) implements CounterMsg {}
record GetCount(Pid replyTo) implements CounterMsg {}

// Define behavior using effects - Message type is at match level
Effect<Integer, Throwable, Void> counterEffect = 
    Effect.<Integer, Throwable, Void, CounterMsg>match()
        .when(Increment.class, (state, msg, ctx) -> 
            Effect.modify(s -> s + msg.amount())
                .andThen(Effect.logState(s -> "Count: " + s)))
        .when(Decrement.class, (state, msg, ctx) ->
            Effect.modify(s -> s - msg.amount())
                .andThen(Effect.logState(s -> "Count: " + s)))
        .when(GetCount.class, (state, msg, ctx) ->
            Effect.tell(msg.replyTo(), state))
        .build();

// Create actor with effect-based behavior
Pid counter = ActorSystemEffectExtensions.fromEffect(system, counterEffect, 0)
    .withId("counter")
    .spawn();
```

## Core Concepts

### Effect<State, Error, Result>

An `Effect` represents a **stack-safe** computation that:
- Takes a **state** and **message** (message type specified at match level)
- Produces a **new state**
- May produce a **result** value
- May perform **side effects** (logging, sending messages, etc.)
- May **fail** with an **error** of type `Error` (typically `Throwable`)
- Returns a `Trampoline<EffectResult<State, Result>>` for stack safety

**Key Changes from Previous Version:**
- Message type moved from interface to `match()` method
- All operations return `Trampoline` for stack-safe execution
- Explicit `Error` type parameter for better type safety

### EffectResult<State, Result>

The result of executing an effect, which can be:
- **Success** - Effect executed successfully with a result value
- **NoResult** - Effect executed successfully but produced no result (state change only)
- **Failure** - Effect execution failed with an error

## Factory Methods

### Creating Effects

```java
// Return a value without changing state
Effect<Integer, Throwable, String> effect = Effect.of("success");

// Return current state as result
Effect<Integer, Throwable, Integer> effect = Effect.state();

// Modify state
Effect<Integer, Throwable, Void> effect = Effect.modify(s -> s + 10);

// Set state to specific value
Effect<Integer, Throwable, Void> effect = Effect.setState(100);

// Keep state unchanged (identity)
Effect<Integer, Throwable, Void> effect = Effect.identity();

// Use both state and message (with type casting)
Effect<Integer, Throwable, Void> effect = 
    Effect.fromTransition((Integer state, String msg) -> state + msg.length());

// Create failing effect
Effect<Integer, Throwable, String> effect = 
    Effect.fail(new IllegalStateException("error"));

// No-op effect
Effect<Integer, Throwable, Void> effect = Effect.none();
```

## Advanced Operators

### delay() - Suspend Execution

Suspends execution for a specified duration. Safe with virtual threads.

```java
// Wait 1 second before continuing
Effect.delay(Duration.ofSeconds(1))
    .andThen(Effect.log("Delayed execution"));

// Debounce pattern
Effect.delay(Duration.ofMillis(300))
    .andThen(Effect.modify(s -> s.processInput()));
```

### suspend() - Lazy Evaluation

Creates an effect from a lazy computation that won't execute until the effect runs.

```java
// Computation deferred until effect executes
Effect<State, Throwable, Data> effect = 
    Effect.suspend(() -> expensiveComputation());

// Compare to eager evaluation:
var data = expensiveComputation();  // Runs immediately!
Effect.of(data);  // Just wraps the value
```

### bracket() - Resource Management

Ensures resources are properly acquired and released, even if errors occur.

```java
Effect.bracket(
    // Acquire: open connection
    Effect.attempt(() -> database.connect()),
    
    // Use: query the database
    conn -> Effect.attempt(() -> conn.query("SELECT * FROM users")),
    
    // Release: always close connection
    conn -> Effect.attempt(() -> conn.close())
);
```

**Guarantees:**
- Release always runs if acquire succeeds
- Release runs even if use fails
- If release fails, use result is still preserved

### fromFuture() - CompletableFuture Integration

Converts a CompletableFuture into an Effect. Blocks safely on virtual threads.

```java
CompletableFuture<String> future = httpClient.getAsync("https://api.example.com");

Effect<State, Throwable, String> effect = 
    Effect.fromFuture(future)
        .map(response -> parseJson(response));
```

### parTraverse() - Parallel Collection Processing

Applies an effect to each element of a collection in parallel.

```java
List<UserId> userIds = List.of(id1, id2, id3);

Effect<State, Throwable, List<User>> effect = 
    Effect.parTraverse(userIds, userId -> 
        Effect.ask(userService, new GetUser(userId), timeout)
    );

// All requests happen in parallel, results collected in order
```

### ensure() - Guaranteed Finalization

Ensures a finalizer effect runs after this effect completes, regardless of success or failure.

```java
Effect.modify(s -> s.processData())
    .ensure(Effect.log("Processing complete"))
    .ensure(Effect.modify(s -> s.cleanup()));

// Finalizers run even if processData() fails
```

### retry() - Automatic Retry with Backoff

Retries an effect up to maxAttempts times with exponential backoff.

```java
Effect.attempt(() -> unreliableService.call())
    .retry(maxAttempts = 3, initialDelay = Duration.ofMillis(100));

// Retry schedule:
// - Attempt 1: immediate
// - Attempt 2: after 100ms
// - Attempt 3: after 200ms
// - Attempt 4: after 400ms
```

## Monadic Operations

### map() - Transform Result

```java
Effect<Integer, Throwable, Integer> effect = Effect.of(10);
Effect<Integer, Throwable, String> mapped = effect.map(n -> "Count: " + n);
```

### flatMap() - Chain Effects

```java
Effect<Integer, Throwable, Integer> effect = Effect.of(10);
Effect<Integer, Throwable, Integer> chained = effect.flatMap(n -> 
    Effect.modify(s -> s + n).andThen(Effect.of(n * 2))
);
```

### andThen() - Sequence Effects

```java
Effect<Integer, Throwable, Void> combined = 
    Effect.modify(s -> s + 10)
        .andThen(Effect.modify(s -> s * 2));
```

## Error Handling

### Error Channel - Checked Exception Support

The Effect monad provides a comprehensive error channel for handling checked exceptions gracefully:

#### attempt() - Catch All Exceptions

Wraps an effect to catch all exceptions and convert them to Failure results:

```java
Effect<State, LoadFile, String> safeLoad = Effect.<State, LoadFile, String>modify(s -> {
    // May throw IOException
    String content = Files.readString(Path.of(msg.filename()));
    return new State(content);
}).attempt();
```

#### handleErrorWith() - Custom Error Recovery

Transform errors into fallback effects (e.g., send error replies):

```java
.when(LoadFile.class, (state, msg, ctx) -> {
    return Effect.<State, LoadFile, Void>modify(s -> {
        String content = Files.readString(Path.of(msg.filename()));  // May throw
        return new State(content);
    })
    .attempt()
    .handleErrorWith((error, s, m, c) -> {
        m.replyTo().tell(new ErrorResponse(error.getMessage()));
        return Effect.identity();  // Keep state unchanged
    });
})
```

#### handleError() - Simple State Recovery

Simpler version when you just need to recover the state:

```java
effect.handleError((error, state, msg, ctx) -> {
    ctx.getLogger().error("Operation failed", error);
    return state;  // Keep current state
})
```

#### tapError() - Log Errors

Perform side effects on errors without changing them:

```java
effect
    .attempt()
    .tapError(error -> 
        ctx.getLogger().error("Operation failed", error)
    )
    .handleErrorWith((err, s, m, c) -> {
        m.replyTo().tell(new ErrorResponse(err.getMessage()));
        return Effect.identity();
    });
```

### recover() - Transform Error to Result

```java
Effect<Integer, Throwable, String> safe = 
    riskyEffect.recover(error -> "Error: " + error.getMessage());
```

### recoverWith() - Run Recovery Effect

```java
Effect<Integer, Throwable, String> safe = 
    riskyEffect.recoverWith(error -> 
        Effect.modify(s -> s + 100).andThen(Effect.of("recovered"))
    );
```

### orElse() - Fallback Effect

```java
Effect<Integer, Throwable, String> robust = 
    riskyEffect.orElse(Effect.of("default"));
```

### attempt() - Catch Exceptions

```java
Effect<Integer, Throwable, Integer> safe = 
    Effect.attempt(() -> riskyOperation());
```

## Interruption Handling (Virtual Thread Cancellation)

Effects in Cajun support proper interruption handling for virtual thread environments. When an actor is stopped or a parent effect cancels a child, the virtual thread is interrupted via `Thread.interrupt()`. These operators allow you to handle that interruption gracefully.

### onInterrupt(Effect) - Register Cleanup Effect

Registers a cleanup effect to run when this effect is interrupted. This is crucial for preventing "zombie" tasks and ensuring resources are cleaned up properly.

```java
// Database cleanup example
Effect<State, Throwable, ResultSet> queryEffect = 
    Effect.attempt(() -> database.longRunningQuery())
        .onInterrupt(Effect.attempt(() -> {
            database.rollback();
            connectionPool.returnConnection(conn);
        }).andThen(Effect.log("Query cancelled, connection returned")));

// HTTP request with cancellation
Effect<State, Throwable, Response> httpEffect = 
    Effect.attempt(() -> httpClient.get(url))
        .onInterrupt(Effect.log("HTTP request cancelled"));
```

**Key Features:**
- Cleanup effect runs when the virtual thread is interrupted
- Preserves interruption status for proper propagation
- Errors in cleanup are logged but don't mask the interruption
- Can chain multiple cleanup effects

### onInterrupt(Runnable) - Simple Cleanup Action

Convenience method for simple cleanup actions without state management.

```java
Effect<State, Throwable, Data> fileEffect = 
    Effect.attempt(() -> processFile(inputStream))
        .onInterrupt(() -> {
            inputStream.close();
            Files.deleteIfExists(tempFile);
        });

// With logging
Effect<State, Throwable, String> effect = 
    Effect.attempt(() -> database.query())
        .onInterrupt(() -> logger.info("Query interrupted"));
```

### checkInterrupted() - Cooperative Cancellation

Checks if the current thread has been interrupted and fails the effect if so. This is useful for long-running computations that should be cancellable.

```java
// Long-running computation with cancellation points
Effect<State, Throwable, List<Result>> effect = 
    Effect.attempt(() -> {
        List<Result> results = new ArrayList<>();
        for (Item item : largeDataset) {
            // Check for interruption periodically
            if (Thread.interrupted()) {
                throw new InterruptedException("Processing cancelled");
            }
            results.add(processItem(item));
        }
        return results;
    });

// Or use checkInterrupted() effect
Effect<State, Throwable, List<Result>> effect2 = 
    Effect.of(largeDataset)
        .flatMap(items -> {
            List<Result> results = new ArrayList<>();
            for (Item item : items) {
                Effect.checkInterrupted().run(state, null, ctx);
                results.add(processItem(item));
            }
            return Effect.of(results);
        });
```

**Use Cases:**
- **Database Operations** - Rollback transactions and return connections on cancellation
- **File Processing** - Close streams and delete temporary files
- **HTTP Requests** - Cancel pending requests and clean up resources
- **Long Computations** - Enable cooperative cancellation in CPU-bound work
- **Actor Shutdown** - Graceful cleanup when actors are stopped by supervisors

**Benefits:**
- **No Zombie Tasks** - Prevents orphaned tasks consuming resources
- **Resource Safety** - Ensures database connections, file handles, and network sockets are released
- **Virtual Thread Native** - Uses `Thread.interrupt()` as designed for virtual threads
- **Composable** - Works seamlessly with all other Effect combinators

## Filtering and Validation

### filter() - Validate Result Value

```java
// With typed error
Effect<Integer, ValidationException, Integer> validated = 
    effect.filter(count -> count > 0, 
                  count -> new ValidationException("Count must be positive, got: " + count));

// With standard exception
Effect<Integer, IllegalArgumentException, Integer> validated2 = 
    effect.filter(count -> count > 0, 
                  count -> new IllegalArgumentException("Invalid count: " + count));
```

### filterOrElse() - Validate with Custom Fallback

More flexible than `filter()` - allows custom error handling without crashing the actor:

```java
// Send error reply on validation failure
.when(Withdraw.class, (state, msg, ctx) -> {
    return Effect.<BankState, Withdraw, Void>modify(s -> 
        new BankState(s.balance() - msg.amount())
    )
    .filterOrElse(
        s -> s.balance() >= 0,  // Validation predicate
        (s, m, c) -> {          // Fallback on failure
            m.replyTo().tell(new Error("Insufficient funds"));
            return Effect.identity();  // Keep original state
        }
    );
})

// Silent rejection (no reply)
effect.filterOrElse(
    state -> state.isValid(),
    (state, msg, ctx) -> Effect.identity()  // Keep state, no reply
)
```

## Side Effects

### tap() - Perform Side Effect with Result

```java
Effect<Integer, Throwable, Result> logged = 
    effect.tap(result -> System.out.println("Result: " + result));
```

### tapState() - Perform Side Effect with State

```java
Effect<Integer, Throwable, Result> logged = 
    effect.tapState(state -> System.out.println("State: " + state));
```

### tapBoth() - Perform Side Effect with Both

```java
Effect<Integer, Throwable, Result> logged = 
    effect.tapBoth((state, result) -> 
        System.out.println("State: " + state + ", Result: " + result));
```

## Actor-Specific Effects

### Messaging

```java
// Send message to another actor
Effect<State, Msg, Void> effect = Effect.tell(targetPid, message);

// Send message to self
Effect<State, Msg, Void> effect = Effect.tellSelf(message);

// Ask pattern
Effect<State, Msg, Response> effect = 
    Effect.ask(targetPid, request, Duration.ofSeconds(5));
```

### Logging

```java
// Log message
Effect<State, Msg, Void> effect = Effect.log("Processing started");

// Log derived from state
Effect<Integer, Throwable, Void> effect = 
    Effect.logState(count -> "Current count: " + count);

// Log error
Effect<State, Msg, Void> effect = Effect.logError("Error occurred");
Effect<State, Msg, Void> effect = Effect.logError("Error", throwable);
```

## Pattern Matching

### Type-Based Routing

```java
Effect<Integer, Throwable, Void> effect = 
    Effect.<Integer, Throwable, Void, CounterMsg>match()
    .when(Increment.class, (state, msg, ctx) -> 
        Effect.modify(s -> s + msg.amount()))
    .when(Decrement.class, (state, msg, ctx) ->
        Effect.modify(s -> s - msg.amount()))
    .otherwise(Effect.log("Unknown message"));
```

### Conditional Effects

```java
Effect<State, Msg, Void> conditional = Effect.when(
    msg -> msg.isValid(),
    Effect.modify(s -> s.process(msg)),
    Effect.log("Invalid message")
);
```

## Complex Workflows

### Multi-Step Processing

```java
Effect<State, Throwable, String> workflow = Effect.of(data)
    .tap(d -> ctx.getLogger().info("Processing: " + d))
    .filter(d -> d.isValid(), 
            d -> new IllegalStateException("Invalid data: " + d))
    .map(d -> d.transform())
    .flatMap(transformed -> 
        Effect.modify(s -> s.update(transformed))
            .andThen(Effect.of("Success")))
    .recover(error -> {
        ctx.getLogger().error("Failed", error);
        return "Failed: " + error.getMessage();
    });
```

### With Ask Pattern

```java
Effect<State, Throwable, Result> workflow = Effect.of(order)
    .filter(o -> o.total() > 0, 
            o -> new IllegalArgumentException("Invalid order total: " + o.total()))
    .flatMap(order ->
        Effect.ask(inventoryActor, new CheckStock(order.items()), Duration.ofSeconds(5))
            .map(inStock -> new Tuple2<>(order, inStock)))
    .flatMap(tuple -> {
        if (!tuple._2()) {
            return Effect.fail(new OutOfStockException());
        }
        return Effect.ask(paymentActor, new ProcessPayment(tuple._1().total()), Duration.ofSeconds(10));
    })
    .flatMap(paymentId ->
        Effect.modify(s -> s.withCompletedOrder(paymentId))
            .andThen(Effect.of(paymentId)))
    .recover(error -> {
        ctx.getLogger().error("Workflow failed", error);
        return null;
    });
```

## Migration from Old API

### Converting BiFunction to Effect

```java
// Old style
BiFunction<Integer, Increment, Integer> oldStyle = 
    (state, msg) -> state + msg.amount();

// Convert to Effect
Effect<Integer, Increment, Void> newStyle = 
    EffectConversions.fromBiFunction(oldStyle);
```

### Converting Effect to StatefulHandler

```java
Effect<State, Message, Result> effect = ...;
StatefulHandler<State, Message> handler = 
    EffectConversions.toStatefulHandler(effect);
```

## Persistence and Actor Modes

### Stateful vs Stateless Actors

Effect-based actors can be spawned in two modes:

```java
// Stateless mode (no persistence) - spawns regular Actor
Pid actor = fromEffect(system, effect, initialState)
    .withId("my-actor")
    .withPersistence(false)  // Default is true
    .spawn();

// Stateful mode (with persistence) - spawns StatefulActor
Pid actor = fromEffect(system, effect, initialState)
    .withId("my-actor")
    .withPersistence(true)   // Enables persistence and recovery
    .spawn();
```

### Pid Rehydration

When using persistence, `Pid` references in state are automatically rehydrated after recovery:

```java
record MyState(Pid otherActor, String data) implements Serializable {}

// Pids are automatically rehydrated with the ActorSystem after snapshot recovery
// No manual intervention needed!
```

## Best Practices

### 1. Use Type Inference

```java
// Good - explicit type parameters
Effect<Integer, Throwable, Void> effect = 
    Effect.<Integer, Throwable, Void, CounterMsg>match()
    .when(Increment.class, (state, msg, ctx) ->
        Effect.modify(s -> s + msg.amount()))  // Types inferred
    .build();
```

### 2. Chain Operations

```java
// Good - fluent chaining
Effect<State, Throwable, Result> effect = Effect.of(value)
    .map(transform)
    .filter(validate, v -> new ValidationException("Invalid value: " + v))
    .tap(log)
    .recover(handleError);
```

### 3. Keep Effects Pure

```java
// Good - pure state transformation
Effect<Integer, Throwable, Void> effect = Effect.modify(s -> s + 1);

// Avoid - side effects in modify
Effect<Integer, Throwable, Void> bad = Effect.modify(s -> {
    System.out.println("Don't do this");  // Side effect!
    return s + 1;
});

// Better - use tap for side effects
Effect<Integer, Throwable, Void> good = Effect.modify(s -> s + 1)
    .tapState(s -> System.out.println("State: " + s));
```

### 4. Handle Errors Explicitly

```java
// Good - explicit error handling with error channel
Effect<State, Msg, Result> safe = riskyEffect
    .attempt()  // Catch exceptions
    .tapError(error -> ctx.getLogger().error("Failed", error))
    .handleErrorWith((err, s, m, c) -> {
        m.replyTo().tell(new ErrorResponse(err.getMessage()));
        return Effect.identity();
    });

// Or use recover for simple cases
Effect<State, Msg, Result> safe = riskyEffect
    .recover(error -> defaultValue)
    .tap(result -> ctx.getLogger().info("Success: " + result));
```

### 5. Use Effect.identity() for No-Op State

```java
// Good - clear intent
.when(QueryKey.class, (state, msg, ctx) -> {
    otherActor.tell(new Query(msg.key()));
    return Effect.identity();  // State unchanged
})

// Avoid - verbose
.when(QueryKey.class, (state, msg, ctx) -> {
    otherActor.tell(new Query(msg.key()));
    return Effect.modify(s -> s);  // Harder to read
})
```

### 6. Use Pattern Matching for Message Routing

```java
// Good - clear message routing
Effect<State, Error, Void> effect = 
    Effect.<State, Error, Void, Msg>match()
    .when(TypeA.class, handleTypeA)
    .when(TypeB.class, handleTypeB)
    .otherwise(Effect.log("Unknown"));
```

## Testing

### Unit Testing Effects

```java
@Test
void testEffect() {
    ActorContext mockContext = mock(ActorContext.class);
    Effect<Integer, Increment, Void> effect = 
        Effect.modify(s -> s + 10);
    
    EffectResult<Integer, Void> result = 
        effect.run(5, new Increment(10), mockContext);
    
    assertEquals(15, result.state());
}
```

### Testing Compositions

```java
@Test
void testComposition() {
    Effect<Integer, Throwable, String> workflow = Effect.of(10)
        .map(n -> n * 2)
        .flatMap(n -> Effect.modify(s -> s + n).andThen(Effect.of("done")));
    
    EffectResult<Integer, String> result = 
        workflow.run(5, msg, mockContext);
    
    assertEquals(25, result.state());  // 5 + (10 * 2)
    assertEquals("done", result.value().orElseThrow());
}
```

## Stream API Integration

Effects work seamlessly with Java's Stream API:

```java
// Convert Effect result to Optional
Optional<Result> opt = effect.toOptional(state, message, context);

// Use in stream operations
List<Result> results = messages.stream()
    .map(msg -> effect.run(state, msg, context))
    .filter(EffectResult::isSuccess)
    .map(r -> r.value().orElseThrow())
    .collect(Collectors.toList());
```

## Reactive Libraries Integration

The Effect monad is designed to work with reactive libraries:

```java
// Convert to CompletableFuture
CompletableFuture<Result> future = CompletableFuture.supplyAsync(() ->
    effect.run(state, message, context).value().orElseThrow()
);

// Use with Project Reactor
Mono<Result> mono = Mono.fromCallable(() ->
    effect.run(state, message, context).value().orElseThrow()
);
```

## Performance Considerations

1. **Effects are lightweight** - They're just functions, no heavy object creation
2. **Lazy evaluation** - Effects only execute when `.run()` is called
3. **No reflection** - All operations are direct method calls
4. **Type-safe** - No runtime type checks needed

## Comparison with Other Approaches

### vs. Traditional BiFunction

| Feature | BiFunction | Effect |
|---------|-----------|--------|
| Composability | ❌ Limited | ✅ Full monadic composition |
| Error Handling | ❌ Exceptions only | ✅ Explicit recovery |
| Side Effects | ❌ Not supported | ✅ Logging, messaging, etc. |
| Type Safety | ✅ Yes | ✅ Yes |
| Testability | ⚠️ Moderate | ✅ Excellent |

### vs. Akka Typed

| Feature | Akka Typed | Cajun Effect |
|---------|-----------|--------------|
| Learning Curve | ⚠️ Steep | ✅ Gentle |
| Java Integration | ⚠️ Scala-focused | ✅ Idiomatic Java |
| Composability | ✅ Good | ✅ Excellent |
| Performance | ✅ Excellent | ✅ Excellent |

## Parallel Execution

The Effect monad provides powerful operators for concurrent execution of effects, enabling efficient parallel workflows within actors.

### parZip() - Parallel Execution with Result Combination

Runs two effects in parallel and combines their results:

```java
.when(GetDashboard.class, (state, msg, ctx) -> {
    Effect<State, GetDashboard, UserProfile> getProfile = 
        Effect.ask(profileService, new GetProfile(msg.userId()), Duration.ofSeconds(5));
    
    Effect<State, GetDashboard, List<Order>> getOrders = 
        Effect.ask(orderService, new GetOrders(msg.userId()), Duration.ofSeconds(5));
    
    // Run both in parallel and combine results
    return getProfile.parZip(getOrders, (profile, orders) -> 
        new Dashboard(profile, orders)
    )
    .andThen(Effect.tell(msg.replyTo(), result));
})
```

**Key Features**:
- ✅ Both effects run concurrently
- ✅ Custom combiner function for results
- ✅ Fails fast if either effect fails
- ✅ Uses same initial state for both

### parSequence() - Parallel Execution of Multiple Effects

Runs a list of effects in parallel and collects all results:

```java
.when(AggregateData.class, (state, msg, ctx) -> {
    List<Effect<State, AggregateData, Data>> queries = msg.sources().stream()
        .map(source -> Effect.ask(source, new Query(msg.key()), Duration.ofSeconds(5)))
        .toList();
    
    // Execute all queries in parallel
    return Effect.parSequence(queries)
        .map(results -> new AggregatedData(results))
        .andThen(Effect.tell(msg.replyTo(), result));
})
```

**Key Features**:
- ✅ Runs N effects concurrently
- ✅ Collects all results in a list
- ✅ Fails if any effect fails
- ✅ Maintains result order

### sequence() - Sequential Execution of Multiple Effects

Runs a list of effects sequentially, threading state through each:

```java
.when(ProcessPipeline.class, (state, msg, ctx) -> {
    List<Effect<State, ProcessPipeline, Void>> steps = List.of(
        Effect.modify(s -> s.validate()),
        Effect.modify(s -> s.transform()),
        Effect.modify(s -> s.persist())
    );
    
    // Execute steps sequentially, each seeing the updated state
    return Effect.sequence(steps)
        .andThen(Effect.tell(msg.replyTo(), new Success()));
})
```

**Key Features**:
- ✅ Sequential execution (one after another)
- ✅ State is threaded through each effect
- ✅ Each effect sees the updated state from previous
- ✅ Fails on first error

### race() - First-to-Complete Wins

Races multiple effects and returns whichever completes first:

```java
.when(GetData.class, (state, msg, ctx) -> {
    Effect<State, GetData, Data> primary = 
        Effect.ask(primaryService, new Query(msg.key()), Duration.ofSeconds(2));
    
    Effect<State, GetData, Data> cache = 
        Effect.ask(cacheService, new GetCached(msg.key()), Duration.ofSeconds(5));
    
    // Use whichever responds first
    return primary.race(cache)
        .andThen(Effect.tell(msg.replyTo(), result));
})
```

**Use Cases**:
- Primary service with cache fallback
- Multiple redundant data sources
- Fastest response optimization

### withTimeout() - Timeout Protection

Wraps an effect with a timeout:

```java
.when(SlowQuery.class, (state, msg, ctx) -> {
    return Effect.ask(slowService, new Query(msg.key()), Duration.ofSeconds(30))
        .withTimeout(Duration.ofSeconds(5))
        .handleErrorWith((err, s, m, c) -> {
            // Handle timeout
            m.replyTo().tell(new TimeoutError());
            return Effect.identity();
        });
})
```

**Key Features**:
- ✅ Prevents hanging on slow operations
- ✅ Returns TimeoutException on timeout
- ✅ Composable with error handlers

### Complete Example: Robust Data Aggregation

```java
.when(GetFullReport.class, (state, msg, ctx) -> {
    // Query 3 services in parallel with timeout protection
    Effect<State, GetFullReport, UserProfile> getProfile = 
        Effect.ask(profileService, new GetProfile(msg.userId()), Duration.ofSeconds(5))
            .withTimeout(Duration.ofSeconds(3));
    
    Effect<State, GetFullReport, List<Order>> getOrders = 
        Effect.ask(orderService, new GetOrders(msg.userId()), Duration.ofSeconds(5))
            .withTimeout(Duration.ofSeconds(3));
    
    Effect<State, GetFullReport, Stats> getStats = 
        Effect.ask(statsService, new GetStats(msg.userId()), Duration.ofSeconds(5))
            .withTimeout(Duration.ofSeconds(3));
    
    // Combine all results in parallel with error recovery
    return getProfile.parZip(getOrders, (profile, orders) -> 
        new Tuple2<>(profile, orders)
    )
    .parZip(getStats, (tuple, stats) -> 
        new FullReport(tuple._1(), tuple._2(), stats)
    )
    .handleErrorWith((err, s, m, c) -> {
        // Fallback to cached data on any error
        return Effect.ask(cacheService, new GetCachedReport(msg.userId()), Duration.ofSeconds(1));
    })
    .andThen(Effect.tell(msg.replyTo(), result));
})
```

### Performance Benefits

| Operator | Execution | Use Case |
|----------|-----------|----------|
| `parZip` | Parallel (2 effects) | Combine independent queries |
| `parSequence` | Parallel (N effects) | Aggregate multiple sources |
| `sequence` | Sequential | Pipeline with state threading |
| `race` | Parallel (first wins) | Redundancy & fallback |
| `withTimeout` | Single with limit | Prevent hanging |

**Latency Reduction**: Parallel operators can reduce total latency from `O(n)` to `O(1)` when querying multiple independent services.

## Conclusion

The Effect monad provides a powerful, composable, and type-safe way to build functional actors while maintaining the actor-oriented nature of Cajun. It enables:

- **Functional programming patterns** in an actor context
- **Better error handling** with explicit recovery
- **Composable behaviors** through monadic operations
- **Parallel execution** for efficient concurrent workflows
- **Testable code** with pure functions
- **Type safety** throughout
- **Seamless integration** with Java's ecosystem

Start with simple effects and gradually compose them into complex workflows. The API is designed to be intuitive for Java developers while providing the power of functional programming.
