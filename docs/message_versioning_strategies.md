# Message Versioning and Schema Evolution for Stateful Actors

## The Problem

When using stateful actors with persistence, message protocol evolution poses a critical challenge:

1. **Old persisted messages** in the journal use the old message schema
2. **New application code** expects the new message schema
3. **State snapshots** may contain old message types
4. **Replay during recovery** attempts to deserialize old messages with new code

Without proper versioning, this leads to:
- `ClassNotFoundException` when old message classes are removed
- `InvalidClassException` when message structure changes incompatibly
- State corruption when messages are misinterpreted
- System crashes during recovery

## Core Principles

### 1. Never Break Backward Compatibility in Persistence
Once a message is persisted, you must be able to read it forever (or have a migration path).

### 2. Version Everything
Messages, state, and the persistence format itself should all be versioned.

### 3. Fail Explicitly
Better to fail fast with a clear error than silently corrupt state.

## Strategy 1: Java Serialization with serialVersionUID (Basic)

### Overview
Use Java's built-in serialization versioning mechanism.

### Implementation

```java
// Version 1 of your message
public class OrderMessage implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private String orderId;
    private double amount;
    
    // getters, setters, constructors
}

// Version 2 - Adding a new field (compatible change)
public class OrderMessage implements Serializable {
    private static final long serialVersionUID = 1L;  // Keep same version
    
    private String orderId;
    private double amount;
    private String currency = "USD";  // Default value for backward compatibility
    
    // getters, setters, constructors
}

// Version 3 - Incompatible change (field type change)
public class OrderMessage implements Serializable {
    private static final long serialVersionUID = 2L;  // Increment version
    
    private String orderId;
    private BigDecimal amount;  // Changed from double
    private String currency = "USD";
    
    // Custom serialization for migration
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        // Migration logic if needed
    }
}
```

### Pros
- Built into Java
- Simple for compatible changes
- No external dependencies

### Cons
- Limited migration capabilities
- Brittle for complex changes
- Tightly couples to Java serialization
- Poor cross-language support

### When to Use
- Simple message schemas
- Infrequent schema changes
- Java-only systems
- Prototyping

## Strategy 2: Explicit Message Versioning with Wrappers

### Overview
Wrap all messages with version metadata and use explicit version handling.

### Implementation

```java
// Version envelope
public record VersionedMessage<T>(
    int version,
    String messageType,
    T payload
) implements Serializable {
    private static final long serialVersionUID = 1L;
}

// Version 1 of the message
public record OrderMessageV1(
    String orderId,
    double amount
) implements Serializable {
    private static final long serialVersionUID = 1L;
}

// Version 2 of the message
public record OrderMessageV2(
    String orderId,
    BigDecimal amount,
    String currency
) implements Serializable {
    private static final long serialVersionUID = 1L;
}

// Message adapter that handles version migration
public class OrderMessageAdapter {
    
    public static OrderMessageV2 migrate(VersionedMessage<?> versioned) {
        return switch (versioned.version()) {
            case 1 -> {
                OrderMessageV1 v1 = (OrderMessageV1) versioned.payload();
                yield new OrderMessageV2(
                    v1.orderId(),
                    BigDecimal.valueOf(v1.amount()),
                    "USD"
                );
            }
            case 2 -> (OrderMessageV2) versioned.payload();
            default -> throw new IllegalStateException(
                "Unknown message version: " + versioned.version()
            );
        };
    }
}

// In your StatefulHandler
public class OrderHandler implements StatefulHandler<OrderState, Object> {
    
    @Override
    public OrderState receive(Object message, OrderState state, ActorContext context) {
        if (message instanceof VersionedMessage<?> versioned) {
            // Migrate to latest version
            OrderMessageV2 current = OrderMessageAdapter.migrate(versioned);
            return processOrder(current, state);
        }
        // Handle other message types
        return state;
    }
    
    private OrderState processOrder(OrderMessageV2 msg, OrderState state) {
        // Process with latest version
        return state.addOrder(msg);
    }
}
```

### Pros
- Explicit version tracking
- Clear migration paths
- Can support multiple versions simultaneously
- Easy to test migrations

### Cons
- More boilerplate code
- Wrapper overhead
- Need to maintain old message classes

### When to Use
- Moderate complexity systems
- Frequent schema evolution
- Need to support multiple versions in flight
- Clear migration requirements

## Strategy 3: Versioned Persistence Stores (Recommended)

### Overview
Separate persistence stores by schema version, with explicit migration between versions.

### Implementation

```java
// Custom persistence provider with version support
public class VersionedPersistenceProvider implements PersistenceProvider {
    
    private final int currentVersion;
    private final Map<Integer, PersistenceProvider> versionedProviders;
    private final MessageMigrator migrator;
    
    public VersionedPersistenceProvider(int currentVersion) {
        this.currentVersion = currentVersion;
        this.versionedProviders = new ConcurrentHashMap<>();
        this.migrator = new MessageMigrator();
        
        // Initialize providers for each version
        for (int v = 1; v <= currentVersion; v++) {
            versionedProviders.put(v, createProviderForVersion(v));
        }
    }
    
    private PersistenceProvider createProviderForVersion(int version) {
        // Each version gets its own directory/namespace
        String basePath = "persistence/v" + version;
        return new FileSystemPersistenceProvider(basePath);
    }
    
    @Override
    public <M> BatchedMessageJournal<M> createBatchedMessageJournal(String actorId) {
        // Use current version for new journals
        return new VersionedMessageJournal<>(
            actorId,
            currentVersion,
            versionedProviders.get(currentVersion).createBatchedMessageJournal(actorId),
            migrator
        );
    }
    
    @Override
    public <S> SnapshotStore<S> createSnapshotStore(String actorId) {
        return new VersionedSnapshotStore<>(
            actorId,
            currentVersion,
            versionedProviders,
            migrator
        );
    }
    
    // Other interface methods...
    
    @Override
    public String getProviderName() {
        return "VersionedPersistence-v" + currentVersion;
    }
    
    @Override
    public boolean isHealthy() {
        return versionedProviders.values().stream()
            .allMatch(PersistenceProvider::isHealthy);
    }
}

// Versioned journal that handles migration on read
public class VersionedMessageJournal<M> implements BatchedMessageJournal<M> {
    
    private final String actorId;
    private final int currentVersion;
    private final BatchedMessageJournal<M> currentJournal;
    private final MessageMigrator migrator;
    
    public VersionedMessageJournal(
            String actorId,
            int currentVersion,
            BatchedMessageJournal<M> currentJournal,
            MessageMigrator migrator) {
        this.actorId = actorId;
        this.currentVersion = currentVersion;
        this.currentJournal = currentJournal;
        this.migrator = migrator;
    }
    
    @Override
    public void append(JournalEntry<M> entry) {
        // Wrap with version metadata
        VersionedJournalEntry<M> versioned = new VersionedJournalEntry<>(
            currentVersion,
            entry
        );
        currentJournal.append((JournalEntry<M>) versioned);
    }
    
    @Override
    public List<JournalEntry<M>> readAll() {
        List<JournalEntry<M>> entries = currentJournal.readAll();
        return entries.stream()
            .map(this::migrateIfNeeded)
            .toList();
    }
    
    private JournalEntry<M> migrateIfNeeded(JournalEntry<M> entry) {
        if (entry instanceof VersionedJournalEntry<M> versioned) {
            if (versioned.getVersion() < currentVersion) {
                // Migrate message to current version
                M migratedMessage = migrator.migrate(
                    versioned.getMessage(),
                    versioned.getVersion(),
                    currentVersion
                );
                return new JournalEntry<>(
                    entry.getSequenceNumber(),
                    entry.getActorId(),
                    migratedMessage,
                    entry.getTimestamp()
                );
            }
        }
        return entry;
    }
    
    // Other interface methods...
}

// Versioned journal entry
public class VersionedJournalEntry<M> extends JournalEntry<M> {
    private static final long serialVersionUID = 1L;
    
    private final int version;
    
    public VersionedJournalEntry(int version, JournalEntry<M> entry) {
        super(entry.getSequenceNumber(), entry.getActorId(), 
              entry.getMessage(), entry.getTimestamp());
        this.version = version;
    }
    
    public int getVersion() {
        return version;
    }
}

// Message migrator registry
public class MessageMigrator {
    
    private final Map<MigrationKey, Function<Object, Object>> migrations;
    
    public MessageMigrator() {
        this.migrations = new ConcurrentHashMap<>();
        registerMigrations();
    }
    
    private void registerMigrations() {
        // Register migration from v1 to v2
        register(OrderMessageV1.class, 1, 2, msg -> {
            OrderMessageV1 v1 = (OrderMessageV1) msg;
            return new OrderMessageV2(
                v1.orderId(),
                BigDecimal.valueOf(v1.amount()),
                "USD"
            );
        });
        
        // Register migration from v2 to v3 (if needed)
        register(OrderMessageV2.class, 2, 3, msg -> {
            // Migration logic
            return msg;
        });
    }
    
    public <M> void register(
            Class<?> messageClass,
            int fromVersion,
            int toVersion,
            Function<Object, Object> migrationFn) {
        migrations.put(
            new MigrationKey(messageClass.getName(), fromVersion, toVersion),
            migrationFn
        );
    }
    
    @SuppressWarnings("unchecked")
    public <M> M migrate(M message, int fromVersion, int toVersion) {
        if (fromVersion == toVersion) {
            return message;
        }
        
        Object current = message;
        for (int v = fromVersion; v < toVersion; v++) {
            MigrationKey key = new MigrationKey(
                current.getClass().getName(),
                v,
                v + 1
            );
            
            Function<Object, Object> migration = migrations.get(key);
            if (migration == null) {
                throw new IllegalStateException(
                    "No migration path from version " + v + " to " + (v + 1) +
                    " for message type: " + current.getClass().getName()
                );
            }
            
            current = migration.apply(current);
        }
        
        return (M) current;
    }
    
    private record MigrationKey(String messageType, int fromVersion, int toVersion) {}
}
```

### Usage

```java
// Configure the actor system with versioned persistence
VersionedPersistenceProvider provider = new VersionedPersistenceProvider(2); // Current version is 2
ActorSystemPersistenceHelper.setPersistenceProvider(system, provider);

// Create stateful actors - they automatically use versioned persistence
Pid orderActor = system.statefulActorOf(OrderHandler.class, new OrderState())
    .withId("order-processor")
    .spawn();

// When upgrading to version 3
VersionedPersistenceProvider providerV3 = new VersionedPersistenceProvider(3);
ActorSystemPersistenceHelper.setPersistenceProvider(system, providerV3);
// Old messages from v1 and v2 will be automatically migrated on read
```

### Pros
- Clean separation of versions
- Can maintain old data indefinitely
- Easy to test each version independently
- Can run migration scripts offline
- Supports gradual migration
- No runtime overhead for current version

### Cons
- More complex setup
- Requires migration registry
- Storage overhead (multiple copies during migration)
- Need to maintain migration paths

### When to Use
- Production systems with long-lived data
- Complex schema evolution requirements
- Need for data auditing/compliance
- Multiple concurrent versions in production

## Strategy 4: Schema Registry with Protocol Buffers/Avro

### Overview
Use a schema registry (like Confluent Schema Registry) with self-describing formats.

### Implementation

```java
// Define messages using Protocol Buffers
syntax = "proto3";

message OrderMessageV1 {
  string order_id = 1;
  double amount = 2;
}

message OrderMessageV2 {
  string order_id = 1;
  double amount = 2;
  string currency = 3;
}

// Schema-aware persistence provider
public class SchemaRegistryPersistenceProvider implements PersistenceProvider {
    
    private final SchemaRegistry registry;
    private final ProtobufSerializer serializer;
    
    public SchemaRegistryPersistenceProvider(String registryUrl) {
        this.registry = new SchemaRegistry(registryUrl);
        this.serializer = new ProtobufSerializer(registry);
    }
    
    @Override
    public <M> BatchedMessageJournal<M> createBatchedMessageJournal(String actorId) {
        return new SchemaAwareMessageJournal<>(actorId, registry, serializer);
    }
    
    // Other methods...
}

// Schema-aware journal
public class SchemaAwareMessageJournal<M> implements BatchedMessageJournal<M> {
    
    private final SchemaRegistry registry;
    private final ProtobufSerializer serializer;
    
    @Override
    public void append(JournalEntry<M> entry) {
        // Serialize with schema ID
        int schemaId = registry.getSchemaId(entry.getMessage().getClass());
        byte[] data = serializer.serialize(entry.getMessage(), schemaId);
        // Store with schema ID
        persistWithSchema(data, schemaId);
    }
    
    @Override
    public List<JournalEntry<M>> readAll() {
        return readAllWithSchema().stream()
            .map(this::deserializeWithMigration)
            .toList();
    }
    
    private JournalEntry<M> deserializeWithMigration(SchemaEntry entry) {
        // Get schema for this message
        Schema schema = registry.getSchema(entry.schemaId);
        
        // Deserialize using appropriate schema
        M message = serializer.deserialize(entry.data, schema);
        
        // Schema registry handles evolution automatically
        return new JournalEntry<>(
            entry.sequenceNumber,
            entry.actorId,
            message,
            entry.timestamp
        );
    }
}
```

### Pros
- Industry-standard approach
- Automatic schema evolution
- Cross-language support
- Efficient binary format
- Built-in validation
- Centralized schema management

### Cons
- External dependency (schema registry)
- More complex infrastructure
- Learning curve
- Operational overhead

### When to Use
- Microservices architecture
- Multi-language systems
- High-performance requirements
- Need for schema governance
- Large-scale distributed systems

## Strategy 5: Hybrid Approach (Pragmatic)

### Overview
Combine multiple strategies based on message criticality and change frequency.

### Implementation

```java
public class HybridPersistenceProvider implements PersistenceProvider {
    
    private final Map<String, PersistenceStrategy> strategyMap;
    
    public HybridPersistenceProvider() {
        this.strategyMap = new HashMap<>();
        
        // Critical messages: Use versioned stores
        strategyMap.put("OrderMessage", PersistenceStrategy.VERSIONED);
        strategyMap.put("PaymentMessage", PersistenceStrategy.VERSIONED);
        
        // Stable messages: Use simple serialization
        strategyMap.put("LogMessage", PersistenceStrategy.SIMPLE);
        
        // High-performance messages: Use schema registry
        strategyMap.put("MetricsMessage", PersistenceStrategy.SCHEMA_REGISTRY);
    }
    
    @Override
    public <M> BatchedMessageJournal<M> createBatchedMessageJournal(String actorId) {
        // Determine strategy based on actor ID or message type
        PersistenceStrategy strategy = determineStrategy(actorId);
        
        return switch (strategy) {
            case VERSIONED -> new VersionedMessageJournal<>(actorId, 1, null, null);
            case SCHEMA_REGISTRY -> new SchemaAwareMessageJournal<>(actorId, null, null);
            case SIMPLE -> new SimpleMessageJournal<>(actorId);
        };
    }
    
    private PersistenceStrategy determineStrategy(String actorId) {
        // Logic to determine strategy
        return PersistenceStrategy.VERSIONED;
    }
    
    enum PersistenceStrategy {
        SIMPLE, VERSIONED, SCHEMA_REGISTRY
    }
}
```

### When to Use
- Mixed requirements across different actors
- Gradual migration from simple to complex
- Cost-conscious deployments

## Migration Workflow

### 1. Planning Phase
```
1. Identify all message types that need to change
2. Document the migration path (v1 -> v2 -> v3)
3. Write migration functions
4. Create test data with old versions
```

### 2. Implementation Phase
```
1. Implement new message version (e.g., OrderMessageV2)
2. Keep old version (OrderMessageV1) for compatibility
3. Implement migration function
4. Add tests for migration
```

### 3. Deployment Phase
```
1. Deploy new code with both versions supported
2. New messages use new version
3. Old messages are migrated on read
4. Monitor for migration errors
```

### 4. Cleanup Phase (Optional)
```
1. Run offline migration to convert all old messages
2. Remove old message classes
3. Simplify code
```

## Best Practices

### 1. Always Use serialVersionUID
```java
public class MyMessage implements Serializable {
    private static final long serialVersionUID = 1L; // Always include
}
```

### 2. Make Compatible Changes When Possible
- Add fields with default values
- Don't remove fields (deprecate instead)
- Don't change field types
- Don't change field names

### 3. Test Migrations Thoroughly
```java
@Test
void testMessageMigration() {
    // Create old version message
    OrderMessageV1 v1 = new OrderMessageV1("order-123", 99.99);
    
    // Serialize and persist
    byte[] serialized = serialize(v1);
    
    // Deserialize with new code
    OrderMessageV2 v2 = migrator.migrate(deserialize(serialized), 1, 2);
    
    // Verify migration
    assertEquals("order-123", v2.orderId());
    assertEquals(new BigDecimal("99.99"), v2.amount());
    assertEquals("USD", v2.currency());
}
```

### 4. Version Your Persistence Format Too
```java
public class PersistenceFormatVersion {
    public static final int CURRENT_VERSION = 2;
    
    // Version 1: Basic Java serialization
    // Version 2: Added compression
    // Version 3: Added encryption
}
```

### 5. Monitor Migration Performance
```java
public class MigrationMetrics {
    private final AtomicLong migrationsPerformed = new AtomicLong();
    private final AtomicLong migrationErrors = new AtomicLong();
    private final AtomicLong migrationTimeMs = new AtomicLong();
    
    public void recordMigration(long durationMs, boolean success) {
        migrationsPerformed.incrementAndGet();
        migrationTimeMs.addAndGet(durationMs);
        if (!success) {
            migrationErrors.incrementAndGet();
        }
    }
}
```

### 6. Provide Rollback Capability
```java
// Support bidirectional migration
public interface BidirectionalMigrator {
    <M> M migrateForward(M message, int fromVersion, int toVersion);
    <M> M migrateBackward(M message, int fromVersion, int toVersion);
}
```

## Recommended Approach for Cajun

For most Cajun applications, I recommend **Strategy 3: Versioned Persistence Stores** because:

1. **Clean separation**: Each version has its own storage
2. **Explicit migrations**: Clear migration paths between versions
3. **No runtime overhead**: Current version has no wrapper overhead
4. **Testable**: Easy to test each version independently
5. **Flexible**: Can support multiple versions simultaneously
6. **Auditable**: Old data remains accessible

Start with Strategy 2 (Explicit Versioning) for prototyping, then migrate to Strategy 3 for production.

## Example: Complete Implementation

See the following example for a complete implementation:

```java
// 1. Define your messages with versions
public record OrderMessageV1(String orderId, double amount) implements Serializable {
    private static final long serialVersionUID = 1L;
}

public record OrderMessageV2(String orderId, BigDecimal amount, String currency) 
    implements Serializable {
    private static final long serialVersionUID = 1L;
}

// 2. Set up versioned persistence
VersionedPersistenceProvider provider = new VersionedPersistenceProvider(2);
ActorSystemPersistenceHelper.setPersistenceProvider(system, provider);

// 3. Register migrations
provider.getMigrator().register(OrderMessageV1.class, 1, 2, msg -> {
    OrderMessageV1 v1 = (OrderMessageV1) msg;
    return new OrderMessageV2(
        v1.orderId(),
        BigDecimal.valueOf(v1.amount()),
        "USD"
    );
});

// 4. Create your stateful actor
Pid orderActor = system.statefulActorOf(OrderHandler.class, new OrderState())
    .withId("order-processor")
    .spawn();

// 5. Messages are automatically migrated on recovery
```

## Conclusion

Message versioning is critical for production stateful actor systems. Choose a strategy based on:

- **System complexity**: Simple systems → Strategy 1, Complex systems → Strategy 3 or 4
- **Change frequency**: Rare changes → Strategy 1, Frequent changes → Strategy 3
- **Team size**: Small teams → Strategy 2, Large teams → Strategy 4
- **Infrastructure**: Minimal → Strategy 1-3, Microservices → Strategy 4

Always test your migration strategy thoroughly before deploying to production.
