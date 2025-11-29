# FunctionalActor Evolution: Monadic API Design

## Current State Analysis

### Existing Implementation

The current `FunctionalActor` provides:
- Basic functional composition with `BiFunction<State, Message, State>`
- Chain creation for sequential processing
- Bridge to `StatefulHandler` interface
- Error handling with `BiConsumer`

**Strengths:**
- Simple, straightforward API
- Works well for basic state transformations
- Good integration with existing actor system

**Limitations:**
- No composability - can't easily combine transformations
- Limited error handling - only side effects, no recovery
- No support for effects (logging, side effects, async operations)
- Missing monadic operations (map, flatMap, filter, etc.)
- No way to express conditional logic functionally
- Can't easily compose actors or behaviors

## Proposed Monadic API: `Effect<State, Message, Result>`

### Core Concept

Introduce an `Effect` monad that represents a stateful computation with side effects:

```java
/**
 * Represents a computation that:
 * - Takes a state and message
 * - Produces a new state
 * - May produce a result
 * - May perform side effects (logging, sending messages, etc.)
 * - May fail with an error
 */
@FunctionalInterface
public interface Effect<State, Message, Result> {
    /**
     * Execute the effect with the given state, message, and context.
     * Returns an EffectResult containing the new state and optional result.
     */
    EffectResult<State, Result> run(State state, Message message, ActorContext context);
}
```

### EffectResult - The Result Container

```java
/**
 * Result of executing an effect.
 * Contains the new state and an optional result value.
 */
public sealed interface EffectResult<State, Result> {
    State state();
    
    /**
     * Successful effect execution
     */
    record Success<State, Result>(State state, Result value) implements EffectResult<State, Result> {}
    
    /**
     * Effect execution with no result (state change only)
     */
    record NoResult<State, Result>(State state) implements EffectResult<State, Result> {}
    
    /**
     * Effect execution failed
     */
    record Failure<State, Result>(State state, Throwable error) implements EffectResult<State, Result> {}
    
    // Convenience methods
    default boolean isSuccess() {
        return this instanceof Success;
    }
    
    default boolean hasValue() {
        return this instanceof Success;
    }
    
    default Optional<Result> value() {
        return this instanceof Success<State, Result>(var s, var v) ? Optional.of(v) : Optional.empty();
    }
}
```

## Type Inference Guidelines

Java's type inference (introduced in Java 10+) works well with the Effect API in most cases:

### ✅ When Type Inference Works (No Explicit Types Needed)

```java
// 1. When assigned to a typed variable
Effect<Integer, CounterMsg, Void> effect = Effect.modify(s -> s + 1);

// 2. When chaining operations (types flow through the chain)
Effect.modify(s -> s + 1)
    .andThen(Effect.log("Updated"))
    .tap(result -> System.out.println(result));

// 3. When returned from a method with explicit return type
Effect<State, Msg, Result> createEffect() {
    return Effect.modify(s -> s.increment())  // Types inferred from return type
        .andThen(Effect.logState(s -> "State: " + s));
}

// 4. Inside lambda expressions with typed parameters
Effect.<Integer, Throwable, Void, CounterMsg>match()
    .when(Increment.class, (Integer state, Increment msg, ActorContext ctx) ->
        Effect.modify(s -> s + msg.amount())  // Types inferred from lambda params
    );
```

### ⚠️ When Explicit Types Are Needed

```java
// 1. Starting a chain without assignment (rare)
Effect.<Integer, CounterMsg, Void>modify(s -> s + 1)
    .andThen(Effect.log("Done"));

// 2. Ambiguous generic method calls (very rare with good API design)
Effect.<State, Msg, Result>pure(value);

// 3. When compiler can't infer from context (usually a sign to refactor)
var effect = Effect.<Integer, CounterMsg, Void>modify(s -> s + 1);  // Don't do this
```

### 💡 Best Practices

1. **Prefer typed variables**: `Effect<State, Msg, Result> effect = ...`
2. **Use method return types**: Let the return type drive inference
3. **Avoid `var` for Effects**: It hides important type information
4. **Chain operations**: Types flow naturally through chains

### Example: Clean Type Inference

```java
// Explicit type parameters for clarity
Effect<Integer, Throwable, String> counterEffect = 
    Effect.<Integer, Throwable, String, CounterMsg>match()
    .when(Increment.class, (state, msg, ctx) ->
        Effect.modify(s -> s + msg.amount())              // Inferred
            .andThen(Effect.logState(s -> "Count: " + s)) // Inferred
            .map(_ -> "Incremented")                      // Inferred
    )
    .when(GetCount.class, (state, msg, ctx) ->
        Effect.pure("Count: " + state)                    // Inferred
            .tap(result -> ctx.tell(msg.replyTo(), result)) // Inferred
    );
```

## Monadic Operations

### 1. Pure - Lift a value into Effect

```java
/**
 * Create an effect that returns a value without changing state
 */
static <S, M, R> Effect<S, M, R> pure(R value) {
    return (state, message, context) -> new EffectResult.Success<>(state, value);
}

/**
 * Create an effect that modifies state
 */
static <S, M, R> Effect<S, M, R> modify(Function<S, S> f) {
    return (state, message, context) -> new EffectResult.NoResult<>(f.apply(state));
}

/**
 * Create an effect from a state transition function
 */
static <S, M> Effect<S, M, Void> fromTransition(BiFunction<S, M, S> f) {
    return (state, message, context) -> new EffectResult.NoResult<>(f.apply(state, message));
}
```

### 2. Map - Transform the result

```java
default <R2> Effect<State, Message, R2> map(Function<Result, R2> f) {
    return (state, message, context) -> {
        EffectResult<State, Result> result = this.run(state, message, context);
        return switch (result) {
            case EffectResult.Success(var s, var v) -> 
                new EffectResult.Success<>(s, f.apply(v));
            case EffectResult.NoResult(var s) -> 
                new EffectResult.NoResult<>(s);
            case EffectResult.Failure(var s, var e) -> 
                new EffectResult.Failure<>(s, e);
        };
    };
}
```

### 3. FlatMap - Chain effects

```java
default <R2> Effect<State, Message, R2> flatMap(Function<Result, Effect<State, Message, R2>> f) {
    return (state, message, context) -> {
        EffectResult<State, Result> result = this.run(state, message, context);
        return switch (result) {
            case EffectResult.Success(var s, var v) -> 
                f.apply(v).run(s, message, context);
            case EffectResult.NoResult(var s) -> 
                new EffectResult.NoResult<>(s);
            case EffectResult.Failure(var s, var e) -> 
                new EffectResult.Failure<>(s, e);
        };
    };
}
```

### 4. AndThen - Sequential composition

```java
default <R2> Effect<State, Message, R2> andThen(Effect<State, Message, R2> next) {
    return (state, message, context) -> {
        EffectResult<State, Result> result = this.run(state, message, context);
        return switch (result) {
            case EffectResult.Success(var s, var v) -> 
                next.run(s, message, context);
            case EffectResult.NoResult(var s) -> 
                next.run(s, message, context);
            case EffectResult.Failure(var s, var e) -> 
                new EffectResult.Failure<>(s, e);
        };
    };
}
```

### 5. OrElse - Error recovery

```java
default Effect<State, Message, Result> orElse(Effect<State, Message, Result> fallback) {
    return (state, message, context) -> {
        EffectResult<State, Result> result = this.run(state, message, context);
        return switch (result) {
            case EffectResult.Failure(var s, var e) -> 
                fallback.run(s, message, context);
            default -> result;
        };
    };
}

default Effect<State, Message, Result> recover(Function<Throwable, Result> f) {
    return (state, message, context) -> {
        EffectResult<State, Result> result = this.run(state, message, context);
        return switch (result) {
            case EffectResult.Failure(var s, var e) -> 
                new EffectResult.Success<>(s, f.apply(e));
            default -> result;
        };
    };
}
```

### 6. Filter - Conditional execution

```java
default Effect<State, Message, Result> filter(Predicate<Result> predicate, String errorMsg) {
    return (state, message, context) -> {
        EffectResult<State, Result> result = this.run(state, message, context);
        return switch (result) {
            case EffectResult.Success(var s, var v) -> 
                predicate.test(v) 
                    ? result 
                    : new EffectResult.Failure<>(s, new IllegalStateException(errorMsg));
            default -> result;
        };
    };
}
```

### 7. Tap - Side effects without changing result

```java
default Effect<State, Message, Result> tap(Consumer<Result> action) {
    return (state, message, context) -> {
        EffectResult<State, Result> result = this.run(state, message, context);
        result.value().ifPresent(action);
        return result;
    };
}

default Effect<State, Message, Result> tapState(Consumer<State> action) {
    return (state, message, context) -> {
        EffectResult<State, Result> result = this.run(state, message, context);
        action.accept(result.state());
        return result;
    };
}
```

## Actor-Specific Effects

### 1. Tell - Send messages

```java
static <S, M> Effect<S, M, Void> tell(Pid target, Object message) {
    return (state, msg, context) -> {
        context.tell(target, message);
        return new EffectResult.NoResult<>(state);
    };
}

static <S, M> Effect<S, M, Void> tellSelf(Object message) {
    return (state, msg, context) -> {
        context.tellSelf(message);
        return new EffectResult.NoResult<>(state);
    };
}
```

### 2. Ask - Request-response

```java
static <S, M, R> Effect<S, M, R> ask(Pid target, Object message, Duration timeout) {
    return (state, msg, context) -> {
        try {
            Reply<R> reply = target.ask(message, timeout);
            R response = reply.get();
            return new EffectResult.Success<>(state, response);
        } catch (Exception e) {
            return new EffectResult.Failure<>(state, e);
        }
    };
}
```

### 3. Log - Logging effects

```java
static <S, M> Effect<S, M, Void> log(String message) {
    return (state, msg, context) -> {
        context.getLogger().info(message);
        return new EffectResult.NoResult<>(state);
    };
}

static <S, M> Effect<S, M, Void> logState(Function<S, String> messageFunc) {
    return (state, msg, context) -> {
        context.getLogger().info(messageFunc.apply(state));
        return new EffectResult.NoResult<>(state);
    };
}
```

### 4. Conditional - Pattern matching on messages

```java
static <S, M, R> Effect<S, M, R> when(
    Predicate<M> condition,
    Effect<S, M, R> thenEffect,
    Effect<S, M, R> elseEffect
) {
    return (state, message, context) -> {
        if (condition.test(message)) {
            return thenEffect.run(state, message, context);
        } else {
            return elseEffect.run(state, message, context);
        }
    };
}
```

## Complete Usage Examples

### Example 1: Simple Counter with Logging

```java
public class CounterMessages {
    sealed interface CounterMsg {}
    record Increment(int amount) implements CounterMsg {}
    record Decrement(int amount) implements CounterMsg {}
    record GetCount(Pid replyTo) implements CounterMsg {}
}

// Define the behavior using effects
Effect<Integer, Throwable, Void> counterBehavior = 
    Effect.<Integer, Throwable, Void, CounterMsg>match()
    .when(Increment.class, (state, msg, ctx) -> 
        Effect.modify(s -> s + msg.amount())
            .andThen(Effect.logState(s -> "Counter incremented to: " + s))
    )
    .when(Decrement.class, (state, msg, ctx) ->
        Effect.modify(s -> s - msg.amount())
            .andThen(Effect.logState(s -> "Counter decremented to: " + s))
    )
    .when(GetCount.class, (state, msg, ctx) ->
        Effect.tell(msg.replyTo(), state)
            .andThen(Effect.log("Sent count: " + state))
    )
    .otherwise(Effect.log("Unknown message"));

// Create actor with the effect-based behavior
Pid counter = system.functionalActorOf(counterBehavior, 0)
    .withId("counter")
    .spawn();
```

### Example 2: User Service with Validation and Error Handling

```java
record User(String id, String name, int age) {}

Effect<Map<String, User>, Throwable, User> userService = 
    Effect.<Map<String, User>, Throwable, User, UserMsg>match()
    .when(CreateUser.class, (state, msg, ctx) ->
        // Validate age
        Effect.pure(msg.user())
            .filter(u -> u.age() >= 18, 
                    u -> new IllegalArgumentException("User must be 18 or older, got age: " + u.age()))
            .flatMap(user -> 
                // Check if user exists
                state.containsKey(user.id())
                    ? Effect.fail(new IllegalStateException("User already exists"))
                    : Effect.pure(user)
            )
            .flatMap(user ->
                // Add to state and return user
                Effect.modify(s -> {
                    s.put(user.id(), user);
                    return s;
                }).map(_ -> user)
            )
            .tap(user -> ctx.getLogger().info("Created user: " + user.id()))
            .recover(error -> {
                ctx.getLogger().error("Failed to create user", error);
                return null;
            })
    )
    .when(GetUser.class, (state, msg, ctx) ->
        Effect.pure(state.get(msg.userId()))
            .filter(Objects::nonNull, 
                    u -> new IllegalStateException("User not found: " + msg.userId()))
            .tap(user -> ctx.getLogger().info("Retrieved user: " + user.id()))
    );
```

### Example 3: Workflow with Ask Pattern

```java
Effect<WorkflowState, Throwable, String> workflowEffect = 
    Effect.<WorkflowState, Throwable, String, WorkflowMsg>match()
    .when(ProcessOrder.class, (state, msg, ctx) ->
        // Step 1: Validate order
        Effect.pure(msg.order())
            .filter(order -> order.total() > 0, 
                    order -> new IllegalArgumentException("Order total must be positive, got: " + order.total()))
            
            // Step 2: Check inventory (ask pattern)
            .flatMap(order ->
                Effect.ask(
                    inventoryActor,
                    new CheckStock(order.items()),
                    Duration.ofSeconds(5)
                )
                .map(inStock -> new Tuple2<>(order, inStock))
            )
            
            // Step 3: Process payment if in stock
            .flatMap(tuple -> {
                Order order = tuple._1();
                boolean inStock = tuple._2();
                
                if (!inStock) {
                    return Effect.fail(new OutOfStockException());
                }
                
                return Effect.ask(
                    paymentActor,
                    new ProcessPayment(order.total()),
                    Duration.ofSeconds(10)
                );
            })
            
            // Step 4: Update state and notify
            .flatMap(paymentId ->
                Effect.modify(s -> 
                    s.withCompletedOrder(msg.order().id(), paymentId)
                )
                .andThen(Effect.tell(msg.replyTo(), new OrderCompleted(paymentId)))
                .map(_ -> paymentId)
            )
            
            // Error handling
            .recover(error -> {
                ctx.getLogger().error("Workflow failed", error);
                ctx.tell(msg.replyTo(), new OrderFailed(error.getMessage()));
                return null;
            })
    );
```

### Example 4: Composable Behaviors

```java
// Define reusable effect combinators
class EffectCombinators {
    // Retry effect up to N times
    static <S, M, R> Effect<S, M, R> retry(Effect<S, M, R> effect, int maxAttempts) {
        return (state, message, context) -> {
            EffectResult<S, R> result = null;
            for (int i = 0; i < maxAttempts; i++) {
                result = effect.run(state, message, context);
                if (result.isSuccess()) {
                    return result;
                }
                // Wait before retry
                try {
                    Thread.sleep(100 * (i + 1));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return result;
        };
    }
    
    // Timeout effect
    static <S, M, R> Effect<S, M, R> timeout(Effect<S, M, R> effect, Duration duration) {
        return (state, message, context) -> {
            CompletableFuture<EffectResult<S, R>> future = CompletableFuture.supplyAsync(
                () -> effect.run(state, message, context)
            );
            
            try {
                return future.get(duration.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                return new EffectResult.Failure<>(state, e);
            } catch (Exception e) {
                return new EffectResult.Failure<>(state, e);
            }
        };
    }
    
    // Parallel execution of multiple effects
    static <S, M> Effect<S, M, List<Object>> parallel(List<Effect<S, M, ?>> effects) {
        return (state, message, context) -> {
            List<CompletableFuture<EffectResult<S, ?>>> futures = effects.stream()
                .map(effect -> CompletableFuture.supplyAsync(
                    () -> effect.run(state, message, context)
                ))
                .toList();
            
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
                
                List<Object> results = futures.stream()
                    .map(f -> {
                        try {
                            return f.get().value().orElse(null);
                        } catch (Exception e) {
                            return null;
                        }
                    })
                    .toList();
                
                return new EffectResult.Success<>(state, results);
            } catch (Exception e) {
                return new EffectResult.Failure<>(state, e);
            }
        };
    }
}

// Usage
Effect<State, Msg, Result> robustEffect = 
    EffectCombinators.timeout(
        EffectCombinators.retry(myEffect, 3),
        Duration.ofSeconds(30)
    );
```

## Integration with Existing System

### FunctionalActor Builder API

```java
public class FunctionalActorBuilder<State, Message, Result> {
    private Effect<State, Message, Result> effect;
    private State initialState;
    private String actorId;
    
    public FunctionalActorBuilder<State, Message, Result> withEffect(Effect<State, Message, Result> effect) {
        this.effect = effect;
        return this;
    }
    
    public FunctionalActorBuilder<State, Message, Result> withInitialState(State state) {
        this.initialState = state;
        return this;
    }
    
    public FunctionalActorBuilder<State, Message, Result> withId(String id) {
        this.actorId = id;
        return this;
    }
    
    public Pid spawn() {
        // Convert Effect to StatefulHandler
        StatefulHandler<State, Message> handler = new StatefulHandler<>() {
            @Override
            public State receive(Message message, State state, ActorContext context) {
                EffectResult<State, Result> result = effect.run(state, message, context);
                return result.state();
            }
        };
        
        return system.statefulActorOf(handler, initialState)
            .withId(actorId)
            .spawn();
    }
}

// Usage
Pid actor = system.functionalActorOf(myEffect, initialState)
    .withId("my-actor")
    .spawn();
```

## Benefits of Monadic Approach

### 1. **Composability**
- Effects can be composed using `map`, `flatMap`, `andThen`
- Build complex behaviors from simple building blocks
- Reusable effect combinators

### 2. **Explicit Error Handling**
- Errors are part of the type system
- Use `recover`, `orElse` for graceful degradation
- No hidden exceptions

### 3. **Testability**
- Effects are pure functions (given same inputs, same outputs)
- Easy to test without spawning actors
- Can mock ActorContext

### 4. **Type Safety**
- State transitions are type-checked
- Message types are explicit
- Result types are clear

### 5. **Functional Programming Patterns**
- Pattern matching on messages
- Monadic composition
- Referential transparency (mostly)

### 6. **Actor-Oriented**
- Still uses actor model primitives (tell, ask, context)
- Maintains actor isolation
- Compatible with existing actors

## Migration Path

### Phase 1: Add Effect API alongside existing API
- Keep current `BiFunction` approach
- Add new `Effect` monad
- Provide conversion utilities

### Phase 2: Update examples and documentation
- Show both approaches
- Highlight benefits of Effect API
- Provide migration guide

### Phase 3: Deprecate old API (optional)
- Mark old methods as `@Deprecated`
- Provide automated migration tools
- Keep for backward compatibility

## Comparison: Before and After

### Before (Current API)

```java
BiFunction<Integer, CounterMsg, Integer> counterLogic = (state, message) -> {
    if (message instanceof Increment inc) {
        return state + inc.amount();
    } else if (message instanceof Decrement dec) {
        return state - dec.amount();
    }
    return state;
};
```

**Issues:**
- No logging without side effects
- No error handling
- Can't compose with other behaviors
- No way to send messages

### After (Monadic API)

```java
// Explicit type parameters for clarity
Effect<Integer, Throwable, Void> counterEffect = 
    Effect.<Integer, Throwable, Void, CounterMsg>match()
    .when(Increment.class, (state, msg, ctx) ->
        // Java infers types from context - no explicit type parameters needed!
        Effect.modify(s -> s + msg.amount())
            .andThen(Effect.logState(s -> "Count: " + s))
            .tap(_ -> ctx.tellSelf(new CheckThreshold()))
    )
    .when(Decrement.class, (state, msg, ctx) ->
        Effect.modify(s -> Math.max(0, s - msg.amount()))
            .filter(s -> s >= 0, 
                    s -> new IllegalStateException("Count cannot be negative: " + s))
            .andThen(Effect.logState(s -> "Count: " + s))
            .recover(error -> {
                ctx.getLogger().error("Error", error);
                return null;
            })
    );
```

**Benefits:**
- Explicit logging
- Error handling with recovery
- Composable behaviors
- Can send messages
- Type-safe

## Handling Dependencies (Simple Approach)

For actors that need external dependencies (database, other actors, config), use simple constructor injection:

```java
public class UserServiceActor {
    private final DataSource dataSource;
    private final Pid cacheActor;
    private final Logger logger;
    
    public UserServiceActor(DataSource dataSource, Pid cacheActor, Logger logger) {
        this.dataSource = dataSource;
        this.cacheActor = cacheActor;
        this.logger = logger;
    }
    
    public Effect<Map<String, User>, Throwable, User> behavior() {
        return Effect.<Map<String, User>, Throwable, User, UserMsg>match()
            .when(CreateUser.class, (state, msg, ctx) ->
                // Dependencies are just fields - simple and clear
                Effect.modify(s -> {
                    User user = saveToDatabase(dataSource, msg.user());
                    s.put(user.id(), user);
                    return s;
                })
                .andThen(Effect.tell(cacheActor, new CacheUser(msg.user())))
                .tap(_ -> logger.info("Created user: {}", msg.user().id()))
                .map(_ -> msg.user())
            );
    }
    
    private User saveToDatabase(DataSource ds, User user) {
        // JDBC code here
        return user;
    }
}

// Usage - just pass dependencies when creating the actor
DataSource dataSource = createDataSource();
Pid cacheActor = system.actorOf(CacheHandler.class).spawn();
Logger logger = LoggerFactory.getLogger("UserService");

UserServiceActor userService = new UserServiceActor(dataSource, cacheActor, logger);
Pid userServicePid = system.functionalActorOf(userService.behavior(), new HashMap<>())
    .withId("user-service")
    .spawn();
```

**Benefits:**
- Simple, idiomatic Java
- Easy to understand and maintain
- Straightforward testing (just mock the dependencies)
- No framework magic

## Conclusion

The monadic `Effect` API provides a powerful, composable, and type-safe way to build functional actors while maintaining the actor-oriented nature of Cajun. It enables:

- **Functional programming patterns** in an actor context
- **Better error handling** with explicit recovery
- **Composable behaviors** through monadic operations
- **Testable code** with pure functions
- **Type safety** throughout
- **Simple dependency management** with constructor injection

This evolution positions FunctionalActor as a first-class citizen in Cajun, suitable for building complex, maintainable actor systems with functional programming principles.

Start with the Effect monad and simple constructor injection. Only reach for more advanced patterns (like Layers) when you have a genuine need for complex dependency composition.
