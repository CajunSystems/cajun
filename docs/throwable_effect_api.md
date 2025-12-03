# ThrowableEffect API Guide

## Overview

`ThrowableEffect<State, Result>` is a simplified, stack-safe alternative to `Effect<State, Message, Result>`. It removes the Message type parameter, making the API less verbose while maintaining full functionality through the use of a `Trampoline` for stack safety.

## Key Features

- **Less Verbose** - Only 2 type parameters instead of 3
- **Stack-Safe** - Uses `Trampoline` to prevent StackOverflowError on deep compositions
- **Built-in Error Channel** - Throwable handling is part of the type
- **Message Type at Match** - Type constraint only where needed
- **Fully Compatible** - All operators from Effect are available

## Quick Comparison

### Before (Effect)
```java
Effect<BankState, BankMsg, Void> behavior = 
    Effect.<BankState, BankMsg, Void>match()
        .when(Deposit.class, (state, msg, ctx) -> 
            Effect.<BankState, BankMsg>modify(s -> 
                new BankState(s.balance() + msg.amount())
            )
        )
        .build();
```

### After (ThrowableEffect)
```java
ThrowableEffect<BankState, Void> behavior = 
    ThrowableEffect.<BankState>match()
        .when(Deposit.class, (state, msg, ctx) -> 
            ThrowableEffect.modify(s -> 
                new BankState(s.balance() + msg.amount())
            )
        )
        .build();
```

## Factory Methods

### Creating Effects

```java
// Return a constant value
ThrowableEffect<Integer, String> effect = ThrowableEffect.of("success");

// Return current state as result
ThrowableEffect<Integer, Integer> effect = ThrowableEffect.state();

// Modify state
ThrowableEffect<Integer, Void> effect = ThrowableEffect.modify(s -> s + 10);

// Set state to specific value
ThrowableEffect<Integer, Void> effect = ThrowableEffect.setState(100);

// Keep state unchanged (identity)
ThrowableEffect<Integer, Void> effect = ThrowableEffect.identity();

// Create a failure
ThrowableEffect<Integer, String> effect = ThrowableEffect.fail(new RuntimeException("error"));
```

## Stack Safety

The key innovation of `ThrowableEffect` is its use of `Trampoline` for stack-safe evaluation:

```java
// This would cause StackOverflowError with regular Effect
ThrowableEffect<Integer, Integer> effect = ThrowableEffect.of(0);

for (int i = 0; i < 10000; i++) {
    effect = effect.map(x -> x + 1);
}

// Runs without stack overflow!
EffectResult<Integer, Integer> result = effect.run(0, msg, context);
```

### How It Works

Instead of eagerly evaluating compositions, `ThrowableEffect` returns a `Trampoline` that describes the computation:

```java
@FunctionalInterface
public interface ThrowableEffect<S, R> {
    // Returns a Trampoline for lazy, stack-safe evaluation
    Trampoline<EffectResult<S, R>> runT(S state, Object message, ActorContext context);
    
    // Convenience method that runs the trampoline
    default EffectResult<S, R> run(S state, Object message, ActorContext context) {
        return runT(state, message, context).run();
    }
}
```

## Monadic Operations

All operations are stack-safe:

```java
// Map - transform result
ThrowableEffect<Integer, String> effect = ThrowableEffect.of(10)
    .map(x -> x * 2)
    .map(x -> "Result: " + x);

// FlatMap - chain effects
ThrowableEffect<Integer, Integer> effect = ThrowableEffect.of(5)
    .flatMap(x -> ThrowableEffect.of(x * 2))
    .flatMap(x -> ThrowableEffect.of(x + 10));

// AndThen - sequence effects
ThrowableEffect<Integer, String> effect = ThrowableEffect.<Integer>modify(s -> s + 10)
    .andThen(ThrowableEffect.of("done"));
```

## Error Channel

Built-in Throwable handling:

```java
// Catch exceptions
ThrowableEffect<Integer, String> safe = riskyEffect.attempt();

// Handle errors with recovery effect
ThrowableEffect<Integer, String> recovered = effect
    .handleErrorWith((err, s, m, c) -> 
        ThrowableEffect.of("recovered: " + err.getMessage())
    );

// Handle errors by recovering state
ThrowableEffect<Integer, String> recovered = effect
    .handleError((err, s, m, c) -> 0);  // Reset state to 0

// Tap into errors for logging
ThrowableEffect<Integer, String> logged = effect
    .tapError(err -> logger.error("Error occurred", err));
```

## Validation

```java
.when(Withdraw.class, (state, msg, ctx) -> {
    return ThrowableEffect.<BankState>modify(s -> 
        new BankState(s.balance() - msg.amount())
    )
    .filterOrElse(
        s -> s.balance() >= 0,  // Validation predicate
        ThrowableEffect.<BankState, Void>modify(s -> {
            msg.replyTo().tell(new Error("Insufficient funds"));
            return s;  // Keep original state
        })
    );
})
```

## Parallel Execution

All parallel operators are stack-safe:

```java
// ParZip - combine two effects
ThrowableEffect<State, Dashboard> dashboard = 
    getProfile.parZip(getOrders, (profile, orders) -> 
        new Dashboard(profile, orders)
    );

// ParSequence - run N effects in parallel
List<ThrowableEffect<State, Data>> queries = sources.stream()
    .map(source -> ThrowableEffect.ask(source, query))
    .toList();

ThrowableEffect<State, List<Data>> allData = 
    ThrowableEffect.parSequence(queries);

// Sequence - run sequentially with state threading
List<ThrowableEffect<State, Void>> steps = List.of(
    ThrowableEffect.modify(s -> s.validate()),
    ThrowableEffect.modify(s -> s.transform()),
    ThrowableEffect.modify(s -> s.persist())
);

ThrowableEffect<State, List<Void>> pipeline = 
    ThrowableEffect.sequence(steps);
```

## Pattern Matching

The Message type is only specified at the match level:

```java
record Increment(int amount) {}
record Decrement(int amount) {}
record GetCount(Pid replyTo) {}

ThrowableEffect<Integer, Void> counter = ThrowableEffect.<Integer>match()
    .when(Increment.class, (state, msg, ctx) -> 
        ThrowableEffect.modify(s -> s + msg.amount())
    )
    .when(Decrement.class, (state, msg, ctx) -> 
        ThrowableEffect.modify(s -> s - msg.amount())
    )
    .when(GetCount.class, (state, msg, ctx) -> 
        ThrowableEffect.<Integer, Void>state()
            .andThen((state2, msg2, ctx2) -> {
                msg.replyTo().tell(state2);
                return Trampoline.done(EffectResult.noResult(state2));
            })
    )
    .build();
```

## Trampoline API

The `Trampoline` data structure enables stack-safe recursion:

```java
// Create a completed trampoline
Trampoline<Integer> done = Trampoline.done(42);

// Suspend a computation
Trampoline<Integer> suspended = Trampoline.more(() -> 
    Trampoline.done(42)
);

// Delay a computation
Trampoline<Integer> delayed = Trampoline.delay(() -> expensiveComputation());

// Map and flatMap are stack-safe
Trampoline<Integer> result = trampoline
    .map(x -> x * 2)
    .flatMap(x -> Trampoline.done(x + 10));

// Run the trampoline
Integer value = result.run();  // Iterative evaluation - no stack growth
```

## When to Use ThrowableEffect vs Effect

### Use ThrowableEffect When:
- ✅ You want less verbose type signatures
- ✅ You need deep effect compositions (>100 chained operations)
- ✅ You prefer built-in error handling
- ✅ You're starting a new project

### Use Effect When:
- ✅ You need explicit Message type constraints throughout
- ✅ You have existing code using Effect
- ✅ You prefer the three-parameter style

## Migration from Effect

ThrowableEffect is designed to coexist with Effect. You can gradually migrate:

```java
// Old Effect code
Effect<State, Msg, Result> oldEffect = ...;

// New ThrowableEffect code
ThrowableEffect<State, Result> newEffect = ...;

// They can be used together in the same codebase
```

## Performance

- **Stack Safety**: Prevents StackOverflowError for deep compositions
- **Overhead**: Minimal - trampoline adds one level of indirection
- **Parallel Operations**: Same performance as Effect (uses CompletableFuture)
- **Memory**: Slightly lower than Effect (one less type parameter)

## Best Practices

1. **Use `identity()` instead of `modify(s -> s)`**
   ```java
   // Good
   ThrowableEffect.identity()
   
   // Verbose
   ThrowableEffect.modify(s -> s)
   ```

2. **Prefer `filterOrElse()` over manual validation**
   ```java
   // Good
   effect.filterOrElse(
       s -> s.isValid(),
       fallbackEffect
   )
   
   // Manual
   effect.andThen((s, m, c) -> {
       if (!s.isValid()) {
           return fallbackEffect.runT(s, m, c);
       }
       return Trampoline.done(EffectResult.noResult(s));
   })
   ```

3. **Use `attempt()` for exception-prone operations**
   ```java
   ThrowableEffect.of(() -> riskyOperation())
       .attempt()
       .handleErrorWith((err, s, m, c) -> fallbackEffect);
   ```

4. **Leverage parallel operators for independent queries**
   ```java
   // Sequential - slow
   ThrowableEffect<State, Data> data = query1.andThen(query2);
   
   // Parallel - fast
   ThrowableEffect<State, Data> data = query1.parZip(query2, combiner);
   ```

## Summary

`ThrowableEffect` provides a cleaner, stack-safe API for building functional actors in Cajun. Its simplified type signature and built-in trampoline make it ideal for complex effect compositions while maintaining full compatibility with the existing Effect ecosystem.
