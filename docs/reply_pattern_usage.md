# Reply Pattern Usage Guide

The Reply pattern provides a streamlined, 3-tier API for the ask pattern in Cajun, making it easy to work with asynchronous responses from actors.

## Overview

The `Reply<T>` interface wraps a `CompletableFuture` and provides three tiers of API:

1. **Tier 1 (Simple)**: `get()` - Just blocks and returns the value
2. **Tier 2 (Safe)**: `await()` - Returns `Result` for pattern matching
3. **Tier 3 (Advanced)**: `future()` - Access underlying `CompletableFuture`

## Basic Usage

### Tier 1: Simple API

The simplest way to use the ask pattern - just block and get the value:

```java
// Clean and simple - let exceptions propagate
String name = userActor.ask(new GetName(), Duration.ofSeconds(5)).get();
int balance = accountActor.ask(new GetBalance(), Duration.ofSeconds(5)).get();

// With timeout
String result = actor.ask(msg, Duration.ofSeconds(5)).get(Duration.ofSeconds(2));
```

**When to use**: Quick scripts, tests, or when you're confident the operation will succeed.

**Exception handling**: Throws `ReplyException` (unchecked) if the ask fails, or `TimeoutException` if timeout expires.

### Tier 2: Safe API (Pattern Matching)

Use `Result` for explicit error handling with Java's pattern matching:

```java
switch (userActor.ask(new GetProfile(), Duration.ofSeconds(5)).await()) {
    case Result.Success(var profile) -> {
        System.out.println("Got profile: " + profile);
    }
    case Result.Failure(var error) -> {
        log.error("Failed to get profile", error);
    }
}
```

**With getOrElse**:

```java
String name = userActor.ask(new GetName(), Duration.ofSeconds(5))
    .await()
    .getOrElse("Anonymous");
```

**Chaining with Result**:

```java
Result<String> result = userActor.ask(new GetEmail(), Duration.ofSeconds(5))
    .await()
    .map(String::toUpperCase)
    .recover(ex -> "no-email@example.com");
```

**When to use**: Production code where you need explicit error handling without exceptions.

### Tier 3: Advanced API (CompletableFuture)

Access the underlying `CompletableFuture` for complex async composition:

```java
Reply<User> userReply = userActor.ask(new GetUser(userId), Duration.ofSeconds(5));
Reply<Orders> ordersReply = orderActor.ask(new GetOrders(userId), Duration.ofSeconds(5));

// Combine multiple asks
CompletableFuture<UserWithOrders> combined = userReply.future()
    .thenCombine(ordersReply.future(), 
        (user, orders) -> new UserWithOrders(user, orders));

Reply<UserWithOrders> result = Reply.from(combined);
```

**When to use**: Complex async workflows, parallel operations, or when you need full `CompletableFuture` power.

## Monadic Operations

### map - Transform the reply value

```java
Reply<String> result = userActor.ask(new GetUserId(), Duration.ofSeconds(5))
    .map(userId -> "User: " + userId);

String displayName = result.get();
```

### flatMap - Chain async operations

```java
Reply<String> result = userActor.ask(new GetUserId(), Duration.ofSeconds(5))
    .flatMap(userId -> profileActor.ask(new GetProfile(userId), Duration.ofSeconds(5)))
    .map(profile -> profile.displayName())
    .recover(ex -> "Unknown User");

String displayName = result.get();
```

### recover - Provide fallback on error

```java
Reply<String> result = actor.ask(new GetData(), Duration.ofSeconds(5))
    .recover(ex -> "Default Value");

String data = result.get(); // Never throws
```

### recoverWith - Provide fallback Reply on error

```java
Reply<String> result = primaryActor.ask(new GetData(), Duration.ofSeconds(5))
    .recoverWith(ex -> backupActor.ask(new GetData(), Duration.ofSeconds(5)));

String data = result.get();
```

## Callback API (Non-blocking)

### onComplete - Handle both success and failure

```java
actor.ask(new ProcessData(), Duration.ofSeconds(5))
    .onComplete(
        data -> log.info("Success: {}", data),
        error -> log.error("Failed", error)
    );
```

### onSuccess - Handle success only

```java
actor.ask(new GetStats(), Duration.ofSeconds(5))
    .onSuccess(stats -> updateDashboard(stats));
```

### onFailure - Handle failure only

```java
actor.ask(new RiskyOperation(), Duration.ofSeconds(5))
    .onFailure(error -> alertOps(error));
```

## Factory Methods

### Reply.completed - Already-completed successful Reply

```java
Reply<String> reply = Reply.completed("immediate value");
String value = reply.get(); // Returns immediately
```

### Reply.failed - Already-failed Reply

```java
Reply<String> reply = Reply.failed(new RuntimeException("error"));
// Use with recover to provide defaults
String value = reply.recover(ex -> "default").get();
```

### Reply.from - Create from CompletableFuture

```java
CompletableFuture<String> future = someAsyncOperation();
Reply<String> reply = Reply.from(future);
```

## Result Operations

The `Result<T>` type provides its own monadic operations:

### map - Transform success value

```java
Result<String> result = Result.success("hello");
Result<String> upper = result.map(String::toUpperCase);
```

### flatMap - Chain Results

```java
Result<String> result = Result.success("5");
Result<Integer> number = result.flatMap(s -> Result.success(Integer.parseInt(s)));
```

### recover - Handle failure

```java
Result<String> failure = Result.failure(new RuntimeException("error"));
Result<String> recovered = failure.recover(ex -> "recovered");
```

### ifSuccess / ifFailure - Side effects

```java
result.ifSuccess(value -> log.info("Got: {}", value));
result.ifFailure(error -> log.error("Failed", error));
```

### Result.attempt - Execute code that might throw

```java
Result<Integer> result = Result.attempt(() -> {
    return Integer.parseInt(input);
});
```

## Complete Examples

### Example 1: Simple request-response

```java
ActorSystem system = new ActorSystem();
Pid userActor = system.actorOf(UserHandler.class).spawn();

// Simple - just get the value
String name = userActor.ask(new GetName(), Duration.ofSeconds(5)).get();
System.out.println("Name: " + name);
```

### Example 2: Safe error handling

```java
Reply<User> reply = userActor.ask(new GetUser(userId), Duration.ofSeconds(5));

switch (reply.await()) {
    case Result.Success(var user) -> {
        processUser(user);
    }
    case Result.Failure(var error) -> {
        if (error instanceof TimeoutException) {
            log.warn("User service timeout");
        } else {
            log.error("Failed to get user", error);
        }
    }
}
```

### Example 3: Chained operations

```java
String result = userActor.ask(new GetUserId(), Duration.ofSeconds(5))
    .map(String::toUpperCase)
    .flatMap(userId -> profileActor.ask(new GetProfile(userId), Duration.ofSeconds(5)))
    .map(profile -> profile.displayName())
    .recover(ex -> "Unknown User")
    .get();
```

### Example 4: Parallel requests

```java
Reply<User> userReply = userActor.ask(new GetUser(id), Duration.ofSeconds(5));
Reply<Orders> ordersReply = orderActor.ask(new GetOrders(id), Duration.ofSeconds(5));
Reply<Preferences> prefsReply = prefsActor.ask(new GetPrefs(id), Duration.ofSeconds(5));

CompletableFuture<Dashboard> dashboard = userReply.future()
    .thenCombine(ordersReply.future(), UserWithOrders::new)
    .thenCombine(prefsReply.future(), 
        (userOrders, prefs) -> new Dashboard(userOrders, prefs));

Dashboard result = Reply.from(dashboard).get();
```

### Example 5: Non-blocking callbacks

```java
actor.ask(new ProcessLargeDataset(), Duration.ofMinutes(5))
    .onSuccess(result -> {
        log.info("Processing complete: {}", result);
        notifyUser(result);
    })
    .onFailure(error -> {
        log.error("Processing failed", error);
        alertOps(error);
    });

// Continue with other work - callbacks will fire when complete
```

## Best Practices

1. **Choose the right tier for your use case**:
   - Use Tier 1 (get) for simple cases and tests
   - Use Tier 2 (await) for production code with explicit error handling
   - Use Tier 3 (future) for complex async composition

2. **Set appropriate timeouts**:
   ```java
   // Too short - might timeout unnecessarily
   reply.get(Duration.ofMillis(10));
   
   // Better - reasonable timeout for the operation
   reply.get(Duration.ofSeconds(5));
   ```

3. **Use pattern matching for clear error handling**:
   ```java
   switch (reply.await()) {
       case Result.Success(var value) -> handleSuccess(value);
       case Result.Failure(var error) -> handleError(error);
   }
   ```

4. **Chain operations for cleaner code**:
   ```java
   // Instead of nested asks
   String result = actor1.ask(msg1, timeout).get();
   String result2 = actor2.ask(new Msg(result), timeout).get();
   
   // Use flatMap
   String result = actor1.ask(msg1, timeout)
       .flatMap(r -> actor2.ask(new Msg(r), timeout))
       .get();
   ```

5. **Use callbacks for fire-and-forget operations**:
   ```java
   actor.ask(msg, timeout)
       .onSuccess(result -> log.info("Done: {}", result))
       .onFailure(error -> log.error("Failed", error));
   ```

## Migration from CompletableFuture

If you're currently using `ActorSystem.ask()` which returns `CompletableFuture`, you can easily migrate:

```java
// Old way
CompletableFuture<String> future = system.ask(actor, msg, timeout);
String result = future.get();

// New way - Tier 1
String result = actor.ask(msg, timeout).get();

// New way - Tier 2 (safer)
Result<String> result = actor.ask(msg, timeout).await();

// New way - Tier 3 (if you need CompletableFuture)
CompletableFuture<String> future = actor.ask(msg, timeout).future();
```

## Summary

The Reply pattern gives you maximum flexibility:

- **Start simple** with `.get()` for straightforward cases
- **Use pattern matching** with `.await()` when you need explicit error handling
- **Drop down to CompletableFuture** with `.future()` for complex async composition

All three tiers work together seamlessly, allowing you to choose the right level of abstraction for each use case.
