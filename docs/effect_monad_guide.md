# Building Actors with Effects

> **🎉 Updated for Latest Version**: Effect is now stack-safe with simplified type signature!  
> See the [Effect Refactoring Guide](effect_refactoring_guide.md) for migration details.

## What are Effects?

Think of an **Effect** as a recipe for what your actor should do when it receives a message. Just like a cooking recipe tells you the steps to make a dish, an Effect tells the actor:

1. How to update its state
2. What messages to send to other actors
3. What to log or track
4. How to handle errors

The beauty of Effects is that you can **compose** them - combine simple recipes into complex behaviors, just like combining basic cooking techniques to create elaborate dishes. And now, Effects are **stack-safe**, meaning you can chain thousands of operations without worrying about stack overflow!

### 🚀 Blocking is Safe!

**Coming from Akka or reactive frameworks?** Great news: **You can forget about `CompletableFuture` chains and async/await complexity!**

Cajun runs on **Java 21+ Virtual Threads** (Project Loom), which means:

- ✅ **Write normal blocking code** - Database calls, HTTP requests, file I/O - just write them naturally
- ✅ **No Future/Promise hell** - No `.thenCompose()`, `.thenApply()`, or callback chains
- ✅ **Efficient under the hood** - When you block, only the virtual thread suspends, never the OS thread
- ✅ **Simple imperative style** - Code looks synchronous but executes efficiently

```java
// This is perfectly fine in Cajun! No CompletableFuture needed.
Effect.attempt(() -> {
    // Looks like blocking code, but it's efficient!
    var data = database.query("SELECT * FROM users");  // Blocks the VT, not the OS
    var result = httpClient.get("https://api.example.com");  // Also fine!
    return processData(data, result);
})
.recover(error -> "Fallback value");
```

**The catch?** While blocking is efficient for the system, remember that **actors process one message at a time**. If your actor blocks for 5 seconds waiting for an API call, it won't process the next message until that call finishes. For parallel work, spawn child actors or use the parallel combinators (`parZip`, `parSequence`).

## Your First Effect

Let's start with the simplest possible example - a counter that increments:

```java
// Define your messages
sealed interface CounterMsg {}
record Increment(int amount) implements CounterMsg {}
record GetCount(Pid replyTo) implements CounterMsg {}

// Create an effect that increments the counter
// Note: Effect<State, Error, Result> - Message type is at match level
Effect<Integer, Throwable, Void> incrementEffect = 
    Effect.modify(count -> count + 1);
```

That's it! This effect says: "When you get an Increment message, add 1 to the count."

## Building Intuition: Effects are Transformations

Think of your actor's state as a value that flows through a pipeline. Each effect is a transformation in that pipeline:

```java
// Start with state = 5
// Message arrives: Increment(10)

Effect<Integer, Throwable, Void> effect = 
    Effect.modify(count -> count + 10);  // Transform: 5 → 15

// State is now 15
```

## Composing Effects: Chaining Actions

The real power comes from chaining effects together. Use `.andThen()` to say "do this, then do that":

```java
Effect<Integer, Throwable, Void> effect = 
    Effect.modify(count -> count + 10)           // First: add 10
        .andThen(Effect.logState(c -> "Count is now: " + c));  // Then: log it

// When state=5 and Increment arrives:
// 1. State becomes 15
// 2. Logs "Count is now: 15"
```

## Pattern Matching: Handling Different Messages

Real actors need to handle multiple message types. Use `Effect.match()` to route messages.  
**Note**: The Message type is specified at the match level (4th type parameter), not in the Effect type:

```java
// Message type (CounterMsg) is the 4th type parameter in match()
Effect<Integer, Throwable, Void> counterBehavior = 
    Effect.<Integer, Throwable, Void, CounterMsg>match()
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

## Understanding the Error Type Parameter

You may have noticed `Throwable` as the second type parameter in `Effect<State, Throwable, Result>`. This is the **Error type** - it tells the Effect what kind of errors it can handle.

### Why Not Just Use Throwable Everywhere?

While `Throwable` works for most cases, you can use **specific exception types** for better type safety:

```java
// Using IOException for file operations
Effect<FileState, IOException, String> readFile = 
    Effect.<FileState, IOException, String>attempt(() -> {
        String content = Files.readString(Path.of("data.txt"));
        return content;
    });

// Using SQLException for database operations  
Effect<DbState, SQLException, ResultSet> queryDb =
    Effect.<DbState, SQLException, ResultSet>attempt(() -> {
        // Your database query code
        return resultSet;
    });

// Using custom checked exceptions
class ValidationException extends Exception {
    ValidationException(String msg) { super(msg); }
}

Effect<OrderState, ValidationException, Void> validateOrder = 
    Effect.<OrderState, ValidationException>modify(state -> {
        if (state.items().isEmpty()) {
            throw new ValidationException("Order cannot be empty");
        }
        return state.withValidated(true);
    }).attempt();
```

### When to Use Specific Exception Types

**Use specific exception types when:**
- You want compile-time guarantees about what errors can occur
- You're integrating with APIs that throw checked exceptions (File I/O, JDBC, etc.)
- You have custom domain-specific exceptions
- You want to handle different error types differently

**Use `Throwable` when:**
- You're handling multiple different exception types
- You want maximum flexibility
- You're just getting started and want simplicity

### Handling Multiple Exception Types

If you need to handle multiple exception types, use their common supertype:

```java
// Both IOException and SQLException extend Exception
Effect<State, Exception, Result> effect = 
    Effect.<State, Exception, Result>attempt(() -> {
        // Code that might throw IOException or SQLException
        String data = Files.readString(Path.of("data.txt"));  // throws IOException
        database.execute(data);  // throws SQLException
        return result;
    });

// Then handle them differently in recovery
Effect<State, Exception, String> handled = effect.handleErrorWith((err, s, m, c) -> {
    if (err instanceof IOException) {
        return Effect.of("IO Error: " + err.getMessage());
    } else if (err instanceof SQLException) {
        return Effect.of("DB Error: " + err.getMessage());
    }
    return Effect.of("Unknown error");
});
```

## Alternative: ThrowableEffect

If you find the type parameters verbose, Cajun also provides **`ThrowableEffect<State, Result>`** - a simpler alternative with only 2 type parameters:

```java
// ThrowableEffect - simpler, always uses Throwable for errors
ThrowableEffect<Integer, Void> increment = 
    ThrowableEffect.modify(count -> count + 1);

// Pattern matching with ThrowableEffect
ThrowableEffect<Integer, Void> behavior = 
    ThrowableEffect.<Integer, CounterMsg>match()
        .when(Increment.class, (state, msg, ctx) -> 
            ThrowableEffect.modify(s -> s + msg.amount()))
        .build();
```

**When to use ThrowableEffect:**
- You always use `Throwable` as your error type
- You want less verbose type signatures
- You're building simple actors

**When to use Effect:**
- You need specific checked exception types
- You want maximum type safety
- You're integrating with APIs that throw checked exceptions

Both `Effect` and `ThrowableEffect` are **stack-safe** and have the same operators. Choose based on your needs!

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
// Define a custom validation error
record ValidationError(String field, Object value, String reason) extends Exception {
    ValidationError(String field, Object value, String reason) {
        super(String.format("%s: %s (got: %s)", field, reason, value));
    }
}

Effect<Integer, ValidationError, Integer> validated = 
    Effect.of(value)
        .filter(v -> v > 0, 
                v -> new ValidationError("amount", v, "must be positive"))
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
Effect<Integer, Throwable, Void> counterEffect = 
    Effect.<Integer, Throwable, Void, CounterMsg>match()
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
Effect<CartState, Throwable, Void> cartBehavior = 
    Effect.<CartState, Throwable, Void, CartMsg>match()
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
            .filter(total -> total > 0, 
                    total -> new IllegalStateException("Cart is empty, total: " + total))
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
    .filter(i -> i.isValid(), 
            i -> new ValidationException("Input validation failed: " + i))
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

## Best Practices for Virtual Threads

### ✅ DO: Write Simple Blocking Code

```java
// This is the Cajun way - simple and efficient!
Effect.attempt(() -> {
    var user = database.findUser(userId);        // Blocking call - totally fine!
    var orders = orderService.getOrders(userId); // Another blocking call - great!
    return new UserProfile(user, orders);
})
.recover(error -> UserProfile.empty());
```

### ⚠️ AVOID: synchronized Blocks

**Important:** `synchronized` blocks can pin virtual threads to OS threads, defeating the purpose of Virtual Threads.

```java
// ❌ BAD - synchronized pins the virtual thread
Effect.attempt(() -> {
    synchronized(lock) {  // This pins the VT to the carrier thread!
        return sharedResource.read();
    }
})

// ✅ GOOD - Use ReentrantLock instead
Effect.attempt(() -> {
    lock.lock();  // ReentrantLock is VT-friendly
    try {
        return sharedResource.read();
    } finally {
        lock.unlock();
    }
})
```

### 🎯 Understanding Effect.ask

`Effect.ask` is a **suspension point** - it looks synchronous but executes efficiently:

```java
Effect.ask(inventoryActor, new CheckStock(item), Duration.ofSeconds(5))
    .flatMap(inStock -> {
        // When the runtime encounters ask, it suspends this actor's virtual thread
        // until the reply arrives. The code looks synchronous, but the execution
        // is non-blocking - the OS thread is free to do other work!
        
        if (inStock) {
            return Effect.tell(orderActor, new CreateOrder(item));
        } else {
            return Effect.log("Out of stock: " + item);
        }
    });
```

**Remember:** While the OS thread isn't blocked, **this specific actor** won't process its next message until the reply arrives. If you need this actor to remain responsive, consider:
- Using a child actor to handle the slow operation
- Using `parZip` to do multiple asks in parallel
- Setting appropriate timeouts

### 💡 Parallel Operations

When you need an actor to do multiple things at once:

```java
// ✅ Parallel asks - both happen simultaneously
Effect.ask(service1, request1, timeout)
    .parZip(Effect.ask(service2, request2, timeout), (r1, r2) -> 
        combineResults(r1, r2)
    );

// ✅ Spawn a child actor for long-running work
Effect.spawn(WorkerActor.class, initialState, workerBehavior)
    .flatMap(worker -> Effect.tell(worker, new DoWork(data)));
```

## Tips for Beginners

1. **Start Simple** - Begin with basic `modify` and `log` effects
2. **Chain Gradually** - Add one `andThen` at a time
3. **Test Each Piece** - Effects are easy to test in isolation
4. **Use Pattern Matching** - It makes message handling clear
5. **Handle Errors** - Always add `.recover()` for risky operations
6. **Think in Pipelines** - Data flows through transformations
7. **Embrace Blocking** - Write natural blocking code, Virtual Threads handle the rest
8. **Avoid synchronized** - Use `ReentrantLock` if you need locks

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
Effect.<State, Error, Result, Message>match()
    .when(Type1.class, handler1)
    .when(Type2.class, handler2)
    .otherwise(defaultHandler)
    .build()
```

Remember: Effects are just descriptions of what to do. They don't execute until the actor runs them. This makes them easy to test, compose, and reason about!

## Learn More

- **[Effect API Reference](effect_monad_api.md)** - Complete API documentation with all operators
- **[Effect Refactoring Guide](effect_refactoring_guide.md)** - Migration guide for the new stack-safe API
- **[ThrowableEffect API](throwable_effect_api.md)** - Documentation for the simpler ThrowableEffect alternative
- **[Checked Exception Tests](../lib/src/test/java/com/cajunsystems/functional/EffectCheckedExceptionTest.java)** - Examples of using checked exceptions with Effect
