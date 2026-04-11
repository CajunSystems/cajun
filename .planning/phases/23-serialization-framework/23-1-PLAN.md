# Phase 23 — Serialization Framework

## Objective

Introduce a pluggable `SerializationProvider` interface with Kryo (primary) and Jackson JSON (secondary) implementations. Wire the provider into `ReliableMessagingSystem` (inter-node messages), `FileMessageJournal`, and `LmdbMessageJournal` (persistence). After this phase, message and state types no longer need to implement `java.io.Serializable` for cluster or persistence use.

---

## Execution Context

- Branch: `feature/roux-effect-integration`
- Java 21, `--enable-preview`; run tests: `./gradlew test`
- Module layout (relevant modules only):
  - `cajun-core/` — shared abstractions; all other modules depend on it
  - `cajun-persistence/` — `FileMessageJournal`, `LmdbMessageJournal`, `FileSnapshotStore`
  - `lib/` — monolithic module; contains `ReliableMessagingSystem`, all tests
- Build files to edit:
  - `gradle/libs.versions.toml` — add Kryo + Jackson version entries
  - `lib/build.gradle` — add Kryo + Jackson implementation dependencies
  - `cajun-core/build.gradle` — no dependency changes (only interface code, deps-free)

---

## Context — Architecture Decisions

**Module placement:**
- `SerializationProvider` interface + `JavaSerializationProvider` (backward compat) → `cajun-core/src/main/java/com/cajunsystems/serialization/`
- `KryoSerializationProvider` + `JsonSerializationProvider` → `lib/src/main/java/com/cajunsystems/serialization/`  
  (Kryo and Jackson deps added to `lib/build.gradle`; tests also live in `lib`)
- cajun-persistence's `FileMessageJournal` and `LmdbMessageJournal` use `SerializationProvider` from cajun-core — no new deps needed in cajun-persistence

**Wire-up strategy:** Constructor injection. Each journal/store gains an optional `SerializationProvider` overload. The `PersistenceFactory` defaults to `KryoSerializationProvider`. `ReliableMessagingSystem` gains a `SerializationProvider` constructor parameter.

**Serializable constraint removal:**
- `LmdbMessageJournal<T extends Serializable>` → `LmdbMessageJournal<T>` (and `LmdbSnapshotStore`, `LmdbBatchedMessageJournal`)
- `FileMessageJournal` — no type constraint today, but ObjectOutputStream usage removed
- `ReliableMessagingSystem.RemoteMessage` inner class — currently `implements Serializable`; replace with provider-serialized `byte[]`

**Library versions (confirmed for plan):**
- Kryo: `com.esotericsoftware:kryo:5.6.2` (latest stable; no-arg constructors NOT required by default with Kryo 5 objenesis support)
- Jackson: `com.fasterxml.jackson.core:jackson-databind:2.17.2`

---

## Tasks

### Task 1 — Add Dependencies

Edit `gradle/libs.versions.toml` — add versions:
```toml
kryo = "5.6.2"
jackson = "2.17.2"
```

And under `[libraries]`:
```toml
kryo = { module = "com.esotericsoftware:kryo", version.ref = "kryo" }
jackson-databind = { module = "com.fasterxml.jackson.core:jackson-databind", version.ref = "jackson" }
```

Edit `lib/build.gradle` — add under `dependencies {}`:
```groovy
// Serialization providers
implementation libs.kryo
implementation libs.jackson.databind
```

Verify with: `./gradlew :lib:dependencies --configuration runtimeClasspath 2>&1 | grep -E 'kryo|jackson'`

### Task 2 — Define SerializationProvider Interface

Create `cajun-core/src/main/java/com/cajunsystems/serialization/SerializationProvider.java`:

```java
package com.cajunsystems.serialization;

/**
 * Pluggable serialization strategy for inter-node messages and persistence journals.
 *
 * <p>Implementations must be thread-safe. A single provider instance may be shared
 * across many actors and threads simultaneously.
 */
public interface SerializationProvider {

    /**
     * Serializes an object to bytes.
     *
     * @param object the object to serialize (must not be null)
     * @return the serialized byte array
     * @throws SerializationException if serialization fails
     */
    byte[] serialize(Object object);

    /**
     * Deserializes bytes back to an object of the given type.
     *
     * @param bytes the serialized bytes
     * @param type  the expected type of the deserialized object
     * @param <T>   the type parameter
     * @return the deserialized object
     * @throws SerializationException if deserialization fails
     */
    <T> T deserialize(byte[] bytes, Class<T> type);

    /**
     * Returns the name of this provider (for logging and configuration).
     */
    String name();
}
```

Create `cajun-core/src/main/java/com/cajunsystems/serialization/SerializationException.java`:

```java
package com.cajunsystems.serialization;

public class SerializationException extends RuntimeException {
    public SerializationException(String message) { super(message); }
    public SerializationException(String message, Throwable cause) { super(message, cause); }
}
```

Create `cajun-core/src/main/java/com/cajunsystems/serialization/JavaSerializationProvider.java` — backward-compat fallback using `ObjectOutputStream`/`ObjectInputStream`. This is the same logic currently embedded in `FileMessageJournal` and `ReliableMessagingSystem`, extracted into one place:

```java
package com.cajunsystems.serialization;

import java.io.*;

/**
 * Serialization provider using Java's built-in object serialization.
 * Objects must implement {@link java.io.Serializable}.
 *
 * <p>This is the backward-compatible fallback. Prefer {@code KryoSerializationProvider}
 * for new deployments.
 */
public class JavaSerializationProvider implements SerializationProvider {

    public static final JavaSerializationProvider INSTANCE = new JavaSerializationProvider();

    @Override
    public byte[] serialize(Object object) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(object);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new SerializationException("Java serialization failed for " + object.getClass().getName(), e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T deserialize(byte[] bytes, Class<T> type) {
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return (T) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new SerializationException("Java deserialization failed for type " + type.getName(), e);
        }
    }

    @Override
    public String name() { return "java"; }
}
```

### Task 3 — Implement KryoSerializationProvider

Create `lib/src/main/java/com/cajunsystems/serialization/KryoSerializationProvider.java`.

Key requirements:
- Kryo instances are NOT thread-safe — use a `ThreadLocal<Kryo>` pool or create per-call (per-call is simpler for Phase 23, ThreadLocal pool if benchmarks show overhead)
- Use `Kryo` with `setRegistrationRequired(false)` so unregistered types work without pre-registration
- Use objenesis (included with Kryo 5) for classes without no-arg constructors
- Serialize to/from `byte[]` via `Output`/`Input`

```java
package com.cajunsystems.serialization;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

import java.io.ByteArrayOutputStream;

/**
 * High-performance serialization using Kryo.
 *
 * <p>Does not require types to implement {@link java.io.Serializable} or have
 * no-arg constructors (Kryo 5 uses Objenesis for instantiation).
 *
 * <p>Kryo instances are not thread-safe; this provider uses a per-thread instance.
 */
public class KryoSerializationProvider implements SerializationProvider {

    public static final KryoSerializationProvider INSTANCE = new KryoSerializationProvider();

    private final ThreadLocal<Kryo> kryoLocal = ThreadLocal.withInitial(() -> {
        Kryo kryo = new Kryo();
        kryo.setRegistrationRequired(false);
        kryo.setReferences(true); // handle object graph cycles
        return kryo;
    });

    @Override
    public byte[] serialize(Object object) {
        try {
            Kryo kryo = kryoLocal.get();
            ByteArrayOutputStream bos = new ByteArrayOutputStream(256);
            try (Output output = new Output(bos)) {
                kryo.writeClassAndObject(output, object);
            }
            return bos.toByteArray();
        } catch (Exception e) {
            throw new SerializationException(
                "Kryo serialization failed for " + object.getClass().getName(), e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T deserialize(byte[] bytes, Class<T> type) {
        try {
            Kryo kryo = kryoLocal.get();
            try (Input input = new Input(bytes)) {
                return (T) kryo.readClassAndObject(input);
            }
        } catch (Exception e) {
            throw new SerializationException(
                "Kryo deserialization failed for type " + type.getName(), e);
        }
    }

    @Override
    public String name() { return "kryo"; }
}
```

### Task 4 — Implement JsonSerializationProvider

Create `lib/src/main/java/com/cajunsystems/serialization/JsonSerializationProvider.java`.

Key requirements:
- Use `ObjectMapper` with `enableDefaultTyping` (full type info) so polymorphic types round-trip correctly
- Configure to handle Java records, sealed interfaces, and unknown types gracefully
- `ObjectMapper` IS thread-safe once configured — use a single shared instance

```java
package com.cajunsystems.serialization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

/**
 * Human-readable serialization using Jackson JSON.
 *
 * <p>Includes full type information so polymorphic types (sealed interfaces, etc.)
 * round-trip correctly. Best for debugging and cross-language interop; prefer Kryo
 * for performance-critical paths.
 */
public class JsonSerializationProvider implements SerializationProvider {

    public static final JsonSerializationProvider INSTANCE = new JsonSerializationProvider();

    private final ObjectMapper mapper;

    public JsonSerializationProvider() {
        this.mapper = JsonMapper.builder()
            .activateDefaultTyping(
                BasicPolymorphicTypeValidator.builder()
                    .allowIfBaseType(Object.class)
                    .build(),
                ObjectMapper.DefaultTyping.EVERYTHING
            )
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .build();
    }

    @Override
    public byte[] serialize(Object object) {
        try {
            return mapper.writeValueAsBytes(object);
        } catch (Exception e) {
            throw new SerializationException(
                "JSON serialization failed for " + object.getClass().getName(), e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T deserialize(byte[] bytes, Class<T> type) {
        try {
            return mapper.readValue(bytes, type);
        } catch (Exception e) {
            throw new SerializationException(
                "JSON deserialization failed for type " + type.getName(), e);
        }
    }

    @Override
    public String name() { return "json"; }
}
```

After writing both providers, compile to catch errors:
```bash
cd /Users/pradeep.samuel/cajun && ./gradlew :lib:compileJava 2>&1 | tail -30
```

Commit Tasks 1–4:
```bash
git add gradle/libs.versions.toml lib/build.gradle
git add cajun-core/src/main/java/com/cajunsystems/serialization/SerializationProvider.java
git add cajun-core/src/main/java/com/cajunsystems/serialization/SerializationException.java
git add cajun-core/src/main/java/com/cajunsystems/serialization/JavaSerializationProvider.java
git add lib/src/main/java/com/cajunsystems/serialization/KryoSerializationProvider.java
git add lib/src/main/java/com/cajunsystems/serialization/JsonSerializationProvider.java
git commit -m "feat(23-1): add SerializationProvider interface with Kryo and JSON implementations

Defines pluggable byte[] serialize/deserialize contract in cajun-core.
JavaSerializationProvider is the backward-compat fallback. KryoSerializationProvider
(primary) uses ThreadLocal Kryo with registration-not-required + objenesis.
JsonSerializationProvider (secondary) uses Jackson with full type info.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

### Task 5 — Wire into ReliableMessagingSystem

`ReliableMessagingSystem` currently sends `RemoteMessage<T>` via `ObjectOutputStream`. Replace the write/read path with `SerializationProvider`.

**File to edit**: `lib/src/main/java/com/cajunsystems/cluster/ReliableMessagingSystem.java`

Also check and edit (if different):
`cajun-core/src/main/java/com/cajunsystems/cluster/ReliableMessagingSystem.java`

Changes required:

1. Add a `SerializationProvider provider` field; default to `KryoSerializationProvider.INSTANCE`

2. Add constructor overloads:
```java
public ReliableMessagingSystem(String systemId, int port) {
    this(systemId, port, DeliveryGuarantee.EXACTLY_ONCE);
}
public ReliableMessagingSystem(String systemId, int port, DeliveryGuarantee guarantee) {
    this(systemId, port, guarantee, new ThreadPoolFactory());
}
public ReliableMessagingSystem(String systemId, int port, DeliveryGuarantee guarantee, ThreadPoolFactory factory) {
    this(systemId, port, guarantee, factory, KryoSerializationProvider.INSTANCE);
}
public ReliableMessagingSystem(String systemId, int port, DeliveryGuarantee guarantee,
                                ThreadPoolFactory factory, SerializationProvider provider) {
    // ... existing field assignments ...
    this.provider = provider;
}
```

3. Replace `doSendMessage` write path:
```java
// OLD:
ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
out.writeObject(remoteMessage);

// NEW:
byte[] payload = provider.serialize(remoteMessage);
DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
dos.writeInt(payload.length);
dos.write(payload);
dos.flush();
```

4. Replace `doSendMessage` read path for acknowledgment:
```java
// OLD:
ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
MessageAcknowledgment ack = (MessageAcknowledgment) in.readObject();

// NEW:
DataInputStream dis = new DataInputStream(socket.getInputStream());
int len = dis.readInt();
byte[] ackBytes = dis.readNBytes(len);
MessageAcknowledgment ack = provider.deserialize(ackBytes, MessageAcknowledgment.class);
```

5. Replace `acceptConnections` / `handleConnection` read path (incoming messages):
```java
// OLD:
ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
RemoteMessage<?> remoteMessage = (RemoteMessage<?>) in.readObject();

// NEW:
DataInputStream dis = new DataInputStream(socket.getInputStream());
int len = dis.readInt();
byte[] msgBytes = dis.readNBytes(len);
RemoteMessage<?> remoteMessage = provider.deserialize(msgBytes, RemoteMessage.class);
```

6. Same for ack send-back path.

7. `RemoteMessage` and `MessageAcknowledgment` inner/private classes: remove `implements Serializable` (they no longer need it — Kryo doesn't require it).

**Important**: both `lib/` and `cajun-core/` have copies of this class. Update BOTH if they differ. If they are identical, update both identically. Check by diffing:
```bash
diff lib/src/main/java/com/cajunsystems/cluster/ReliableMessagingSystem.java \
     cajun-core/src/main/java/com/cajunsystems/cluster/ReliableMessagingSystem.java
```

After editing, compile:
```bash
cd /Users/pradeep.samuel/cajun && ./gradlew :lib:compileJava 2>&1 | tail -30
```

Fix any compile errors before committing.

Commit:
```bash
git add lib/src/main/java/com/cajunsystems/cluster/ReliableMessagingSystem.java
# Also add cajun-core version if it was changed:
git add cajun-core/src/main/java/com/cajunsystems/cluster/ReliableMessagingSystem.java
git commit -m "feat(23-1): wire SerializationProvider into ReliableMessagingSystem

Replaces ObjectOutputStream/ObjectInputStream with provider.serialize/deserialize.
Length-prefixed framing (4-byte int + payload) used for stream-based transport.
RemoteMessage and MessageAcknowledgment no longer require Serializable.
Default provider is KryoSerializationProvider.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

### Task 6 — Wire into FileMessageJournal

**Files to edit**:
- `cajun-persistence/src/main/java/com/cajunsystems/persistence/filesystem/FileMessageJournal.java`
- `lib/src/main/java/com/cajunsystems/runtime/persistence/FileMessageJournal.java` (if different)
- `cajun-persistence/src/main/java/com/cajunsystems/persistence/filesystem/FileSnapshotStore.java`

**Changes for `FileMessageJournal`**:

1. Add `SerializationProvider provider` field; default to `KryoSerializationProvider.INSTANCE`

2. Constructor overloads:
```java
public FileMessageJournal(Path journalDir) {
    this(journalDir, KryoSerializationProvider.INSTANCE);
}
public FileMessageJournal(Path journalDir, SerializationProvider provider) {
    this.journalDir = journalDir;
    this.provider = provider;
    // ... existing dir creation ...
}
public FileMessageJournal(String journalDirPath) {
    this(Paths.get(journalDirPath));
}
public FileMessageJournal(String journalDirPath, SerializationProvider provider) {
    this(Paths.get(journalDirPath), provider);
}
```

3. Replace `append()` write:
```java
// OLD:
try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(entryFile.toFile()))) {
    oos.writeObject(entry);
}

// NEW:
byte[] bytes = provider.serialize(entry);
Files.write(entryFile, bytes);
```

4. Replace `readFrom()` read:
```java
// OLD:
try (ObjectInputStream is = new ObjectInputStream(new FileInputStream(entryFile.toFile()))) {
    JournalEntry<M> entry = (JournalEntry<M>) is.readObject();
    entries.add(entry);
}

// NEW:
byte[] bytes = Files.readAllBytes(entryFile);
JournalEntry<M> entry = provider.deserialize(bytes, JournalEntry.class);
entries.add(entry);
```

Note: `JournalEntry.class` is the raw class; Kryo will reconstruct the generic type correctly. The `@SuppressWarnings("unchecked")` may be needed.

Apply the same pattern to `FileSnapshotStore` (replace ObjectOutputStream/ObjectInputStream with provider calls).

Also check `BatchedFileMessageJournal` in `cajun-persistence/` — it likely delegates to `FileMessageJournal` and may need to pass the provider through.

After editing, compile:
```bash
cd /Users/pradeep.samuel/cajun && ./gradlew :cajun-persistence:compileJava :lib:compileJava 2>&1 | tail -30
```

Commit:
```bash
git add cajun-persistence/src/main/java/com/cajunsystems/persistence/filesystem/FileMessageJournal.java
git add cajun-persistence/src/main/java/com/cajunsystems/persistence/filesystem/FileSnapshotStore.java
git add cajun-persistence/src/main/java/com/cajunsystems/persistence/filesystem/BatchedFileMessageJournal.java
# Also add lib runtime copies if changed:
git add lib/src/main/java/com/cajunsystems/runtime/persistence/FileMessageJournal.java
git add lib/src/main/java/com/cajunsystems/runtime/persistence/FileSnapshotStore.java
git commit -m "feat(23-1): wire SerializationProvider into FileMessageJournal and FileSnapshotStore

Replaces ObjectOutputStream/ObjectInputStream with provider.serialize/deserialize.
Journal entries written as raw byte files; snapshot entries similarly.
Default provider is KryoSerializationProvider.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

### Task 7 — Wire into LmdbMessageJournal

**Files to edit**:
- `cajun-persistence/src/main/java/com/cajunsystems/persistence/lmdb/LmdbMessageJournal.java`
- `cajun-persistence/src/main/java/com/cajunsystems/persistence/lmdb/LmdbSnapshotStore.java`
- `cajun-persistence/src/main/java/com/cajunsystems/persistence/lmdb/LmdbBatchedMessageJournal.java`
- `cajun-persistence/src/main/java/com/cajunsystems/persistence/lmdb/SimpleBatchedMessageJournal.java`

**Key change**: `LmdbMessageJournal<T extends Serializable>` → `LmdbMessageJournal<T>` (remove Serializable constraint).

Add `SerializationProvider provider` field. Replace `serializeEntry()` / `deserializeEntry()` private methods to delegate to the provider instead of ObjectOutputStream.

Constructor changes (same pattern as FileMessageJournal).

For `serializeEntry()`:
```java
// OLD (inside serializeEntry):
ByteArrayOutputStream bos = new ByteArrayOutputStream();
ObjectOutputStream oos = new ObjectOutputStream(bos);
oos.writeObject(entry);
return bos.toByteArray();

// NEW:
return provider.serialize(entry);
```

For `deserializeEntry()`:
```java
// OLD (inside deserializeEntry):
ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes));
return (JournalEntry<T>) ois.readObject();

// NEW:
return provider.deserialize(bytes, JournalEntry.class);
```

After editing, compile:
```bash
cd /Users/pradeep.samuel/cajun && ./gradlew :cajun-persistence:compileJava 2>&1 | tail -30
```

Commit:
```bash
git add cajun-persistence/src/main/java/com/cajunsystems/persistence/lmdb/LmdbMessageJournal.java
git add cajun-persistence/src/main/java/com/cajunsystems/persistence/lmdb/LmdbSnapshotStore.java
git add cajun-persistence/src/main/java/com/cajunsystems/persistence/lmdb/LmdbBatchedMessageJournal.java
git add cajun-persistence/src/main/java/com/cajunsystems/persistence/lmdb/SimpleBatchedMessageJournal.java
git commit -m "feat(23-1): wire SerializationProvider into LMDB journals and snapshot store

Removes T extends Serializable constraint from LmdbMessageJournal, LmdbSnapshotStore,
and LmdbBatchedMessageJournal. serializeEntry/deserializeEntry now delegate to
SerializationProvider. Default provider is KryoSerializationProvider.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

### Task 8 — Tests

Write `lib/src/test/java/com/cajunsystems/serialization/SerializationProviderTest.java`.

Tests required:

**Round-trip tests (run for both Kryo and JSON providers):**
- Serialize and deserialize a simple record (no `implements Serializable`) — proves constraint removed
- Serialize and deserialize a sealed interface with pattern-matched subtype
- Serialize and deserialize a `JournalEntry<CounterMessage>` (wrapped generic)
- Serialize `null` — should throw `SerializationException` or handle gracefully (document behavior)
- Very large payload (1MB) — verify no truncation

**Kryo-specific:**
- Non-Serializable class (e.g., a plain POJO with no Serializable) round-trips via Kryo
- Concurrent serialization from 10 threads — verify ThreadLocal isolation (no corruption)

**JSON-specific:**
- Deserialize to correct subtype via type info in JSON
- Produce human-readable output (assert `!bytes.isAllZeros()` and contains `{`)

**Integration — FileMessageJournal with Kryo provider:**
- Write 3 messages to temp directory using `KryoSerializationProvider`, read back, assert equal
- Write with `KryoSerializationProvider`, try to read with `JavaSerializationProvider` — assert `SerializationException` (provider mismatch is an expected failure; documents the contract)

**Integration — ReliableMessagingSystem protocol:**
Use `InMemoryMessagingSystem` from cluster tests (not TCP) — the in-memory system bypasses serialization. Instead, test the `doSendMessage` path via a small TCP loopback integration test if feasible, OR test `KryoSerializationProvider` directly on `RemoteMessage` objects to prove the wire protocol is coherent.

After writing tests, run:
```bash
cd /Users/pradeep.samuel/cajun && ./gradlew test --tests "*SerializationProvider*" 2>&1 | tail -40
```

Fix any failures. Then run the full suite:
```bash
cd /Users/pradeep.samuel/cajun && ./gradlew test 2>&1 | tail -40
```

Pre-existing failures (from STATE.md): `ClusterModeTest.testRemoteActorCommunication` is intermittent and pre-existing — do not count as a regression.

Commit:
```bash
git add lib/src/test/java/com/cajunsystems/serialization/SerializationProviderTest.java
git commit -m "test(23-1): round-trip tests for Kryo, JSON, and Java serialization providers

Covers non-Serializable types, sealed interfaces, JournalEntry generics,
concurrent Kryo usage, FileMessageJournal integration, and wire protocol.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Verification

1. **Build passes**: `./gradlew build -x test` — no compile errors
2. **Serialization tests pass**: `./gradlew test --tests "*SerializationProvider*"` — all green
3. **Full suite**: `./gradlew test` — no new failures (pre-existing `testRemoteActorCommunication` flakiness is known)
4. **Non-Serializable type works**: At least one test proves a plain POJO (no `implements Serializable`) can be journaled and recovered

---

## Success Criteria

- `SerializationProvider` interface + `JavaSerializationProvider` in `cajun-core`
- `KryoSerializationProvider` + `JsonSerializationProvider` in `lib`
- `ReliableMessagingSystem` wired — `RemoteMessage` no longer requires `Serializable`
- `FileMessageJournal` + `FileSnapshotStore` wired — no `ObjectOutputStream` usage remains
- `LmdbMessageJournal` + `LmdbSnapshotStore` wired — `T extends Serializable` constraint removed
- `SerializationProviderTest` passing with ≥ 10 test methods
- `./gradlew test` green (modulo pre-existing flakiness)

---

## Output

- **New files** (cajun-core):
  - `cajun-core/src/main/java/com/cajunsystems/serialization/SerializationProvider.java`
  - `cajun-core/src/main/java/com/cajunsystems/serialization/SerializationException.java`
  - `cajun-core/src/main/java/com/cajunsystems/serialization/JavaSerializationProvider.java`
- **New files** (lib):
  - `lib/src/main/java/com/cajunsystems/serialization/KryoSerializationProvider.java`
  - `lib/src/main/java/com/cajunsystems/serialization/JsonSerializationProvider.java`
  - `lib/src/test/java/com/cajunsystems/serialization/SerializationProviderTest.java`
- **Modified files**:
  - `gradle/libs.versions.toml` — Kryo + Jackson versions
  - `lib/build.gradle` — Kryo + Jackson deps
  - `lib/src/main/java/com/cajunsystems/cluster/ReliableMessagingSystem.java`
  - `cajun-core/src/main/java/com/cajunsystems/cluster/ReliableMessagingSystem.java` (if applicable)
  - `cajun-persistence/src/main/java/com/cajunsystems/persistence/filesystem/FileMessageJournal.java`
  - `cajun-persistence/src/main/java/com/cajunsystems/persistence/filesystem/FileSnapshotStore.java`
  - `cajun-persistence/src/main/java/com/cajunsystems/persistence/lmdb/LmdbMessageJournal.java`
  - `cajun-persistence/src/main/java/com/cajunsystems/persistence/lmdb/LmdbSnapshotStore.java`
- **Planning artifacts**:
  - `.planning/phases/23-serialization-framework/23-1-SUMMARY.md`
  - `.planning/STATE.md` — Phase 23 ✅ Complete
  - `.planning/ROADMAP.md` — Phase 23 struck through
