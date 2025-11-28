# Building Actors with Effects

## What are Effects?

Think of an **Effect** as a recipe for what your actor should do when it receives a message. Just like a cooking recipe tells you the steps to make a dish, an Effect tells the actor:

1. How to update its state
2. What messages to send to other actors
3. What to log or track
4. How to handle errors

The beauty of Effects is that you can **compose** them - combine simple recipes into complex behaviors, just like combining basic cooking techniques to create elaborate dishes.

## Your First Effect

Let's start with the simplest possible example - a counter that increments:

```java
// Define your messages
sealed interface CounterMsg {}
record Increment(int amount) implements CounterMsg {}
record GetCount(Pid replyTo) implements CounterMsg {}

// Create an effect that increments the counter
Effect<Integer, Increment, Void> incrementEffect = 
    Effect.modify(count -> count + 1);
```

That's it! This effect says: "When you get an Increment message, add 1 to the count."

## Building Intuition: Effects are Transformations

Think of your actor's state as a value that flows through a pipeline. Each effect is a transformation in that pipeline:

```java
// Start with state = 5
// Message arrives: Increment(10)

Effect<Integer, Increment, Void> effect = 
    Effect.modify(count -> count + 10);  // Transform: 5 → 15

// State is now 15
```

## Composing Effects: Chaining Actions

The real power comes from chaining effects together. Use `.andThen()` to say "do this, then do that":

```java
Effect<Integer, Increment, Void> effect = 
    Effect.modify(count -> count + 10)           // First: add 10
        .andThen(Effect.logState(c -> "Count is now: " + c));  // Then: log it

// When state=5 and Increment arrives:
// 1. State becomes 15
// 2. Logs "Count is now: 15"
```

## Pattern Matching: Handling Different Messages

Real actors need to handle multiple message types. Use `Effect.match()` to route messages:

```java
Effect<Integer, CounterMsg, Void> counterBehavior = Effect.match()
    .when(Increment.class, (state, msg, ctx) -> 
        Effect.modify(s -> s + msg.amount())
            .andThen(Effect.logState(s -> "Incremented to: " + s)))
    
    .when(Decrement.class, (state, msg, ctx) ->
        Effect.modify(s -> s - msg.amount())
            .andThen(Effect.logState(s -> "Decremented to: " + s)))
    
    .when(GetCount.class, (state, msg, ctx) ->
        Effect.tell(msg.replyTo(), state))
    
    .build();
```

**What's happening here?**
- When an `Increment` arrives → modify state and log
- When a `Decrement` arrives → modify state and log  
- When a `GetCount` arrives → send current state to the requester

## Common Effect Patterns

### 1. Modifying State

```java
// Simple increment
Effect.modify(count -> count + 1)

// Update based on message
Effect.modify(count -> count + msg.amount())

// Set to specific value
Effect.setState(0)  // Reset to zero
```

### 2. Sending Messages

```java
// Send to another actor
Effect.tell(otherActor, new SomeMessage())

// Send to yourself (useful for scheduling)
Effect.tellSelf(new ProcessNext())

// Reply to sender
Effect.tell(msg.replyTo(), result)
```

### 3. Logging

```java
// Simple log message
Effect.log("Processing started")

// Log with current state
Effect.logState(count -> "Current count: " + count)

// Log errors
Effect.logError("Something went wrong")
```

### 4. Combining Multiple Actions

```java
// Do several things in sequence
Effect.modify(count -> count + 1)
    .andThen(Effect.logState(c -> "Count: " + c))
    .andThen(Effect.tell(monitor, new CountUpdate(count)))
```

## Working with Results

Sometimes you want to produce a value, not just change state:

```java
// Produce a value
Effect<Integer, Msg, String> effect = Effect.of("Hello");

// Transform a value
Effect<Integer, Msg, Integer> getCount = Effect.state();
Effect<Integer, Msg, String> formatted = 
    getCount.map(count -> "Count is: " + count);
```

## Error Handling Made Simple

Effects make error handling explicit and composable:

```java
// Try something risky
Effect<Integer, Msg, String> risky = 
    Effect.attempt(() -> riskyOperation());

// Handle errors gracefully
Effect<Integer, Msg, String> safe = risky
    .recover(error -> "Error: " + error.getMessage());

// Or provide a fallback effect
Effect<Integer, Msg, String> withFallback = risky
    .orElse(Effect.of("default value"));
```

## Validation and Filtering

Check conditions and handle invalid cases:

```java
Effect<Integer, Msg, Integer> validated = 
    Effect.of(value)
        .filter(v -> v > 0, "Value must be positive")
        .recover(error -> {
            ctx.getLogger().error("Validation failed: " + error.getMessage());
            return 0;
        });
```

## Creating Actors from Effects

Once you've built your effect, turn it into an actor:

```java
import static com.cajunsystems.functional.ActorSystemEffectExtensions.*;

// Create the effect
Effect<Integer, CounterMsg, Void> counterEffect = Effect.match()
    .when(Increment.class, (state, msg, ctx) -> 
        Effect.modify(s -> s + msg.amount()))
    .when(GetCount.class, (state, msg, ctx) ->
        Effect.tell(msg.replyTo(), state))
    .build();

// Spawn an actor with this behavior
Pid counter = fromEffect(system, counterEffect, 0)  // Start with state = 0
    .withId("my-counter")
    .spawn();

// Use it like any actor
counter.tell(new Increment(5));
```

## Real-World Example: Shopping Cart

Let's build a shopping cart actor using effects:

```java
// Messages
sealed interface CartMsg {}
record AddItem(String item, double price) implements CartMsg {}
record RemoveItem(String item) implements CartMsg {}
record GetTotal(Pid replyTo) implements CartMsg {}
record Checkout(Pid replyTo) implements CartMsg {}

// State
record CartState(Map<String, Double> items, double total) {
    CartState() {
        this(new HashMap<>(), 0.0);
    }
}

// Build the behavior
Effect<CartState, CartMsg, Void> cartBehavior = Effect.match()
    .when(AddItem.class, (state, msg, ctx) ->
        Effect.modify(s -> {
            s.items().put(msg.item(), msg.price());
            return new CartState(s.items(), s.total() + msg.price());
        })
        .andThen(Effect.logState(s -> "Cart total: $" + s.total())))
    
    .when(RemoveItem.class, (state, msg, ctx) ->
        Effect.modify(s -> {
            Double price = s.items().remove(msg.item());
            if (price != null) {
                return new CartState(s.items(), s.total() - price);
            }
            return s;
        }))
    
    .when(GetTotal.class, (state, msg, ctx) ->
        Effect.tell(msg.replyTo(), state.total()))
    
    .when(Checkout.class, (state, msg, ctx) ->
        Effect.of(state.total())
            .filter(total -> total > 0, "Cart is empty")
            .flatMap(total -> 
                Effect.tell(paymentService, new ProcessPayment(total))
                    .andThen(Effect.setState(new CartState()))
                    .andThen(Effect.tell(msg.replyTo(), "Checkout successful")))
            .recover(error -> {
                ctx.tell(msg.replyTo(), "Checkout failed: " + error.getMessage());
                return null;
            }))
    
    .build();

// Create the cart actor
Pid cart = fromEffect(system, cartBehavior, new CartState())
    .withId("shopping-cart")
    .spawn();
```

## Building Intuition: The Mental Model

Think of Effects like building blocks:

1. **Simple blocks** - Basic operations like `modify`, `log`, `tell`
2. **Combining blocks** - Use `andThen` to chain operations
3. **Branching blocks** - Use `match` to handle different cases
4. **Error-safe blocks** - Use `recover` and `orElse` for safety
5. **Transforming blocks** - Use `map` and `flatMap` to transform values

The key insight: **You're describing what should happen, not manually doing it**. The Effect system handles the execution, state management, and error propagation for you.

## Common Patterns

### Pattern 1: Validate, Process, Notify

```java
Effect.of(input)
    .filter(i -> i.isValid(), "Invalid input")
    .map(i -> i.process())
    .flatMap(result -> 
        Effect.modify(s -> s.update(result))
            .andThen(Effect.tell(monitor, new ProcessComplete(result))))
    .recover(error -> {
        ctx.getLogger().error("Processing failed", error);
        return null;
    })
```

### Pattern 2: Ask Another Actor, Then Update

```java
Effect.ask(inventoryActor, new CheckStock(item), Duration.ofSeconds(5))
    .flatMap(inStock -> {
        if (inStock) {
            return Effect.modify(s -> s.addItem(item))
                .andThen(Effect.log("Item added"));
        } else {
            return Effect.log("Out of stock");
        }
    })
    .recover(error -> {
        ctx.getLogger().error("Inventory check failed", error);
        return null;
    })
```

### Pattern 3: Conditional Logic

```java
Effect.when(
    msg -> msg.priority() == Priority.HIGH,
    Effect.modify(s -> s.processImmediately(msg))
        .andThen(Effect.log("High priority processed")),
    Effect.tellSelf(msg)  // Requeue for later
        .andThen(Effect.log("Queued for later"))
)
```

## Tips for Beginners

1. **Start Simple** - Begin with basic `modify` and `log` effects
2. **Chain Gradually** - Add one `andThen` at a time
3. **Test Each Piece** - Effects are easy to test in isolation
4. **Use Pattern Matching** - It makes message handling clear
5. **Handle Errors** - Always add `.recover()` for risky operations
6. **Think in Pipelines** - Data flows through transformations

## Next Steps

- Read the [Full Effect API Reference](effect_monad_api.md) for all available operations
- Check out [Functional Actor Evolution](functional_actor_evolution.md) for advanced patterns
- See [Examples](../lib/src/test/java/examples/) for complete working code
  - **[KVEffectExample.java](../lib/src/test/java/examples/KVEffectExample.java)** - LSM Tree-based Key-Value store demonstrating complex actor coordination with Effects

## Quick Reference

```java
// State operations
Effect.modify(s -> s + 1)           // Update state
Effect.setState(newState)           // Replace state
Effect.state()                      // Get current state

// Messaging
Effect.tell(actor, msg)             // Send message
Effect.tellSelf(msg)                // Send to self
Effect.ask(actor, msg, timeout)     // Request-response

// Logging
Effect.log("message")               // Log message
Effect.logState(s -> "State: " + s) // Log with state
Effect.logError("error")            // Log error

// Composition
effect1.andThen(effect2)            // Do both in sequence
effect.map(v -> transform(v))       // Transform result
effect.flatMap(v -> nextEffect)     // Chain effects

// Error handling
effect.recover(e -> defaultValue)   // Handle errors
effect.orElse(fallbackEffect)       // Fallback effect
Effect.attempt(() -> risky())       // Try risky operation

// Pattern matching
Effect.match()
    .when(Type1.class, handler1)
    .when(Type2.class, handler2)
    .otherwise(defaultHandler)
    .build()
```

Remember: Effects are just descriptions of what to do. They don't execute until the actor runs them. This makes them easy to test, compose, and reason about!
