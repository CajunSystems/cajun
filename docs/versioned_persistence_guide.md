# Versioned Persistence Guide

## Overview

Versioned Persistence provides automatic schema evolution for stateful actors in Cajun. When your message or state schemas change over time, versioned persistence automatically migrates old data to new formats during actor recovery.

## Why Versioned Persistence?

As your application evolves, you may need to:
- Add new fields to messages or state
- Change data types (e.g., `double` to `BigDecimal`)
- Rename or restructure data
- Remove deprecated fields

Without versioned persistence, loading old data with new code can cause:
- Deserialization errors
- Data corruption
- Application crashes
- Loss of historical data

Versioned persistence solves these problems by:
- **Tracking versions** of all persisted data
- **Automatically migrating** old data during recovery
- **Maintaining backward compatibility** with legacy data
- **Providing metrics** on migration performance

## Quick Start

### 1. Define Your Message Versions

```java
// Version 0 (original)
public record OrderMessageV0(String orderId, double amount) {}

// Version 1 (added currency)
public record OrderMessageV1(String orderId, BigDecimal amount, String currency) {}
```

### 2. Create a Message Migrator

```java
MessageMigrator migrator = new MessageMigrator();

// Register migration from V0 to V1
migrator.register(OrderMessageV0.class.getName(), 0, 1, msg -> {
    OrderMessageV0 v0 = (OrderMessageV0) msg;
    return new OrderMessageV1(
        v0.orderId(),
        BigDecimal.valueOf(v0.amount()),
        "USD"  // Default currency for old orders
    );
});
```

### 3. Create Actor with Versioned Persistence

```java
// Get your base persistence provider
PersistenceProvider baseProvider = new FileSystemPersistenceProvider("./data");

// Create actor with versioned persistence
Pid actorPid = new StatefulActorBuilder<>(system, handler, initialState)
    .withId("order-processor")
    .withVersionedPersistence(baseProvider, migrator)
    .spawn();
```

That's it! Old messages are automatically migrated during recovery.

## Core Concepts

### Version Numbers

- **Version 0 (UNVERSIONED)**: Legacy data without version metadata
- **Version 1 (CURRENT_VERSION)**: Current schema version
- Versions are integers starting from 0
- Each message/state type has its own version

### Migration Functions

Migration functions transform data from one version to another:

```java
Function<Object, Object> migrationFn = oldMessage -> {
    // Cast to old type
    OrderMessageV0 v0 = (OrderMessageV0) oldMessage;
    
    // Transform to new type
    return new OrderMessageV1(
        v0.orderId(),
        BigDecimal.valueOf(v0.amount()),
        "USD"
    );
};
```

### Auto-Migration

When enabled (default), old data is automatically migrated during:
- **Actor recovery**: When reading journal entries
- **Snapshot loading**: When loading state snapshots
- **Message replay**: During event sourcing replay

## Advanced Usage

### Bidirectional Migration

Support both upgrade and rollback:

```java
// Forward migration (V0 → V1)
migrator.register(OrderMessageV0.class.getName(), 0, 1, msg -> {
    OrderMessageV0 v0 = (OrderMessageV0) msg;
    return new OrderMessageV1(v0.orderId(), BigDecimal.valueOf(v0.amount()), "USD");
});

// Backward migration (V1 → V0) for rollback
migrator.register(OrderMessageV1.class.getName(), 1, 0, msg -> {
    OrderMessageV1 v1 = (OrderMessageV1) msg;
    return new OrderMessageV0(v1.orderId(), v1.amount().doubleValue());
});
```

### Multi-Hop Migration

Cajun automatically chains migrations:

```java
// V0 → V1
migrator.register(OrderMessageV0.class.getName(), 0, 1, /* ... */);

// V1 → V2
migrator.register(OrderMessageV1.class.getName(), 1, 2, /* ... */);

// Cajun automatically migrates V0 → V1 → V2
```

### Custom Version and Settings

```java
Pid actorPid = new StatefulActorBuilder<>(system, handler, initialState)
    .withId("order-processor")
    .withVersionedPersistence(
        baseProvider,
        migrator,
        2,      // Current version
        true    // Auto-migrate enabled
    )
    .spawn();
```

### Migration Metrics

Track migration performance:

```java
VersionedPersistenceProvider versionedProvider = 
    new VersionedPersistenceProvider(baseProvider, migrator);

// Get migration statistics
MigrationMetrics.MigrationStats stats = versionedProvider.getMigrationStats();

System.out.println("Total migrations: " + stats.totalMigrations());
System.out.println("Success rate: " + stats.successRate() + "%");
System.out.println("Average time: " + stats.averageMigrationTimeMillis() + "ms");
```

## Best Practices

### 1. Version Your Data Early

Even if you don't need migrations yet, start with versioned persistence:

```java
// Always use versioned persistence for new actors
.withVersionedPersistence(baseProvider, migrator)
```

### 2. Keep Old Message Classes

Don't delete old message classes - you need them for migration:

```java
// Keep these around even after upgrading
public record OrderMessageV0(String orderId, double amount) {}
public record OrderMessageV1(String orderId, BigDecimal amount, String currency) {}
public record OrderMessageV2(String orderId, BigDecimal amount, String currency, String customerId) {}
```

### 3. Test Migrations

Write tests for your migration functions:

```java
@Test
void testOrderMigrationV0ToV1() {
    OrderMessageV0 v0 = new OrderMessageV0("ORD-123", 99.99);
    OrderMessageV1 v1 = migrator.migrate(v0, 0, 1);
    
    assertEquals("ORD-123", v1.orderId());
    assertEquals(BigDecimal.valueOf(99.99), v1.amount());
    assertEquals("USD", v1.currency());
}
```

### 4. Provide Sensible Defaults

When adding new fields, provide reasonable defaults:

```java
migrator.register(OrderMessageV0.class.getName(), 0, 1, msg -> {
    OrderMessageV0 v0 = (OrderMessageV0) msg;
    return new OrderMessageV1(
        v0.orderId(),
        BigDecimal.valueOf(v0.amount()),
        "USD"  // ✅ Sensible default
    );
});
```

### 5. Document Migration Logic

Add comments explaining why migrations exist:

```java
// Migration V0 → V1: Added currency field
// Default to USD for all legacy orders (pre-2024)
migrator.register(OrderMessageV0.class.getName(), 0, 1, msg -> {
    // ...
});
```

### 6. Monitor Migration Metrics

In production, monitor migration performance:

```java
MigrationMetrics.MigrationStats stats = provider.getMigrationStats();

if (stats.failureRate() > 5.0) {
    logger.error("High migration failure rate: {}%", stats.failureRate());
}

if (stats.averageMigrationTimeMillis() > 100) {
    logger.warn("Slow migrations detected: {}ms avg", stats.averageMigrationTimeMillis());
}
```

## Migration Patterns

### Pattern 1: Adding Fields

```java
// V0: Original
record Order(String id, double amount) {}

// V1: Added currency
record Order(String id, BigDecimal amount, String currency) {}

// Migration: Provide default
migrator.register(Order.class.getName(), 0, 1, msg -> {
    Order v0 = (Order) msg;
    return new Order(v0.id(), BigDecimal.valueOf(v0.amount()), "USD");
});
```

### Pattern 2: Changing Types

```java
// V0: Using double
record Order(String id, double amount) {}

// V1: Using BigDecimal
record Order(String id, BigDecimal amount) {}

// Migration: Convert type
migrator.register(Order.class.getName(), 0, 1, msg -> {
    Order v0 = (Order) msg;
    return new Order(v0.id(), BigDecimal.valueOf(v0.amount()));
});
```

### Pattern 3: Restructuring Data

```java
// V0: Flat structure
record Order(String id, String customerName, String customerEmail) {}

// V1: Nested structure
record Customer(String name, String email) {}
record Order(String id, Customer customer) {}

// Migration: Restructure
migrator.register(Order.class.getName(), 0, 1, msg -> {
    Order v0 = (Order) msg;
    Customer customer = new Customer(v0.customerName(), v0.customerEmail());
    return new Order(v0.id(), customer);
});
```

### Pattern 4: Removing Fields

```java
// V0: With deprecated field
record Order(String id, double amount, String legacyCode) {}

// V1: Removed legacyCode
record Order(String id, BigDecimal amount) {}

// Migration: Drop field
migrator.register(Order.class.getName(), 0, 1, msg -> {
    Order v0 = (Order) msg;
    return new Order(v0.id(), BigDecimal.valueOf(v0.amount()));
});
```

## Troubleshooting

### Migration Fails

**Problem**: `MigrationException: No migration path from v0 to v2`

**Solution**: Register all intermediate migrations:

```java
// Need both migrations for v0 → v2
migrator.register(Message.class.getName(), 0, 1, /* ... */);
migrator.register(Message.class.getName(), 1, 2, /* ... */);
```

### Null Pointer During Migration

**Problem**: `NullPointerException` in migration function

**Solution**: Handle null values:

```java
migrator.register(Order.class.getName(), 0, 1, msg -> {
    Order v0 = (Order) msg;
    String currency = v0.currency() != null ? v0.currency() : "USD";
    return new Order(v0.id(), v0.amount(), currency);
});
```

### Slow Migrations

**Problem**: Migrations taking too long

**Solution**: Optimize migration logic, avoid I/O:

```java
// ❌ Bad: I/O in migration
migrator.register(Order.class.getName(), 0, 1, msg -> {
    Order v0 = (Order) msg;
    String currency = database.lookupCurrency(v0.id());  // Slow!
    return new Order(v0.id(), v0.amount(), currency);
});

// ✅ Good: Pure transformation
migrator.register(Order.class.getName(), 0, 1, msg -> {
    Order v0 = (Order) msg;
    return new Order(v0.id(), v0.amount(), "USD");  // Fast!
});
```

## Performance Considerations

### Migration Overhead

- **Current version data**: Zero overhead (no migration)
- **Old version data**: Migration happens once during recovery
- **Lock-free**: All operations use atomic data structures
- **In-memory**: Migrations are pure transformations

### When Migrations Occur

Migrations happen during:
1. **Actor recovery** - When reading journal entries
2. **Snapshot loading** - When loading state snapshots
3. **Never during writes** - Writes always use current version

### Optimization Tips

1. **Keep migrations simple** - Pure data transformations only
2. **No I/O in migrations** - Avoid database/network calls
3. **Batch recovery** - Let actors recover in parallel
4. **Monitor metrics** - Track migration performance

## API Reference

### MessageMigrator

```java
// Register migration
migrator.register(String messageType, int from, int to, Function<Object, Object> fn)

// Check if migration exists
migrator.hasMigration(String messageType, int from, int to)

// Check if path exists (including multi-hop)
migrator.hasMigrationPath(String messageType, int from, int to)

// Perform migration
<M> M migrate(M message, int fromVersion, int toVersion)

// Get migration metrics
MigrationMetrics getMetrics()
```

### VersionedPersistenceProvider

```java
// Create with defaults
new VersionedPersistenceProvider(PersistenceProvider delegate, MessageMigrator migrator)

// Create with custom settings
new VersionedPersistenceProvider(
    PersistenceProvider delegate,
    MessageMigrator migrator,
    int currentVersion,
    boolean autoMigrate
)

// Get migration statistics
MigrationMetrics.MigrationStats getMigrationStats()
```

### StatefulActorBuilder

```java
// Enable versioned persistence (simple)
.withVersionedPersistence(PersistenceProvider provider, MessageMigrator migrator)

// Enable versioned persistence (advanced)
.withVersionedPersistence(
    PersistenceProvider provider,
    MessageMigrator migrator,
    int currentVersion,
    boolean autoMigrate
)
```

## See Also

- [Message Versioning Strategies](message_versioning_strategies.md) - Design rationale
- [Implementation Plan](versioned_persistence_implementation_plan.md) - Technical details
- [Stateful Actors Guide](../README.md#stateful-actors) - General persistence guide
