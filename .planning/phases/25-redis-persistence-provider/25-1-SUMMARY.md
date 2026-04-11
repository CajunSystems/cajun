# Phase 25 Plan 1 Summary — Redis Persistence Provider

## Status: Complete

## What Was Done
- Implemented `RedisMessageJournal<M>` with atomic Lua-based append, `readFrom` with filtering/sorting, `truncateBefore` via `HDEL`, and `getHighestSequenceNumber` via a dedicated seq counter key
- Implemented `RedisSnapshotStore<S>` with overwrite semantics (single Redis key per actor), PONG health check, and full `SnapshotEntry` serialization
- Implemented `RedisPersistenceProvider` with 3 constructors (URI-only, URI+prefix, full control), full `PersistenceProvider` factory API, and a `close()` method for resource cleanup
- Added 12 unit tests (mocked Lettuce) for `RedisMessageJournal` and `RedisSnapshotStore` covering all key paths
- Added 9 integration tests (tagged `requires-redis`) covering round-trip journal/snapshot operations and stateful actor recovery across system restarts

## Key Decisions
| Decision | Choice | Rationale |
|----------|--------|-----------|
| Serializer default | `JavaSerializationProvider` in `cajun-persistence`, `KryoSerializationProvider` in integration tests | Avoids Kryo dependency in `cajun-persistence`; tests in `lib` use Kryo for performance |
| Hash tag format | `{prefix}:journal:{actorId}` (actorId in `{}`) | Forces Redis Cluster co-location of journal hash and seq counter |
| Sequence numbering | INCR counter in `{prefix}:journal:{actorId}:seq` | Lua atomic: INCR then HSET in one round-trip |
| Snapshot storage | Single Redis String key `{prefix}:snapshot:{actorId}` | Overwrite semantics; only latest snapshot per actor is needed |
| RedisFuture mocking | Concrete anonymous `RedisFuture` implementation wrapping `CompletableFuture` | Avoids Mockito strict-stubbing issues with `default` interface methods |

## Deviations from Plan
- `build.gradle` for both `cajun-persistence` and `lib` already had `requires-redis` tag excluded before this phase began (pre-existing from Phase 24 setup commit `a8a41c6`)
- Tasks 1-3 implementation files were already committed before this plan execution started

## Test Results
All tests pass: `./gradlew test` — BUILD SUCCESSFUL (requires-redis integration tests excluded as intended)

## Commits
- `e3e0a1a` feat(25-1): implement RedisMessageJournal with Lua atomic append
- `6f4876e` feat(25-1): implement RedisSnapshotStore with single-key overwrite semantics
- `a8a41c6` feat(25-1): implement RedisPersistenceProvider; exclude requires-redis tag
- `7b8048b` test(25-1): unit tests for Redis journal and snapshot with mocked Lettuce
- `1d0d569` test(25-1): integration tests for Redis journal, snapshot, and StatefulActor recovery
