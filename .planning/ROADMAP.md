# Roadmap: Cajun × Roux Unified Effect System

Replace Cajun's bespoke `functional/` effect system with Roux (`com.cajunsystems:roux`).
Adds an actor-backed `EffectRuntime` so Roux effects in Cajun dispatch through actor
execution rather than virtual threads.

**Depth**: Standard | **Mode**: Interactive
**Branch**: `feature/roux-effect-integration`

---

## ~~Phase 1: Dependency Setup & Build Verification~~ ✅
**Goal**: Roux is on the classpath and the build compiles cleanly with both systems present.

No code deleted yet — establish the foundation before the hard cut.

Plans:
- 1.1 Add `com.cajunsystems:roux:0.1.0` to `lib/build.gradle`
- 1.2 Verify full build compiles with Roux present alongside old effect system
- 1.3 Smoke test — confirm Roux's `DefaultEffectRuntime` runs correctly in Cajun's test environment

---

## ~~Phase 2: Implement ActorEffectRuntime~~ ✅
**Goal**: A working `ActorEffectRuntime` that dispatches Roux effects through the actor system's executor, not a virtual-thread pool.

This is the core deliverable — the thing that makes Cajun's integration unique.

Plans:
- 2.1 Analyse Roux's `EffectRuntime` interface and `DefaultEffectRuntime` implementation
- 2.2 Implement `ActorEffectRuntime` — delegates `ExecutorService` to `ActorSystem`'s executor
- 2.3 Test basic effect execution, error handling, and fiber fork/join through `ActorEffectRuntime`
- 2.4 Test concurrency — confirm effects run on actor threads, not virtual threads

---

## ~~Phase 3: Remove Old Effect Machinery~~ ✅
**Goal**: Every line of the old Cajun effect system is gone. Build still compiles.

Hard cut: `Effect<State,Error,Result>`, `Trampoline`, `EffectGenerator`, `GeneratorContext`,
`EffectMatchBuilder`, `EffectConversions`, `ThrowableEffect` — all deleted.

Plans:
- 3.1 Delete core monad files: `Effect.java`, `EffectResult.java`, `ThrowableEffect.java`, `ThrowableEffectMatchBuilder.java`, `EffectMatchBuilder.java`, `EffectConversions.java`
- 3.2 Delete generator files: `EffectGenerator.java`, `GeneratorContext.java`, `GeneratorContextImpl.java`, `functional/internal/Trampoline.java`
- 3.3 Delete all 7 old effect tests (`EffectCheckedExceptionTest`, `EffectGeneratorTest`, `EffectInterruptionTest`, `EffectResultTest`, `NewEffectOperatorsTest`, `ThrowableEffectTest`, `TrampolineTest`)
- 3.4 Fix any compile errors in remaining code that referenced old types

---

## ~~Phase 4: Migrate Capabilities~~ ✅
**Goal**: Cajun's `LogCapability` and `ConsoleLogHandler` work through Roux's `Capability<R>` / `CapabilityHandler` model.

Cajun's own `Capability.java` and `CapabilityHandler.java` are deleted — Roux's versions are used directly.

Plans:
- 4.1 Delete `functional/capabilities/Capability.java` and `functional/capabilities/CapabilityHandler.java` (superseded by Roux)
- 4.2 Rewrite `LogCapability.java` as sealed Roux `Capability<R>` implementations
- 4.3 Rewrite `ConsoleLogHandler.java` implementing Roux's `CapabilityHandler` interface
- 4.4 Integration test — run `LogCapability` through `ActorEffectRuntime` end-to-end

---

## ~~Phase 5: Rewrite Effect Builders~~ ✅
**Goal**: `EffectActorBuilder` and `ActorSystemEffectExtensions` expose a clean Roux-native API.

The public surface that Cajun users interact with when writing effect-based actors.

Plans:
- 5.1 Rewrite `EffectActorBuilder.java` — spawns actors that execute Roux `Effect<E, A>` pipelines via `ActorEffectRuntime`
- 5.2 Rewrite `ActorSystemEffectExtensions.java` — Roux-native extension methods on `ActorSystem`
- 5.3 Integration tests for effect-based actors using the new builder API

---

## ~~Phase 6: Tests, Examples & Final Validation~~ ✅
**Goal**: Test suite is green, examples updated, no traces of old Cajun effect types remain.

Plans:
- 6.1 Write comprehensive new tests: `ActorEffectRuntimeTest`, `CapabilityIntegrationTest`, `EffectActorBuilderTest`
- 6.2 Update example files referencing old Cajun effect types
- 6.3 Run full test suite (`./gradlew test`) — fix any remaining failures
- 6.4 Final audit — grep codebase for old Cajun effect imports; confirm clean slate

---

## Summary — Milestone 1

| Phase | Name | Key Output |
|-------|------|------------|
| 1 | Dependency Setup | Roux on classpath, build green |
| 2 | ActorEffectRuntime | Actor-backed runtime implemented & tested |
| 3 | Remove Old Machinery | Old `functional/` effect system deleted |
| 4 | Migrate Capabilities | LogCapability + ConsoleLogHandler on Roux |
| 5 | Rewrite Effect Builders | EffectActorBuilder + ActorSystemEffectExtensions on Roux |
| 6 | Tests & Validation | Green test suite, clean codebase |

**Files deleted**: ~16 production + 7 test files
**Files rewritten**: 4 (capabilities + builders)
**Files created**: 1 (`ActorEffectRuntime.java`) + new tests

---

---

## ~~Milestone 2: Effect Actor Examples & Documentation~~ ✅ `v0.5.0`

8 runnable examples + `docs/effect-actors/` (3 guides) — error handling, stateful composition, pipelines, fan-out, custom capabilities. → [Archive](.planning/milestones/v0.5.0-ROADMAP.md)

---

---

## ~~Milestone 3: Roux v0.2.1 Upgrade~~ ✅ `v0.6.0`

Roux upgraded to 0.2.1, AutoCloseable fix, concurrency/resource/timeout examples, full doc update. → [Archive](.planning/milestones/v0.6.0-ROADMAP.md)

---

---

## ~~Milestone 4: Doc Audit & v0.7.0 Release~~ ✅ `v0.7.0`

Doc audit (25 files), README rewrite, 4 legacy effect docs archived, version bumped 0.4.0→0.7.0, 629 tests green. → [Archive](.planning/milestones/v0.7.0-ROADMAP.md)

---

---

## Milestone 5: Cluster Evaluation & Enhancement `v0.8.0`

**Phases**: 22–31 | **Branch**: `feature/roux-effect-integration`

Evaluate and harden the cluster module. Introduce a pluggable serialization layer to replace Java native serialization throughout messaging and persistence. Add Redis-backed shared persistence so StatefulActors survive node reassignment. Harden cluster reliability, observability, and operations.

---

### ~~Phase 22: Cluster & Persistence Audit~~ ✅

**Goal**: Written findings document covering gaps, risks, and test coverage blind spots in both the cluster module and the persistence system.

Plans:
- 22.1 Audit `ClusterActorSystem`, `ReliableMessagingSystem`, `MessageTracker`, `EtcdMetadataStore`, `RendezvousHashing` — document design gaps and risks
- 22.2 Audit `StatefulActor` recovery + `PersistenceProvider` impls — demonstrate the state-loss-on-reassignment bug with a failing test
- 22.3 Audit existing cluster test coverage — identify blind spots (node failure, message ordering, split-brain)
- 22.4 Produce findings document summarising all issues, prioritised for the phases ahead

---

### ~~Phase 23: Serialization Framework~~ ✅

**Goal**: Pluggable `SerializationProvider` interface with Kryo and JSON implementations, wired into `ReliableMessagingSystem`, `FileMessageJournal`, and `LmdbMessageJournal`. Message types no longer required to implement `Serializable`.

Plans:
- 23.1 Evaluate Kryo vs Jackson vs protobuf — pick Kryo as primary (performance, no-schema) and Jackson JSON as secondary (debuggability)
- 23.2 Design and implement `SerializationProvider` interface: `byte[] serialize(Object)` / `<T> T deserialize(byte[], Class<T>)`
- 23.3 Implement `KryoSerializationProvider` and `JsonSerializationProvider`
- 23.4 Wire `SerializationProvider` into `ReliableMessagingSystem` (inter-node messages) and `FileMessageJournal` / `LmdbMessageJournal` (persistence)
- 23.5 Tests: round-trip serialization, cross-version compatibility, verify `Serializable` constraint removed from message types

---

### ~~Phase 24: Redis Persistence Design~~ ✅

**Goal**: Documented schema design for Redis-backed journal and snapshot stores, with evaluated tradeoffs vs existing file and LMDB providers.

Plans:
- 24.1 Evaluate Redis data structures for journal (Streams vs Lists vs Sorted Sets) and snapshot (Hash vs String vs JSON)
- 24.2 Design key namespace: `cajun:journal:{actorId}` and `cajun:snapshot:{actorId}`
- 24.3 Evaluate Redis persistence modes (RDB, AOF, no persistence) and their impact on actor durability guarantees
- 24.4 Document tradeoffs: Redis vs LMDB vs file — latency, throughput, cross-node access, operational complexity
- 24.5 Choose Redis client library (Lettuce vs Jedis) and add dependency to `cajun-persistence/build.gradle`

---

### ~~Phase 25: Redis Persistence Provider~~ ✅

**Goal**: `RedisPersistenceProvider`, `RedisMessageJournal`, and `RedisSnapshotStore` implemented, tested, and registered in `PersistenceProviderRegistry`. Uses `SerializationProvider` from Phase 23.

Plans:
- 25.1 Implement `RedisMessageJournal<M>`: `append`, `readFrom`, `truncateBefore`, `getHighestSequenceNumber` using Redis Streams
- 25.2 Implement `RedisSnapshotStore<S>`: `saveSnapshot`, `getLatestSnapshot`, `deleteSnapshots` using Redis Hash/String
- 25.3 Implement `RedisPersistenceProvider` factory; register as `"redis"` in `PersistenceProviderRegistry`
- 25.4 Unit tests: round-trip journal append/read, snapshot save/load, truncation, sequence number tracking
- 25.5 Integration tests: `StatefulActor` with Redis provider — full journal replay and snapshot recovery

---

### ~~Phase 26: Cluster + Shared Persistence Integration~~ ✅

**Goal**: `ClusterActorSystem` uses Redis-backed persistence so StatefulActors recover their full state when reassigned to a new node. `PidRehydrator` verified cross-node. Benchmark Redis vs LMDB vs file.

Plans:
- 26.1 Wire `RedisPersistenceProvider` as the default persistence provider in `ClusterActorSystem` startup
- 26.2 Add persistence health check to cluster startup — fail fast if Redis unreachable
- 26.3 Verify `PidRehydrator` correctly resolves `Pid` references across nodes after actor migration
- 26.4 Integration test: actor on node A accumulates state → node A killed → actor reassigned to node B → verify full state recovered from Redis
- 26.5 Benchmark: message throughput and recovery latency for Redis vs LMDB vs file-based persistence

---

### ~~Phase 27: Observability & Diagnostics~~ ✅

**Goal**: Metrics API exposing throughput, latency, and cluster health; structured logging throughout cluster and persistence code.

Plans:
- 27.1 Define `ClusterMetrics` API: messages routed (local vs remote), routing latency, node count, actor count per node
- 27.2 Define `PersistenceMetrics` API: journal append latency, snapshot save/load latency, provider health status
- 27.3 Implement metrics collection in `ClusterActorSystem` and `ReliableMessagingSystem`
- 27.4 Add cluster health check: `ClusterActorSystem.healthCheck()` returning node liveness, leader status, persistence reachability
- 27.5 Improve structured logging (MDC or structured log fields) throughout cluster and persistence paths

---

### ~~Phase 28: Reliability Hardening~~ ✅

**Goal**: Circuit breaker for node-to-node calls; exponential backoff for metadata store operations; graceful degradation when etcd is unavailable.

Plans:
- 28.1 Implement circuit breaker for `ReliableMessagingSystem` — open on repeated node failures, half-open probe, close on recovery
- 28.2 Add exponential backoff with jitter to `EtcdMetadataStore` retry logic for transient failures
- 28.3 Graceful degradation: define behaviour when etcd is unreachable (continue routing with cached assignments vs fail-fast)
- 28.4 Improve error messages throughout cluster code — include node IDs, actor IDs, and failure context
- 28.5 Tests: circuit breaker state transitions, retry backoff behaviour, degraded-mode routing

---

### ~~Phase 29: Performance Optimization~~ ✅

**Goal**: Local actor-location cache reduces etcd round-trips; EtcdMetadataStore uses connection pooling; batch actor registration on startup.

Plans:
- 29.1 Implement local actor-location cache in `ClusterActorSystem` with TTL-based invalidation and watcher-driven eviction
- 29.2 Add connection pooling to `EtcdMetadataStore` (configure pool size, idle timeout)
- 29.3 Batch actor registration on node startup — single bulk write instead of one etcd put per actor
- 29.4 Profile hot paths under load — identify any remaining bottlenecks in routing or messaging
- 29.5 Benchmarks: routing throughput and latency with and without caching; registration time with batch vs sequential

**Code review P1 fixes** (commit `159fc2c`, plan `29-2`):
- Fix double-counted `remoteMessageFailures` in `routeToNode().exceptionally()`
- Catch `SerializationException` explicitly in `handleClient()` (both RMS copies)
- Fix Jackson RCE: replace `DefaultTyping.EVERYTHING` + `allowIfBaseType(Object)` with trusted package prefixes + `NON_FINAL` in `JsonSerializationProvider`

---

### ~~Phase 30: Cluster Management API~~ ✅

**Goal**: Programmatic API for cluster operations — node listing, actor migration, node draining. Config builder for simpler cluster setup.

Plans:
- ✅ **30-1** `ClusterConfiguration` builder + `ClusterManagementApi` interface + `listNodes`/`listActors` + 10 unit tests
- ✅ **30-2** `migrateActor` + `drainNode` implementations + drain-and-rejoin tests (9 tests)

---

### ~~Phase 31: Testing, Documentation & Examples~~ ✅

**Goal**: Chaos tests, cross-node state recovery tests, serialization migration guide, and production deployment guide.

Plans:
- ✅ **31-1** Shared test helpers (WatchableInMemoryMetadataStore + InMemoryMessagingSystem) + chaos tests (3 sequential-failure scenarios) + management API lifecycle tests (3 scenarios)
- ✅ **31-2** Docs (rewrite cluster_mode.md, new cluster-deployment.md, cluster-serialization.md) + 100-msg state recovery test (`@Tag("requires-redis")`) + runnable ClusterStatefulRecoveryExample
