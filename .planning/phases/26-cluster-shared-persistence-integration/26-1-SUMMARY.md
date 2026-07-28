# Phase 26 Plan 1 Summary — Cluster + Shared Persistence Integration

## Status: Complete

## What Was Done
- Added `withPersistenceProvider()` fluent setter to `ClusterActorSystem` — registers provider in `PersistenceProviderRegistry` and sets as default during `start()`; logs WARN if unhealthy at startup
- Added `setupPersistence()` private helper called at the start of the `thenRun` block in `start()`, ensuring persistence is configured before heartbeat and leader election begin
- Added `PidRehydratorClusterTest` with 6 unit tests verifying null-system Pid replacement, non-null preservation, record traversal, nested Pid rehydration, null-state handling, and no-Pid state pass-through
- Rewrote `StatefulActorClusterStateTest`: removed class-level `@Disabled`, added `@Tag("requires-redis")`, replaced `MockBatchedMessageJournal`/`MockSnapshotStore` with shared `RedisPersistenceProvider` (same Redis URI + actorId for both system1 and system2). `testStatefulActorStateRecoveredViaRedis` asserts count=6 (5 recovered from Redis + 1 new). Original bug-doc test kept `@Disabled` at method level.
- Added `PersistenceBenchmarkTest` with 4 throughput tests tagged `@Tag("performance")`: file journal append, file journal recovery, Redis journal append (`@Tag("requires-redis")`), Redis journal recovery (`@Tag("requires-redis")`). N=500 messages; results printed to stdout.

## Key Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Persistence wiring point | `start()` → `setupPersistence()` | Ensures metadata store + messaging ready before persistence configured; no-op if null |
| Health check severity | WARN (not fail-fast) | Backward compat; cluster may still route messages even if persistence is temporarily degraded |
| Cross-node test design | `requires-redis` + UUID actor IDs + `InMemoryMetadataStore` | No etcd needed; UUID prevents cross-run Redis key accumulation |
| Original bug test | Kept `@Disabled` at method level | Preserves root-cause documentation alongside the fix |
| Benchmark assertions | Correctness only (no latency SLA) | Throughput values are environment-dependent; benchmarks exist to inform, not gate |

## Test Results
All 649+ non-Redis/non-performance tests pass. `BUILD SUCCESSFUL`. No regressions.

## Commits
- `80b6a9c` feat(26-1): add withPersistenceProvider() to ClusterActorSystem with startup health check
- `91748dc` test(26-1): unit tests for PidRehydrator in cluster/recovery context
- `45dbd6c` fix(26-1): fix StatefulActorClusterStateTest — state recovered via Redis cross-node
- `c5616cb` test(26-1): persistence throughput benchmark — file vs Redis journal append and recovery
