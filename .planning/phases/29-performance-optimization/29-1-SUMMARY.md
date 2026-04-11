# Phase 29 Plan 1 Summary — Performance Optimization

## Status: Complete

## What Was Done
- Implemented `TtlCache<K, V>` in `cajun-core` — `ConcurrentHashMap<K, Entry<V>>` backing; passive TTL check on `get()` with conditional remove (avoids TOCTOU); `snapshot()` returns non-expired entries; `cleanupExpired()` for periodic bulk eviction; `invalidate(K)` for watcher-driven eviction
- Promoted `actorAssignmentCache` in `ClusterActorSystem` from `ConcurrentHashMap` fallback to `TtlCache<String, String>` **primary routing path** — `routeMessage()` now checks cache first (cache hit = no etcd round-trip), only falls back to metadata store on cache miss; `routeToNode()` private helper extracted to eliminate duplication between hit and miss paths; actor assignment watcher in `start()` drives real-time cache updates/evictions; `cleanupExpired()` scheduled every 30s via existing `scheduler`
- Added `cacheHitCount` and `cacheMissCount` to `ClusterMetrics` (in `cajun-core`) with increment methods and getters
- Configured gRPC keep-alive in `EtcdMetadataStore.connect()` — `keepAliveTime(5s)`, `keepAliveTimeout(3s)`, `keepAliveWithoutCalls(true)`; new overloaded constructor accepts all three config values; default constructor delegates to configured constructor with sensible defaults
- Added `batchRegisterActors(List<String> actorIds)` to `ClusterActorSystem` — `CompletableFuture.allOf()` over all `metadataStore.put()` calls; logs total time on completion; returns immediately for empty lists
- Unit tests: `TtlCacheTest` (7 tests) covering put/get, TTL expiry, invalidate, snapshot exclusion, cleanup, concurrent access, overwrite resetting TTL
- Performance benchmark: `ClusterRoutingBenchmarkTest` (2 tests, `@Tag("performance")`) — cache-hit routing throughput + batch vs sequential registration with simulated latency; correctness assertions only, throughput printed to stdout as `[BENCH]` lines

## Key Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Cache as primary vs fallback | Primary fast path | Phase 28 cache was fallback-only; Phase 29 promotes it — etcd is only consulted on cache miss, not every message |
| `routeToNode()` helper | Extracted private method | Avoids duplicating remote-send logic between cache-hit and cache-miss branches |
| TTL duration | 60s default | Balances staleness risk vs etcd traffic reduction; watcher covers rebalancing scenarios |
| Cleanup strategy | Passive (on get) + periodic (every 30s via existing scheduler) | No extra thread; passive handles hot keys; periodic handles cold keys |
| gRPC keep-alive | 5s time / 3s timeout / without-calls=true | Reduces reconnect latency for bursty traffic patterns; timeout shorter than keepalive prevents hanging |
| Batch registration | `CompletableFuture.allOf()` parallelism | Simplest correct implementation; avoids sequential etcd round-trips for startup actor registration |

## Deviations from Plan
- Previous subagent hit rate limit after Tasks 1–4 and writing test files (before Task 5 commit); caller committed Task 5 and ran checkpoint
- Branch discrepancy: work landed on `feature/cluster-improvements` (via merge commit `28c5802`) rather than `feature/roux-effect-integration`; both branches contain identical Task 1–4 commits

## Test Results
BUILD SUCCESSFUL. All non-`requires-redis`/non-`requires-etcd`/non-`performance` tests pass.

## Commits
- `e949a91` feat(29-1): add TtlCache — TTL-aware concurrent cache
- `17f8f08` feat(29-1): upgrade actorAssignmentCache to TtlCache with primary cache-hit routing path
- `7270780` feat(29-1): configure gRPC keep-alive settings in EtcdMetadataStore
- `18555b9` feat(29-1): add batchRegisterActors for parallel metadata store registration
- `0ff8ec5` test(29-1): TtlCache unit tests and cluster routing benchmark
