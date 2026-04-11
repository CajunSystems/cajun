# Phase 27 Plan 1 Summary — Observability & Diagnostics

## Status: Complete

## What Was Done
- Added `ClusterMetrics` in `cajun-core/src/main/java/com/cajunsystems/metrics/` — AtomicLong counters for local/remote routing, send/receive/failure counts, routing latency (total + count → average), node join/departure counts
- Added `PersistenceMetrics` and `PersistenceMetricsRegistry` in `cajun-core/src/main/java/com/cajunsystems/metrics/` — per-actor counters for journal append/read and snapshot save/load, with latency tracking and error counts; registry uses `computeIfAbsent` for thread-safe getOrCreate
- Wired `ClusterMetrics` into `ClusterActorSystem`: routing branch increments local/remote counters with latency recording; node watcher increments join/departure counts; `getClusterMetrics()` accessor added
- Wired `ClusterMetrics` into both copies of `ReliableMessagingSystem` (one in `cajun-core`, one in `lib`) via optional setter `setClusterMetrics(ClusterMetrics)` — null-checked before each increment so existing construction paths are unaffected
- Added `ClusterHealthStatus` record in `lib/src/main/java/com/cajunsystems/cluster/ClusterHealthStatus.java` — fields: `healthy`, `nodeId`, `isLeader`, `knownNodeCount`, `messagingSystemRunning`, `persistenceHealthy`, `persistenceProviderName`; `healthy` = `persistenceHealthy && messagingSystemRunning`
- Added `ClusterActorSystem.healthCheck()` — returns `ClusterHealthStatus` with live values from `isLeader`, `knownNodes.size()`, and `persistenceProvider.isHealthy()`
- Added MDC structured logging to `ReliableMessagingSystem.doSendMessage()` (sets `targetSystem`, `actorId`, `messageId`) and `handleClient()` (sets `sourceSystem`, `actorId`, `messageId`) — both use try-finally to clear MDC on exit
- Updated `lib/src/main/resources/logback.xml` pattern to include `[%X{actorId}][%X{messageId}]` — outputs empty for non-cluster log lines
- Added unit tests: `ClusterMetricsTest` (4 tests), `PersistenceMetricsTest` (5 tests), `ClusterHealthStatusTest` (5 tests) — all use inline no-op infrastructure, no etcd/Redis needed

## Key Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Metrics module placement | `cajun-core` for both `ClusterMetrics` and `PersistenceMetrics` | `ReliableMessagingSystem` is in `cajun-core` — must be able to import `ClusterMetrics` |
| `ReliableMessagingSystem` injection | Optional setter `setClusterMetrics()` with null checks | Two copies exist; existing constructors unchanged; `ClusterActorSystem` injects via `instanceof` check |
| `ClusterHealthStatus.healthy` | `persistenceHealthy && messagingSystemRunning` | Composite: system is healthy only if both messaging and persistence are up |
| MDC clearing strategy | try-finally in each instrumented method | Guarantees MDC never leaks to unrelated log lines even on exception |
| Test infrastructure | Inline `InMemoryMetadataStore` + `NoOpMessagingSystem` | Matches existing test pattern; no shared class extraction needed |

## Deviations from Plan
- Two copies of `ReliableMessagingSystem` exist (`cajun-core` and `lib`) — both updated identically with MDC and counter instrumentation
- `ClusterHealthStatusTest` uses inline `InMemoryMetadataStore` and `NoOpMessagingSystem` (matching existing test file pattern) rather than a shared extracted class

## Test Results
BUILD SUCCESSFUL. All non-`requires-redis`/non-`requires-etcd`/non-`performance` tests pass.

## Commits
- `583ebb7` feat(27-1): add ClusterMetrics — routing and node counters
- `a686231` feat(27-1): add PersistenceMetrics and PersistenceMetricsRegistry
- `4188e3f` feat(27-1): wire ClusterMetrics into ClusterActorSystem and ReliableMessagingSystem
- `50b901f` feat(27-1): add ClusterHealthStatus record and ClusterActorSystem.healthCheck()
- `22f71cd` feat(27-1): add MDC structured logging to ReliableMessagingSystem and update logback.xml
- `d552fb2` test(27-1): unit tests for ClusterMetrics, PersistenceMetrics, and ClusterHealthStatus
