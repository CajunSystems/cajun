# Effect Monad API Guide

## Overview

The Effect monad provides a composable, type-safe way to build actor behaviors using functional programming patterns. It integrates seamlessly with Java's Stream API and reactive libraries while maintaining the actor-oriented nature of Cajun.

## Key Features

- **Idiomatic Java naming** - Uses `.of()` instead of `.pure()`, familiar to Java developers
- **Composable** - Build complex behaviors from simple building blocks
- **Type-safe** - State transitions and message types are compile-time checked
- **Error handling** - Explicit error recovery with `.recover()` and `.orElse()`
- **Testable** - Pure functions that are easy to test without spawning actors
- **Stream-compatible** - Works with Java's Stream API and reactive libraries

## Quick Start

### Basic Counter Example

```java
sealed interface CounterMsg {}
record Increment(int amount) implements CounterMsg {}
record Decrement(int amount) implements CounterMsg {}
record GetCount(Pid replyTo) implements CounterMsg {}

// Define behavior using effects
Effect<Integer, CounterMsg, Void> counterEffect = Effect.match()
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

### Effect<State, Message, Result>

An `Effect` represents a computation that:
- Takes a **state** and **message**
- Produces a **new state**
- May produce a **result** value
- May perform **side effects** (logging, sending messages, etc.)
- May **fail** with an error

### EffectResult<State, Result>

The result of executing an effect, which can be:
- **Success** - Effect executed successfully with a result value
- **NoResult** - Effect executed successfully but produced no result (state change only)
- **Failure** - Effect execution failed with an error

## Factory Methods

### Creating Effects

```java
// Return a value without changing state
Effect<Integer, Msg, String> effect = Effect.of("success");

// Return current state as result
Effect<Integer, Msg, Integer> effect = Effect.state();

// Modify state
Effect<Integer, Msg, Void> effect = Effect.modify(s -> s + 10);

// Set state to specific value
Effect<Integer, Msg, Void> effect = Effect.setState(100);

// Use both state and message
Effect<Integer, String, Void> effect = 
    Effect.fromTransition((state, msg) -> state + msg.length());

// Create failing effect
Effect<Integer, Msg, String> effect = 
    Effect.fail(new IllegalStateException("error"));

// No-op effect
Effect<Integer, Msg, Void> effect = Effect.none();
```

## Monadic Operations

### map() - Transform Result

```java
Effect<Integer, Msg, Integer> effect = Effect.of(10);
Effect<Integer, Msg, String> mapped = effect.map(n -> "Count: " + n);
```

### flatMap() - Chain Effects

```java
Effect<Integer, Msg, Integer> effect = Effect.of(10);
Effect<Integer, Msg, Integer> chained = effect.flatMap(n -> 
    Effect.modify(s -> s + n).andThen(Effect.of(n * 2))
);
```

### andThen() - Sequence Effects

```java
Effect<Integer, Msg, Void> combined = 
    Effect.modify(s -> s + 10)
        .andThen(Effect.modify(s -> s * 2));
```

## Error Handling

### recover() - Transform Error to Result

```java
Effect<Integer, Msg, String> safe = 
    riskyEffect.recover(error -> "Error: " + error.getMessage());
```

### recoverWith() - Run Recovery Effect

```java
Effect<Integer, Msg, String> safe = 
    riskyEffect.recoverWith(error -> 
        Effect.modify(s -> s + 100).andThen(Effect.of("recovered"))
    );
```

### orElse() - Fallback Effect

```java
Effect<Integer, Msg, String> robust = 
    riskyEffect.orElse(Effect.of("default"));
```

### attempt() - Catch Exceptions

```java
Effect<Integer, Msg, Integer> safe = 
    Effect.attempt(() -> riskyOperation());
```

## Filtering and Validation

```java
Effect<Integer, Msg, Integer> validated = 
    effect.filter(count -> count > 0, "Count must be positive");
```

## Side Effects

### tap() - Perform Side Effect with Result

```java
Effect<Integer, Msg, Result> logged = 
    effect.tap(result -> System.out.println("Result: " + result));
```

### tapState() - Perform Side Effect with State

```java
Effect<Integer, Msg, Result> logged = 
    effect.tapState(state -> System.out.println("State: " + state));
```

### tapBoth() - Perform Side Effect with Both

```java
Effect<Integer, Msg, Result> logged = 
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
Effect<Integer, Msg, Void> effect = 
    Effect.logState(count -> "Current count: " + count);

// Log error
Effect<State, Msg, Void> effect = Effect.logError("Error occurred");
Effect<State, Msg, Void> effect = Effect.logError("Error", throwable);
```

## Pattern Matching

### Type-Based Routing

```java
Effect<Integer, CounterMsg, Void> effect = Effect.match()
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
Effect<State, Msg, String> workflow = Effect.of(data)
    .tap(d -> ctx.getLogger().info("Processing: " + d))
    .filter(d -> d.isValid(), "Invalid data")
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
Effect<State, Msg, Result> workflow = Effect.of(order)
    .filter(o -> o.total() > 0, "Invalid order")
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

## Best Practices

### 1. Use Type Inference

```java
// Good - types inferred from variable declaration
Effect<Integer, CounterMsg, Void> effect = Effect.match()
    .when(Increment.class, (state, msg, ctx) ->
        Effect.modify(s -> s + msg.amount()))  // Types inferred
    .build();
```

### 2. Chain Operations

```java
// Good - fluent chaining
Effect<State, Msg, Result> effect = Effect.of(value)
    .map(transform)
    .filter(validate, "Invalid")
    .tap(log)
    .recover(handleError);
```

### 3. Keep Effects Pure

```java
// Good - pure state transformation
Effect<Integer, Msg, Void> effect = Effect.modify(s -> s + 1);

// Avoid - side effects in modify
Effect<Integer, Msg, Void> bad = Effect.modify(s -> {
    System.out.println("Don't do this");  // Side effect!
    return s + 1;
});

// Better - use tap for side effects
Effect<Integer, Msg, Void> good = Effect.modify(s -> s + 1)
    .tapState(s -> System.out.println("State: " + s));
```

### 4. Handle Errors Explicitly

```java
// Good - explicit error handling
Effect<State, Msg, Result> safe = riskyEffect
    .recover(error -> defaultValue)
    .tap(result -> ctx.getLogger().info("Success: " + result));
```

### 5. Use Pattern Matching for Message Routing

```java
// Good - clear message routing
Effect<State, Msg, Void> effect = Effect.match()
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
    Effect<Integer, Msg, String> workflow = Effect.of(10)
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

## Conclusion

The Effect monad provides a powerful, composable, and type-safe way to build functional actors while maintaining the actor-oriented nature of Cajun. It enables:

- **Functional programming patterns** in an actor context
- **Better error handling** with explicit recovery
- **Composable behaviors** through monadic operations
- **Testable code** with pure functions
- **Type safety** throughout
- **Seamless integration** with Java's ecosystem

Start with simple effects and gradually compose them into complex workflows. The API is designed to be intuitive for Java developers while providing the power of functional programming.
