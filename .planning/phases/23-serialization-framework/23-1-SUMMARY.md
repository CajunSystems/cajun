# Phase 23 Plan 1 Summary — Serialization Framework

## Status: Complete

## What Was Done
- Defined `SerializationProvider` interface + `SerializationException` in `cajun-core`
- Implemented `JavaSerializationProvider` (backward-compat fallback) in `cajun-core`
- Implemented `KryoSerializationProvider` (primary, ThreadLocal + Objenesis, no-registration) in `lib`
- Implemented `JsonSerializationProvider` (Jackson with full type info) in `lib`
- Wired `ReliableMessagingSystem` (lib + cajun-core copies) — length-prefixed byte[] framing (4-byte int + payload)
- Wired `FileMessageJournal` + `FileSnapshotStore` (cajun-persistence + lib/runtime copies) — raw byte files via `Files.write`/`Files.readAllBytes`
- Wired `BatchedFileMessageJournal` (cajun-persistence) — provider inherited from `FileMessageJournal`
- Wired `LmdbMessageJournal` + `LmdbSnapshotStore` — removed `T extends Serializable` constraint
- Updated `LmdbBatchedMessageJournal` — removed `M extends Serializable` constraint
- Added Kryo 5.6.2 + Jackson 2.17.2 to `lib/build.gradle` via `gradle/libs.versions.toml`

## Key Decisions Made
- `JavaSerializationProvider` is the default for persistence (backward compat with existing journal files on disk)
- `KryoSerializationProvider` is the default for inter-node messaging (`lib` copy of `ReliableMessagingSystem`)
- `JavaSerializationProvider` is the default for inter-node messaging in cajun-core copy (cajun-core has no Kryo dependency)
- Thread-safety: Kryo uses ThreadLocal instances with Objenesis StdInstantiatorStrategy; Jackson ObjectMapper is shared (thread-safe once configured)
- Constructor injection — no provider registry; each component takes an optional provider arg with backward-compat no-arg constructor defaulting to Java provider
- `RemoteMessage` and `MessageAcknowledgment` no longer implement `Serializable` — package-private inner classes with no-arg constructors for deserialization frameworks

## Deviations from Plan
- Kryo required Objenesis `StdInstantiatorStrategy` to handle classes without no-arg constructors (Java records, final classes). Added `new DefaultInstantiatorStrategy(new StdInstantiatorStrategy())` to the Kryo configuration.
- `BatchedFileMessageJournal` needed explicit `getSerializationProvider()` accessor added to `FileMessageJournal` (parent class) to reference the provider from the batch flush method.
- JSON test for sealed interfaces simplified: tests use concrete record types rather than sealed interface polymorphism (Jackson sealed interface support with `activateDefaultTyping` works but round-tripping via `deserialize(bytes, Shape.class)` requires type info in JSON, which works cleanly with a concrete target type).

## Test Coverage
- `SerializationProviderTest`: 15 tests
- Non-Serializable types: confirmed working via Kryo (Point, Container records)
- Backward compat: JavaSerializationProvider confirmed working for Serializable types
- Concurrent Kryo: ThreadLocal isolation verified across 10 threads x 100 iterations = 0 errors
- Sealed interface Kryo: confirmed working (Circle, Rectangle)
- JournalEntry round-trip with Kryo: confirmed working (non-Serializable payload)
- FileMessageJournal integration: Kryo + Java backward-compat both tested
- Non-Serializable fails with Java provider: confirmed SerializationException thrown

## Commits
- 6b48f91 feat(23-1): add SerializationProvider interface with Kryo and JSON implementations
- 83a72dc feat(23-1): wire SerializationProvider into ReliableMessagingSystem
- 7d542c5 feat(23-1): wire SerializationProvider into FileMessageJournal and FileSnapshotStore
- d5f8cde feat(23-1): wire SerializationProvider into LMDB journals and snapshot store
- 825acff test(23-1): SerializationProvider round-trip tests for Kryo, JSON, and Java providers
