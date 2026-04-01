# Effect Monad - New Features Summary

This document summarizes the latest enhancements to the Cajun Effect monad API.

## 1. Effect.identity() - Clean No-Op State

**Problem**: `Effect.modify(s -> s)` was verbose and unclear in intent.

**Solution**: Added `Effect.identity()` for cleaner code when state doesn't change.

```java
// Before
.when(QueryKey.class, (state, msg, ctx) -> {
    otherActor.tell(new Query(msg.key()));
    return Effect.modify(s -> s);  // Verbose
})

// After
.when(QueryKey.class, (state, msg, ctx) -> {
    otherActor.tell(new Query(msg.key()));
    return Effect.identity();  // Clear intent
})
```

**Benefits**:
- ✅ More readable code
- ✅ Clear semantic meaning
- ✅ Less cognitive overhead

---

## 2. filterOrElse() - Rich Validation with Custom Fallbacks

**Problem**: `filter()` only accepts an error message and crashes the actor on validation failure.

**Solution**: Added `filterOrElse()` for graceful error handling with custom fallback effects.

```java
default Effect<State, Message, Result> filterOrElse(
    Predicate<State> predicate, 
    Effect<State, Message, Result> fallback)
```

### Use Cases

#### Send Error Reply on Validation Failure
```java
.when(Withdraw.class, (state, msg, ctx) -> {
    return Effect.<BankState, Withdraw, Void>modify(s -> 
        new BankState(s.balance() - msg.amount())
    )
    .filterOrElse(
        s -> s.balance() >= 0,  // Validation
        (s, m, c) -> {          // Fallback on failure
            m.replyTo().tell(new Error("Insufficient funds"));
            return Effect.identity();  // Keep original state
        }
    );
})
```

#### Silent Rejection (No Reply)
```java
effect.filterOrElse(
    state -> state.isValid(),
    (state, msg, ctx) -> Effect.identity()  // Keep state, no reply
)
```

**Benefits**:
- ✅ Graceful error handling (no actor crashes)
- ✅ Send error responses to clients
- ✅ State preservation on validation failure
- ✅ Composable with other Effect operations

---

## 3. Error Channel - Checked Exception Handling

**Problem**: Checked exceptions require manual try-catch blocks, cluttering the code.

**Solution**: Added comprehensive error channel with `attempt()`, `handleErrorWith()`, `handleError()`, and `tapError()`.

### attempt() - Catch All Exceptions

Wraps an effect to catch all exceptions and convert them to Failure results:

```java
Effect<State, LoadFile, String> safeLoad = Effect.<State, LoadFile, String>modify(s -> {
    // May throw IOException
    String content = Files.readString(Path.of(msg.filename()));
    return new State(content);
}).attempt();
```

### handleErrorWith() - Custom Error Recovery

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

### handleError() - Simple State Recovery

Simpler version when you just need to recover the state:

```java
effect.handleError((error, state, msg, ctx) -> {
    ctx.getLogger().error("Operation failed", error);
    return state;  // Keep current state
})
```

### tapError() - Log Errors

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

### Complete Error Handling Pipeline

```java
.when(ProcessFile.class, (state, msg, ctx) -> {
    return Effect.<State, ProcessFile, Void>modify(s -> {
        // May throw IOException, ParseException, etc.
        String content = Files.readString(Path.of(msg.filename()));
        Data parsed = parser.parse(content);
        return new State(parsed);
    })
    .attempt()                                    // Catch exceptions
    .tapError(e -> ctx.getLogger().error("Failed", e))  // Log errors
    .handleErrorWith((err, s, m, c) -> {         // Handle gracefully
        m.replyTo().tell(new ErrorResponse(err.getMessage()));
        return Effect.identity();
    });
})
```

**Benefits**:
- ✅ Clean exception handling without try-catch
- ✅ Composable error recovery
- ✅ Send error responses to clients
- ✅ Actor continues processing (no crashes)
- ✅ Type-safe error handling

---

## 4. Dual-Mode Actor Spawning

**Problem**: All Effect-based actors were spawned as StatefulActors with persistence overhead, even when persistence wasn't needed.

**Solution**: Refactored `EffectActorBuilder` to support two modes.

### Stateless Mode (No Persistence)

Spawns a regular `Actor` with in-memory state only:

```java
Pid actor = fromEffect(system, effect, initialState)
    .withId("my-actor")
    .withPersistence(false)  // Spawns regular Actor
    .spawn();
```

### Stateful Mode (With Persistence)

Spawns a `StatefulActor` with persistence and recovery:

```java
Pid actor = fromEffect(system, effect, initialState)
    .withId("my-actor")
    .withPersistence(true)   // Spawns StatefulActor (default)
    .spawn();
```

**Benefits**:
- ✅ No persistence overhead for simple actors
- ✅ Explicit opt-in to persistence
- ✅ Clean separation of concerns
- ✅ Better performance for stateless use cases

---

## 5. Automatic Pid Rehydration

**Problem**: When actor state is recovered from persistence, `Pid` objects are deserialized with `null` ActorSystem references, causing `NullPointerException`.

**Solution**: Added automatic Pid rehydration during state recovery.

### Implementation

1. **Added `Pid.withSystem()`** - Method to rehydrate Pids
2. **Created `PidRehydrator`** - Utility that recursively rehydrates all Pids in state objects
3. **Integrated into `StatefulActor`** - Automatic rehydration after snapshot recovery

### Usage

```java
record MyState(Pid otherActor, String data) implements Serializable {}

// Pids are automatically rehydrated with the ActorSystem after snapshot recovery
// No manual intervention needed!
```

### How It Works

```java
// In StatefulActor.recoverFromSnapshotAndJournal()
State recoveredState = snapshot.getState();

// Rehydrate any Pid references in the state with the current ActorSystem
recoveredState = PidRehydrator.rehydrate(recoveredState, getSystem());

currentState.set(recoveredState);
```

The `PidRehydrator` recursively traverses:
- Records
- Collections (List, Set, etc.)
- Maps
- Nested objects

**Benefits**:
- ✅ Transparent - no manual rehydration needed
- ✅ Automatic - works for any state structure
- ✅ Safe - handles circular references
- ✅ Efficient - uses identity map to avoid reprocessing

---

## Migration Guide

### Updating Existing Code

#### 1. Replace `Effect.modify(s -> s)` with `Effect.identity()`

```java
// Old
return Effect.modify(s -> s);

// New
return Effect.identity();
```

#### 2. Add Error Handling to File I/O Operations

```java
// Old - may crash actor
.when(LoadFile.class, (state, msg, ctx) -> {
    String content = Files.readString(Path.of(msg.filename()));
    return Effect.setState(new State(content));
})

// New - graceful error handling
.when(LoadFile.class, (state, msg, ctx) -> {
    return Effect.<State, LoadFile, Void>modify(s -> {
        String content = Files.readString(Path.of(msg.filename()));
        return new State(content);
    })
    .attempt()
    .handleErrorWith((err, s, m, c) -> {
        m.replyTo().tell(new ErrorResponse(err.getMessage()));
        return Effect.identity();
    });
})
```

#### 3. Use `filterOrElse()` for Validation

```java
// Old - crashes actor on validation failure
.when(Withdraw.class, (state, msg, ctx) -> {
    if (state.balance() < msg.amount()) {
        throw new IllegalStateException("Insufficient funds");
    }
    return Effect.setState(new State(state.balance() - msg.amount()));
})

// New - graceful validation
.when(Withdraw.class, (state, msg, ctx) -> {
    return Effect.<State, Withdraw, Void>modify(s -> 
        new State(s.balance() - msg.amount())
    )
    .filterOrElse(
        s -> s.balance() >= 0,
        (s, m, c) -> {
            m.replyTo().tell(new Error("Insufficient funds"));
            return Effect.identity();
        }
    );
})
```

#### 4. Disable Persistence for Simple Actors

```java
// Old - always uses StatefulActor
Pid actor = fromEffect(system, effect, initialState)
    .withId("my-actor")
    .spawn();

// New - use regular Actor when persistence not needed
Pid actor = fromEffect(system, effect, initialState)
    .withId("my-actor")
    .withPersistence(false)  // Better performance
    .spawn();
```

---

## 6. Parallel Execution Operators

**Added**: `parZip()`, `parSequence()`, `sequence()`, `race()`, `withTimeout()`

### The Problem

No built-in support for concurrent or sequential execution of multiple effects, forcing manual CompletableFuture management.

### The Solution

Added comprehensive parallel and sequential execution operators:

#### parZip() - Parallel Combination

```java
.when(GetDashboard.class, (state, msg, ctx) -> {
    Effect<State, GetDashboard, UserProfile> getProfile = 
        Effect.ask(profileService, new GetProfile(msg.userId()), Duration.ofSeconds(5));
    
    Effect<State, GetDashboard, List<Order>> getOrders = 
        Effect.ask(orderService, new GetOrders(msg.userId()), Duration.ofSeconds(5));
    
    return getProfile.parZip(getOrders, (profile, orders) -> 
        new Dashboard(profile, orders)
    );
})
```

#### parSequence() - Parallel Aggregation

```java
List<Effect<State, Msg, Data>> queries = sources.stream()
    .map(source -> Effect.ask(source, new Query(key), Duration.ofSeconds(5)))
    .toList();

return Effect.parSequence(queries)
    .map(results -> new AggregatedData(results));
```

#### sequence() - Sequential Pipeline

```java
List<Effect<State, Msg, Void>> steps = List.of(
    Effect.modify(s -> s.validate()),
    Effect.modify(s -> s.transform()),
    Effect.modify(s -> s.persist())
);

return Effect.sequence(steps);  // Each step sees updated state
```

#### race() - First Wins

```java
Effect<State, Msg, Data> primary = 
    Effect.ask(primaryService, new Query(id), Duration.ofSeconds(2));

Effect<State, Msg, Data> cache = 
    Effect.ask(cacheService, new GetCached(id), Duration.ofSeconds(5));

return primary.race(cache);  // Use fastest response
```

#### withTimeout() - Timeout Protection

```java
return Effect.ask(slowService, new Query(id), Duration.ofSeconds(30))
    .withTimeout(Duration.ofSeconds(5))
    .handleErrorWith((err, s, m, c) -> Effect.of(cachedValue));
```

### Benefits

- ✅ **Parallel execution** reduces latency from O(n) to O(1)
- ✅ **Sequential pipelines** with state threading
- ✅ **Timeout protection** prevents hanging
- ✅ **Race conditions** for redundancy and fallback
- ✅ **Composable** with error handling

---

## Summary

| Feature | Problem Solved | Key Benefit |
|---------|---------------|-------------|
| `Effect.identity()` | Verbose `modify(s -> s)` | Cleaner, more readable code |
| `filterOrElse()` | Limited validation | Graceful error handling with custom fallbacks |
| Error Channel | Manual try-catch | Clean checked exception handling |
| Parallel Operators | Manual concurrency | Efficient parallel & sequential execution |
| Dual-Mode Spawning | Unnecessary persistence overhead | Better performance for stateless actors |
| Pid Rehydration | NPE after recovery | Transparent persistence support |

All features are **production-ready** and **fully tested**! 🚀
