# Phase 28 Plan 1 Summary — Reliability Hardening

## Status: Complete

## What Was Done
- Implemented `NodeCircuitBreaker` in `cajun-core` — three-state FSM (CLOSED→OPEN→HALF_OPEN→CLOSED), configurable `failureThreshold` (default 5) and `resetTimeoutMs` (default 30s), thread-safe via `synchronized` + `volatile`; added `CircuitBreakerOpenException` carrying the nodeId
- Wired circuit breaker into both copies of `ReliableMessagingSystem` (cajun-core and lib) — `doSendMessage()` checks `isCallPermitted()` before connecting, calls `recordSuccess()` after ack, `recordFailure()` on IOException; `CircuitBreakerOpenException` short-circuits the send with a WARN log; `getCircuitBreakerForNode(nodeId)` public accessor added
- Implemented `ExponentialBackoff` in `cajun-core` — exponential delay with jitter (`initialDelay × 2^attempt ± jitter%`, capped at `maxDelay`); async `withRetry(supplier, scheduler)` using `exceptionallyCompose` for non-blocking retry chains; wired into `EtcdMetadataStore` `put()`, `get()`, `delete()`, `listKeys()` (5 attempts, 100ms→30s, 25% jitter); `acquireLock/watch/unwatch/connect/close` intentionally not wrapped
- Added `actorAssignmentCache` (`ConcurrentHashMap<String, String>`) to `ClusterActorSystem` — updated on every successful metadata lookup in `routeMessage()`; on `metadataStore.get()` failure, `exceptionally()` handler falls back to cached assignment with WARN log; drops message with ERROR if no cache entry; `getActorAssignmentCache()` accessor added for testing
- Improved error messages in both `ReliableMessagingSystem` copies and `ClusterActorSystem` — all error/warn logs now include messageId, actorId, targetSystemId, host:port as appropriate
- Unit tests: `NodeCircuitBreakerTest` (7 tests), `ExponentialBackoffTest` (7 tests), `DegradedRoutingTest` (2 tests)

## Key Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Circuit breaker granularity | Per-node (not per-actor) | One node failure should block all messages to that node, not just per-actor |
| Circuit breaker implementation | Synchronized methods + volatile state | Simpler and correct for low-contention; no lock-free complexity needed |
| Backoff wrapping scope | Only idempotent KV ops (put/get/delete/listKeys) | `acquireLock` must not retry (double-acquire risk); watch/stream subscriptions are not request/response |
| Graceful degradation trigger | `exceptionally()` on `metadataStore.get()` future | Zero overhead on happy path; cache update piggybacks on the existing success handler |
| Error message level | WARN for degraded routing, ERROR for dropped messages | Degraded mode is recoverable; dropped messages represent data loss |

## Deviations from Plan
- `put()`/`delete()` explicit `(Void) null` cast required in backoff wrapping due to Java generics erasure
- `DegradedRoutingTest` key prefix corrected from `"actor:actor-1"` to `"cajun/actor/actor-1"` — matches `ClusterActorSystem.ACTOR_ASSIGNMENT_PREFIX`
- `NoOpMessagingSystem` omits `addNode`/`removeNode` — those methods are not in the `MessagingSystem` interface

## Test Results
BUILD SUCCESSFUL. All non-`requires-redis`/non-`requires-etcd`/non-`performance` tests pass.

## Commits
- `98a57c6` feat(28-1): add NodeCircuitBreaker and CircuitBreakerOpenException
- `5c53d33` feat(28-1): wire per-node circuit breaker into ReliableMessagingSystem
- `b46d60b` feat(28-1): add ExponentialBackoff and wire retry into EtcdMetadataStore
- `8d713c4` feat(28-1): add actor-assignment cache for graceful etcd degradation
- `70f3df5` refactor(28-1): improve error messages in cluster code with full actor/node context
- `0087643` test(28-1): unit tests for circuit breaker, exponential backoff, and degraded routing
