# Effect Monad Refactoring Guide

## Overview

The Effect monad has been refactored to provide a simpler, more powerful API with built-in stack safety and better error handling.

## Key Changes

### 1. Type Signature Change

**Old:**
```java
Effect<State, Message, Result>
```

**New:**
```java
Effect<State, Error, Result>
```

The `Message` type parameter has been moved from the interface level to the `match()` level, making the Effect monad less verbose and more flexible.

### 2. Stack Safety with Trampoline

All Effect operations now return `Trampoline<EffectResult<State, Result>>` instead of directly returning `EffectResult`. This ensures stack-safe execution even with deep chains of `map`, `flatMap`, and other operators.

**Example:**
```java
// Old (could cause stack overflow)
Effect<Integer, String, Integer> effect = Effect.of(1)
    .map(x -> x + 1)
    .map(x -> x * 2)
    // ... thousands of maps ...
    .map(x -> x - 1);

// New (stack-safe)
Effect<Integer, Throwable, Integer> effect = Effect.of(1)
    .map(x -> x + 1)
    .map(x -> x * 2)
    // ... thousands of maps - no stack overflow! ...
    .map(x -> x - 1);
```

### 3. Pattern Matching with Message Type

The `match()` method now takes the Message type as a type parameter:

**Old:**
```java
Effect<BankState, BankMsg, Void> behavior = 
    Effect.<BankState, BankMsg, Void>match()
        .when(Deposit.class, (state, msg, ctx) -> ...)
        .build();
```

**New:**
```java
Effect<BankState, Throwable, Void> behavior = 
    Effect.<BankState, Throwable, Void, BankMsg>match()
        .when(Deposit.class, (state, msg, ctx) -> ...)
        .build();
```

### 4. Error Type

The Error type parameter is now explicit (typically `Throwable`), providing better type safety for error handling.

## Migration Guide

### Step 1: Update Type Signatures

Replace all occurrences of:
```java
Effect<State, Message, Result>
```

With:
```java
Effect<State, Throwable, Result>
```

### Step 2: Update Match Calls

Change:
```java
Effect.<State, Message, Result>match()
```

To:
```java
Effect.<State, Throwable, Result, Message>match()
```

### Step 3: Update Effect Implementations

Change effect lambdas to return `Trampoline`:

**Old:**
```java
Effect<Integer, String, Integer> effect = (state, msg, ctx) -> 
    EffectResult.success(state + 1, state + 1);
```

**New:**
```java
Effect<Integer, Throwable, Integer> effect = (state, msg, ctx) -> 
    Trampoline.done(EffectResult.success(state + 1, state + 1));
```

### Step 4: Update Factory Methods

All factory methods now return stack-safe effects:

```java
// These all return Trampoline internally
Effect.of(value)
Effect.modify(s -> s + 1)
Effect.state()
Effect.identity()
Effect.fail(error)
```

## New Features

### 1. Enhanced Error Handling

```java
Effect<State, Throwable, Result> effect = ...;

// Recover from errors
effect.recover(err -> defaultValue)

// Recover with another effect
effect.recoverWith(err -> fallbackEffect)

// Use fallback effect on failure
effect.orElse(fallbackEffect)

// Tap into errors for logging
effect.tapError(err -> log.error("Failed", err))
```

### 2. Sequential Composition

```java
List<Effect<State, Throwable, Result>> effects = List.of(
    effect1, effect2, effect3
);

// Execute sequentially, threading state through
Effect<State, Throwable, List<Result>> pipeline = Effect.sequence(effects);
```

### 3. Parallel Execution

```java
// Zip two effects in parallel
effect1.parZip(effect2, (a, b) -> a + b)

// Execute multiple effects in parallel
Effect.parSequence(List.of(effect1, effect2, effect3))

// Race two effects
effect1.race(effect2)

// Add timeout
effect.withTimeout(Duration.ofSeconds(5))
```

### 4. Utility Methods

```java
// Logging
Effect.log("message")
Effect.logError("error occurred")
Effect.logState(s -> "State: " + s)

// Messaging
Effect.tell(targetPid, message)
Effect.tellSelf(message)

// Conversion
Effect.fromTransition((state, msg) -> newState)
```

## Complete API

### Factory Methods
- `of(value)` - Create effect with value
- `state()` - Get current state
- `modify(f)` - Modify state
- `setState(newState)` - Set state
- `identity()` / `none()` - No-op effect
- `fail(error)` - Failed effect
- `fromTransition(f)` - From state transition function

### Monadic Operations
- `map(f)` - Transform result
- `flatMap(f)` - Chain effects
- `andThen(next)` - Sequence effects
- `zip(other, combiner)` - Combine two effects sequentially

### Error Handling
- `attempt()` - Catch exceptions
- `handleErrorWith(handler)` - Handle errors with effect
- `handleError(handler)` - Handle errors with state update
- `tapError(action)` - Side effect on error
- `onError(action)` - Alias for tapError
- `recover(f)` - Recover from error with value
- `recoverWith(f)` - Recover from error with effect
- `orElse(fallback)` - Use fallback on failure

### Validation
- `filterOrElse(predicate, fallback)` - Conditional execution

### Side Effects
- `tap(action)` - Side effect on result
- `tapState(action)` - Side effect on state

### Parallel Execution
- `parZip(other, combiner)` - Parallel zip
- `parSequence(effects)` - Parallel sequence
- `sequence(effects)` - Sequential sequence
- `race(other)` - Race two effects
- `withTimeout(duration)` - Add timeout

### Pattern Matching
- `match()` - Create match builder
- `.when(Class, handler)` - Add handler
- `.otherwise(effect)` - Default handler
- `.build()` - Build final effect

## Benefits

1. **Stack Safety**: No more stack overflows with deep effect chains
2. **Simpler API**: Less type parameters to manage
3. **Better Error Handling**: Explicit error type and rich error combinators
4. **More Powerful**: New parallel and sequential combinators
5. **Type Safe**: Message type checked at match level

## Examples

### Basic Usage

```java
Effect<BankState, Throwable, Void> deposit = 
    Effect.<BankState, Throwable>modify(s -> 
        new BankState(s.balance() + amount)
    );
```

### Pattern Matching

```java
Effect<BankState, Throwable, Void> behavior = 
    Effect.<BankState, Throwable, Void, BankMsg>match()
        .when(Deposit.class, (state, msg, ctx) -> 
            Effect.modify(s -> new BankState(s.balance() + msg.amount()))
        )
        .when(Withdraw.class, (state, msg, ctx) -> 
            Effect.<BankState, Throwable, Void>modify(s -> 
                new BankState(s.balance() - msg.amount())
            )
            .filterOrElse(
                s -> s.balance() >= 0,
                Effect.identity()
            )
        )
        .build();
```

### Error Handling

```java
Effect<State, Throwable, Result> robust = riskyEffect
    .attempt()
    .recover(err -> defaultValue)
    .tapError(err -> log.error("Operation failed", err));
```

### Parallel Execution

```java
Effect<State, Throwable, Result> parallel = 
    effect1.parZip(effect2, (a, b) -> a + b)
        .withTimeout(Duration.ofSeconds(5))
        .recover(err -> 0);
```
