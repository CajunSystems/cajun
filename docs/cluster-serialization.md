# Cajun Cluster Serialization Guide

This guide covers the two built-in `SerializationProvider` implementations, how to choose
between them, security considerations, and migration between providers.

---

## Overview

All data that leaves the JVM in a Cajun cluster is serialized:

- **Cross-node messages** — via `MessagingSystem.sendMessage()`.
- **Journal entries** — actor messages written to Redis.
- **Snapshots** — actor state written to Redis.

Both uses share the same `SerializationProvider` configured on `RedisPersistenceProvider`.

---

## Available Providers

### KryoSerializationProvider (recommended)

```java
import com.cajunsystems.serialization.KryoSerializationProvider;

RedisPersistenceProvider redis = new RedisPersistenceProvider(
    "redis://localhost:6379", "myapp",
    KryoSerializationProvider.INSTANCE);
```

**Characteristics**:

| Property | Value |
|----------|-------|
| Format | Binary (compact) |
| Schema required | No |
| `Serializable` required | No |
| Throughput | Highest |
| Human-readable | No |
| Security model | Trusts all classes |

**When to use**:
- Production clusters with high throughput requirements.
- Internal services where all nodes run the same codebase.
- Message types that do not implement `Serializable`.
- Any workload where serialization overhead must be minimized.

**Limitations**:
- Binary format is not inspectable without Kryo tooling.
- Class changes require schema evolution care (see below).

### JsonSerializationProvider

```java
import com.cajunsystems.serialization.JsonSerializationProvider;

// Default INSTANCE: trusts com.cajunsystems.*, java.*, javax.*
RedisPersistenceProvider redis = new RedisPersistenceProvider(
    "redis://localhost:6379", "myapp",
    JsonSerializationProvider.INSTANCE);

// Custom trusted package
JsonSerializationProvider custom = new JsonSerializationProvider("com.example.");
RedisPersistenceProvider redis2 = new RedisPersistenceProvider(
    "redis://localhost:6379", "myapp2", custom);
```

**Characteristics**:

| Property | Value |
|----------|-------|
| Format | JSON (text) |
| Schema required | No |
| `Serializable` required | No |
| Throughput | Lower than Kryo |
| Human-readable | Yes |
| Security model | Allowlist by package prefix |

**When to use**:
- Debugging: journal entries are readable in `redis-cli`.
- Cross-language compatibility: other services consume the same Redis keys.
- Auditing: human-readable event log.
- Development and staging environments.

**Security model**: The `JsonSerializationProvider` restricts polymorphic deserialization to
package prefixes added at construction time. This prevents deserialization gadget attacks
(e.g., Jackson CVE exploits). Always add your application's package prefix:

```java
// Trust your own classes alongside the defaults
JsonSerializationProvider provider = new JsonSerializationProvider("com.mycompany.");
```

---

## Mixing Providers

**Important**: all nodes in a cluster must use the **same** provider and the **same**
configuration. Mixing providers between nodes causes deserialization failures when an actor
journal written by one node is replayed on another.

If you need to change providers on an existing cluster, follow the migration procedure below.

---

## Schema Evolution with Kryo

Kryo serializes field values by position (without field names). Adding, removing, or reordering
fields in a message class can break deserialization of existing journal entries.

### Safe operations (Kryo)

- Add fields to the **end** of a class (Kryo reads extra bytes as null/zero).
- Remove fields from the **end** of a class.
- Rename a field (Kryo uses position, not name).

### Unsafe operations (Kryo)

- Inserting or removing fields in the **middle** of a class — shifts all subsequent field offsets.
- Changing a field's type — type mismatch on read.

### Migration Pattern

If you must make breaking schema changes:

1. Drain all actors to Redis journal before the change.
2. Write a one-time migration tool to read old entries with the old provider and rewrite
   them with the new provider / new class version.
3. Restart all nodes with the new schema.

```java
// Sketch: migrate journal entries from OldCounterMsg to NewCounterMsg
KryoSerializationProvider oldKryo = KryoSerializationProvider.INSTANCE;
KryoSerializationProvider newKryo = KryoSerializationProvider.INSTANCE;

RedisPersistenceProvider oldProvider = new RedisPersistenceProvider(uri, "myapp-old", oldKryo);
RedisPersistenceProvider newProvider = new RedisPersistenceProvider(uri, "myapp-new", newKryo);

MessageJournal<OldCounterMsg> oldJournal = oldProvider.createMessageJournal("counter-1");
MessageJournal<NewCounterMsg> newJournal = newProvider.createMessageJournal("counter-1");

List<JournalEntry<OldCounterMsg>> entries = oldJournal.readAll().get();
for (JournalEntry<OldCounterMsg> e : entries) {
    NewCounterMsg converted = migrate(e.message());
    newJournal.append(new JournalEntry<>(e.sequenceNumber(), converted)).get();
}
```

---

## Migrating Between Providers

### Kryo to JSON

Use this procedure to migrate from Kryo journals to JSON for auditing or debugging purposes.

**Step 1**: Stop all writes to the actor (drain the node or shut down the actor).

**Step 2**: Read journal entries with Kryo, rewrite with JSON:

```java
RedisPersistenceProvider kryoProvider = new RedisPersistenceProvider(
    uri, "myapp", KryoSerializationProvider.INSTANCE);
RedisPersistenceProvider jsonProvider = new RedisPersistenceProvider(
    uri, "myapp-json", new JsonSerializationProvider("com.example."));

String actorId = "counter-1";

MessageJournal<CounterMsg> kryoJournal = kryoProvider.createMessageJournal(actorId);
MessageJournal<CounterMsg> jsonJournal  = jsonProvider.createMessageJournal(actorId);

List<JournalEntry<CounterMsg>> entries = kryoJournal.readAll().get();
for (JournalEntry<CounterMsg> e : entries) {
    jsonJournal.append(e).get();
}

// Migrate snapshot
SnapshotStore<CounterState> kryoSnap = kryoProvider.createSnapshotStore(actorId);
SnapshotStore<CounterState> jsonSnap  = jsonProvider.createSnapshotStore(actorId);

kryoSnap.getLatestSnapshot(actorId).get().ifPresent(snap ->
    jsonSnap.saveSnapshot(snap).join()
);
```

**Step 3**: Update all nodes to use `jsonProvider` with the new prefix (`myapp-json`).

**Step 4**: Delete old Kryo keys from Redis once migration is verified.

### JSON to Kryo

Reverse the process above: use `jsonProvider` as source and `kryoProvider` as destination.

---

## Verification

After migrating, verify journal integrity:

```java
RedisPersistenceProvider newProvider = new RedisPersistenceProvider(
    uri, "myapp-json", new JsonSerializationProvider("com.example."));

MessageJournal<CounterMsg> journal = newProvider.createMessageJournal("counter-1");

// Replaying should not throw
List<JournalEntry<CounterMsg>> entries = journal.readAll().get();
System.out.println("Verified " + entries.size() + " entries");

SnapshotStore<CounterState> snap = newProvider.createSnapshotStore("counter-1");
Optional<SnapshotEntry<CounterState>> latest = snap.getLatestSnapshot("counter-1").get();
System.out.println("Snapshot present: " + latest.isPresent());
```

---

## Quick Reference

| Decision | Recommendation |
|----------|----------------|
| Production cluster | `KryoSerializationProvider.INSTANCE` |
| Debug / audit trail | `JsonSerializationProvider.INSTANCE` |
| Cross-language consumers | `JsonSerializationProvider` with your package prefix |
| Schema breaking change | Migrate via dual-prefix rewrite, then cutover |
| Mixed provider (different nodes) | Not supported — all nodes must use the same provider |
