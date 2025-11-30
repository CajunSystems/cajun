# Actor ID Strategies

Cajun provides a flexible and powerful system for managing actor identities. This guide covers all the ways you can control how actors are identified in your system.

## Table of Contents

- [Overview](#overview)
- [ID Priority System](#id-priority-system)
- [Explicit IDs](#explicit-ids)
- [ID Templates](#id-templates)
- [ID Strategies](#id-strategies)
- [Hierarchical IDs](#hierarchical-ids)
- [Best Practices](#best-practices)
- [Examples](#examples)

## Overview

Every actor in Cajun has a unique identifier (ID) that is used for:
- **Message routing**: Sending messages to specific actors
- **Logging and debugging**: Identifying actors in logs
- **Persistence**: Recovering actor state after restarts
- **Hierarchical organization**: Creating parent-child relationships

Cajun provides four ways to control actor IDs, with a clear priority system:

1. **Explicit IDs** (Highest Priority) - Manually specify the exact ID
2. **ID Templates** - Generate IDs using placeholders
3. **ID Strategies** - Use predefined ID generation strategies
4. **System Default** (Lowest Priority) - Falls back to UUID

## ID Priority System

When you configure multiple ID settings, Cajun uses this priority order:

```java
// Priority 1: Explicit ID wins
Pid actor = system.actorOf(Handler.class)
    .withId("my-actor")           // ← This is used
    .withIdTemplate("user-{seq}") // ← Ignored
    .withIdStrategy(IdStrategy.UUID) // ← Ignored
    .spawn();
// Result: "my-actor"

// Priority 2: Template wins over strategy
Pid actor = system.actorOf(Handler.class)
    .withIdTemplate("user-{seq}") // ← This is used
    .withIdStrategy(IdStrategy.UUID) // ← Ignored
    .spawn();
// Result: "user-1", "user-2", etc.

// Priority 3: Strategy is used
Pid actor = system.actorOf(Handler.class)
    .withIdStrategy(IdStrategy.CLASS_BASED_SEQUENTIAL) // ← This is used
    .spawn();
// Result: "myhandler:1", "myhandler:2", etc.

// Priority 4: System default (UUID)
Pid actor = system.actorOf(Handler.class)
    .spawn();
// Result: "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
```

**Important**: Each `withId()`, `withIdTemplate()`, and `withIdStrategy()` call replaces any previous ID configuration. Only the last one in the chain is effective.

## Explicit IDs

Manually specify the exact ID for an actor. Best for:
- Singleton actors with well-known names
- Actors that need to be looked up by name
- Testing and debugging

### Usage

```java
// Simple explicit ID
Pid actor = system.actorOf(MyHandler.class)
    .withId("user-service")
    .spawn();

// Explicit IDs can contain any characters
Pid actor = system.actorOf(MyHandler.class)
    .withId("user:123:profile")
    .spawn();

// Unicode characters are supported
Pid actor = system.actorOf(MyHandler.class)
    .withId("actor-测试-🎭")
    .spawn();
```

### Pros and Cons

**Pros:**
- ✅ Predictable and easy to debug
- ✅ Can be looked up by name
- ✅ Great for singleton services

**Cons:**
- ⚠️ You must ensure uniqueness manually
- ⚠️ Not suitable for dynamic actor creation
- ⚠️ Duplicate IDs will cause errors

## ID Templates

Generate IDs dynamically using placeholders. Best for:
- Creating multiple actors with consistent naming
- Including dynamic information in IDs
- Maintaining readable IDs with auto-incrementing counters

### Available Placeholders

| Placeholder | Description | Example Output |
|------------|-------------|----------------|
| `{seq}` | Auto-incrementing sequence number | `1`, `2`, `3` |
| `{template-seq}` | Sequence per template pattern | `1`, `2`, `3` |
| `{uuid}` | Full UUID | `a1b2c3d4-e5f6-7890-abcd-ef1234567890` |
| `{short-uuid}` | First 8 characters of UUID | `a1b2c3d4` |
| `{timestamp}` | Current timestamp (milliseconds) | `1732956789123` |
| `{nano}` | Current nanosecond time | `1732956789123456789` |
| `{class}` | Simplified class name (lowercase) | `myhandler` |
| `{parent}` | Parent actor ID (if hierarchical) | `parent-actor` |

### Usage

```java
// Simple sequence
Pid actor = system.actorOf(MyHandler.class)
    .withIdTemplate("user-{seq}")
    .spawn();
// Result: "user-1", "user-2", "user-3", ...

// Multiple placeholders
Pid actor = system.actorOf(MyHandler.class)
    .withIdTemplate("{class}-{seq}-{short-uuid}")
    .spawn();
// Result: "myhandler-1-a1b2c3d4"

// Template with timestamp
Pid actor = system.actorOf(MyHandler.class)
    .withIdTemplate("session-{timestamp}-{seq}")
    .spawn();
// Result: "session-1732956789123-1"

// Complex template
Pid actor = system.actorOf(MyHandler.class)
    .withIdTemplate("{class}-{seq}-{uuid}-{timestamp}")
    .spawn();
// Result: "myhandler-1-a1b2c3d4-e5f6-7890-abcd-ef1234567890-1732956789123"
```

### Sequence Counters

- **`{seq}`**: Global counter per template prefix
  - `"user-{seq}"` → `user-1`, `user-2`, `user-3`
  - `"order-{seq}"` → `order-1`, `order-2`, `order-3`
  - Different prefixes maintain separate counters

- **`{template-seq}`**: Counter per exact template pattern
  - Same template = same counter
  - Different templates = different counters

```java
// Separate counters for different templates
Pid user1 = system.actorOf(Handler.class)
    .withIdTemplate("user-{seq}")
    .spawn(); // "user-1"

Pid order1 = system.actorOf(Handler.class)
    .withIdTemplate("order-{seq}")
    .spawn(); // "order-1"

Pid user2 = system.actorOf(Handler.class)
    .withIdTemplate("user-{seq}")
    .spawn(); // "user-2"
```

### Pros and Cons

**Pros:**
- ✅ Readable and meaningful IDs
- ✅ Automatic uniqueness via counters
- ✅ Flexible composition of information
- ✅ Great for debugging and logging

**Cons:**
- ⚠️ Counters are not persistent across restarts
- ⚠️ Slightly more overhead than strategies

## ID Strategies

Predefined strategies for common ID generation patterns. Best for:
- Consistent ID generation across your application
- When you don't need custom formatting
- Maximum performance

### Available Strategies

#### 1. UUID (Default)

Generates a random UUID for each actor.

```java
Pid actor = system.actorOf(MyHandler.class)
    .withIdStrategy(IdStrategy.UUID)
    .spawn();
// Result: "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
```

**Pros:**
- ✅ Guaranteed unique
- ✅ No coordination needed
- ✅ Works in distributed systems

**Cons:**
- ⚠️ Not human-readable
- ⚠️ Can't infer actor type from ID

#### 2. CLASS_BASED_UUID

Combines class name with UUID: `{class}:{uuid}`

```java
Pid actor = system.actorOf(MyHandler.class)
    .withIdStrategy(IdStrategy.CLASS_BASED_UUID)
    .spawn();
// Result: "myhandler:a1b2c3d4-e5f6-7890-abcd-ef1234567890"
```

**Pros:**
- ✅ Unique and identifiable
- ✅ Can filter by class in logs
- ✅ Good for debugging

**Cons:**
- ⚠️ Longer IDs
- ⚠️ Still not very readable

#### 3. CLASS_BASED_SEQUENTIAL

Combines class name with auto-incrementing counter: `{class}:{seq}`

```java
Pid actor = system.actorOf(MyHandler.class)
    .withIdStrategy(IdStrategy.CLASS_BASED_SEQUENTIAL)
    .spawn();
// Result: "myhandler:1", "myhandler:2", "myhandler:3", ...
```

**Pros:**
- ✅ Very readable
- ✅ Short IDs
- ✅ Easy to track actor count
- ✅ Great for debugging

**Cons:**
- ⚠️ Counters not persistent
- ⚠️ Not suitable for distributed systems

#### 4. SEQUENTIAL

Simple auto-incrementing counter.

```java
Pid actor = system.actorOf(MyHandler.class)
    .withIdStrategy(IdStrategy.SEQUENTIAL)
    .spawn();
// Result: "1", "2", "3", ...
```

**Pros:**
- ✅ Shortest possible IDs
- ✅ Maximum performance

**Cons:**
- ⚠️ No context about actor type
- ⚠️ Only suitable for simple cases

### Strategy Comparison

| Strategy | Example ID | Readability | Uniqueness | Use Case |
|----------|-----------|-------------|------------|----------|
| UUID | `a1b2...` | ⭐ | ⭐⭐⭐⭐⭐ | Distributed systems |
| CLASS_BASED_UUID | `handler:a1b2...` | ⭐⭐ | ⭐⭐⭐⭐⭐ | Multi-class systems |
| CLASS_BASED_SEQUENTIAL | `handler:1` | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | Single-node apps |
| SEQUENTIAL | `1` | ⭐⭐⭐ | ⭐⭐⭐ | Simple testing |

## Hierarchical IDs

Create parent-child relationships with automatic ID prefixing.

### Basic Hierarchy

```java
// Create parent
Pid parent = system.actorOf(ParentHandler.class)
    .withId("parent")
    .spawn();

// Create child - ID is automatically prefixed
Pid child = system.actorOf(ChildHandler.class)
    .withId("child")
    .withParent(system.getActor(parent))
    .spawn();
// Result: "parent/child"
```

### Hierarchies with Templates

```java
Pid parent = system.actorOf(ParentHandler.class)
    .withId("parent")
    .spawn();

// Children with sequential IDs
Pid child1 = system.actorOf(ChildHandler.class)
    .withIdTemplate("child-{seq}")
    .withParent(system.getActor(parent))
    .spawn();
// Result: "parent/child-1"

Pid child2 = system.actorOf(ChildHandler.class)
    .withIdTemplate("child-{seq}")
    .withParent(system.getActor(parent))
    .spawn();
// Result: "parent/child-2"
```

### Hierarchies with Strategies

```java
Pid parent = system.actorOf(ParentHandler.class)
    .withId("parent")
    .spawn();

Pid child = system.actorOf(ChildHandler.class)
    .withIdStrategy(IdStrategy.CLASS_BASED_SEQUENTIAL)
    .withParent(system.getActor(parent))
    .spawn();
// Result: "parent/childhandler:1"
```

### Deep Hierarchies

```java
Pid grandparent = system.actorOf(Handler.class)
    .withId("grandparent")
    .spawn();

Pid parent = system.actorOf(Handler.class)
    .withId("parent")
    .withParent(system.getActor(grandparent))
    .spawn();
// Result: "grandparent/parent"

Pid child = system.actorOf(Handler.class)
    .withId("child")
    .withParent(system.getActor(parent))
    .spawn();
// Result: "grandparent/parent/child"
```

### Using {parent} Placeholder

```java
Pid parent = system.actorOf(ParentHandler.class)
    .withId("parent")
    .spawn();

Pid child = system.actorOf(ChildHandler.class)
    .withIdTemplate("{parent}/child-{seq}")
    .withParent(system.getActor(parent))
    .spawn();
// Result: "parent/child-1"
```

## Best Practices

### 1. Choose the Right Approach

```java
// ✅ Good: Explicit IDs for singletons
Pid userService = system.actorOf(UserServiceHandler.class)
    .withId("user-service")
    .spawn();

// ✅ Good: Templates for dynamic actors
Pid session = system.actorOf(SessionHandler.class)
    .withIdTemplate("session-{seq}")
    .spawn();

// ✅ Good: Strategies for consistency
Pid worker = system.actorOf(WorkerHandler.class)
    .withIdStrategy(IdStrategy.CLASS_BASED_SEQUENTIAL)
    .spawn();

// ❌ Bad: Mixing approaches unnecessarily
Pid actor = system.actorOf(Handler.class)
    .withId("actor")
    .withIdTemplate("template-{seq}")  // Ignored!
    .withIdStrategy(IdStrategy.UUID)   // Ignored!
    .spawn();
```

### 2. Use Meaningful Names

```java
// ✅ Good: Descriptive IDs
.withIdTemplate("user-session-{seq}")
.withIdTemplate("order-processor-{timestamp}")
.withIdTemplate("{class}-worker-{seq}")

// ❌ Bad: Generic IDs
.withIdTemplate("actor-{seq}")
.withIdTemplate("thing-{uuid}")
```

### 3. Consider Persistence

```java
// ✅ Good: For stateful actors, use stable IDs
Pid counter = system.statefulActorOf(CounterHandler.class, 0)
    .withId("global-counter")  // Same ID after restart
    .withPersistence(...)
    .spawn();

// ⚠️ Caution: Sequential IDs reset on restart
Pid temp = system.actorOf(TempHandler.class)
    .withIdTemplate("temp-{seq}")  // Counter resets!
    .spawn();
```

### 4. Hierarchies for Organization

```java
// ✅ Good: Organize related actors
Pid service = system.actorOf(ServiceHandler.class)
    .withId("user-service")
    .spawn();

Pid cache = system.actorOf(CacheHandler.class)
    .withId("cache")
    .withParent(system.getActor(service))
    .spawn();
// Result: "user-service/cache"

Pid db = system.actorOf(DbHandler.class)
    .withId("database")
    .withParent(system.getActor(service))
    .spawn();
// Result: "user-service/database"
```

### 5. Logging and Debugging

```java
// ✅ Good: IDs that help debugging
.withIdTemplate("{class}-{seq}-{short-uuid}")
// Result: "userhandler-1-a1b2c3d4"
// You can see: type, order, and unique identifier

// ✅ Good: Include context in IDs
.withIdTemplate("tenant-{tenant-id}-user-{seq}")
// Result: "tenant-123-user-1"
```

## Examples

### Example 1: User Session Management

```java
public class SessionManager {
    private final ActorSystem system;
    
    public Pid createSession(String userId) {
        return system.actorOf(SessionHandler.class)
            .withIdTemplate("session-" + userId + "-{timestamp}")
            .spawn();
        // Result: "session-user123-1732956789123"
    }
}
```

### Example 2: Worker Pool

```java
public class WorkerPool {
    private final ActorSystem system;
    private final List<Pid> workers = new ArrayList<>();
    
    public void createWorkers(int count) {
        for (int i = 0; i < count; i++) {
            Pid worker = system.actorOf(WorkerHandler.class)
                .withIdStrategy(IdStrategy.CLASS_BASED_SEQUENTIAL)
                .spawn();
            workers.add(worker);
        }
        // Result: "workerhandler:1", "workerhandler:2", ...
    }
}
```

### Example 3: Microservice Architecture

```java
public class MicroserviceActors {
    private final ActorSystem system;
    
    public void setupServices() {
        // API Gateway
        Pid gateway = system.actorOf(GatewayHandler.class)
            .withId("api-gateway")
            .spawn();
        
        // User Service with children
        Pid userService = system.actorOf(UserServiceHandler.class)
            .withId("user-service")
            .spawn();
        
        Pid userCache = system.actorOf(CacheHandler.class)
            .withId("cache")
            .withParent(system.getActor(userService))
            .spawn();
        // Result: "user-service/cache"
        
        Pid userDb = system.actorOf(DbHandler.class)
            .withId("database")
            .withParent(system.getActor(userService))
            .spawn();
        // Result: "user-service/database"
        
        // Order Service
        Pid orderService = system.actorOf(OrderServiceHandler.class)
            .withId("order-service")
            .spawn();
        
        // Dynamic order processors
        for (int i = 0; i < 5; i++) {
            system.actorOf(OrderProcessorHandler.class)
                .withIdTemplate("processor-{seq}")
                .withParent(system.getActor(orderService))
                .spawn();
        }
        // Result: "order-service/processor-1", "order-service/processor-2", ...
    }
}
```

### Example 4: Testing with Predictable IDs

```java
@Test
void testActorCommunication() {
    ActorSystem system = new ActorSystem();
    
    // Use explicit IDs for easy testing
    Pid sender = system.actorOf(SenderHandler.class)
        .withId("test-sender")
        .spawn();
    
    Pid receiver = system.actorOf(ReceiverHandler.class)
        .withId("test-receiver")
        .spawn();
    
    // Easy to verify in logs and assertions
    sender.tell(new SendTo("test-receiver", "Hello"));
    
    // Can look up by ID
    Actor<?> receiverActor = system.getActor(receiver);
    assertNotNull(receiverActor);
}
```

### Example 5: Multi-Tenant System

```java
public class TenantManager {
    private final ActorSystem system;
    
    public Pid createTenantActor(String tenantId) {
        // Tenant supervisor
        Pid tenant = system.actorOf(TenantHandler.class)
            .withId("tenant-" + tenantId)
            .spawn();
        
        // Tenant-specific workers
        for (String service : List.of("auth", "data", "cache")) {
            system.actorOf(ServiceHandler.class)
                .withIdTemplate(service + "-{seq}")
                .withParent(system.getActor(tenant))
                .spawn();
        }
        // Result: "tenant-123/auth-1", "tenant-123/data-1", "tenant-123/cache-1"
        
        return tenant;
    }
}
```

## Summary

Cajun's ID system provides flexibility for every use case:

- **Explicit IDs**: For singletons and well-known actors
- **Templates**: For readable, dynamic IDs with context
- **Strategies**: For consistent, automatic ID generation
- **Hierarchies**: For organizing related actors

Choose the approach that best fits your needs, and remember the priority system when combining multiple approaches.

For more information, see:
- [Main README](../README.md)
- [Actor Hierarchies](actor_hierarchies.md)
- [Testing Guide](../test-utils/README.md)
