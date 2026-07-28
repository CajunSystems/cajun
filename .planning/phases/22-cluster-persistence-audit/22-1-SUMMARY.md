# Phase 22 Plan 1 Summary — Cluster & Persistence Audit

## Status: Complete

## What Was Done
- Audited 9 cluster source files and 8 persistence source files
- Reviewed 3 existing cluster tests (`ClusterModeTest`, `ClusterLocalActorTest`, `ClusterPerformanceTest`)
- Wrote a disabled failing test documenting the state-loss-on-reassignment bug
- Produced a findings document with 11 findings (2 Critical, 4 High, 5 Medium) prioritised for Phases 23–31

## Key Findings
- **C1: StatefulActor state lost on cluster reassignment** — Critical. `assignActorToNode()` only updates the metadata key; no state migration occurs.
- **C2: No actor instantiation on target node** — Critical. Actors are never actually spawned on reassigned nodes; messages are dropped.
- **H1: Java native serialization for inter-node messages** — High. All cross-node messages require `Serializable`; brittle and insecure.
- **H2: MessageTracker thread leak** — High. Internal `ScheduledExecutorService` left running if `stop()` is not called.
- **H3: EtcdMetadataStore no retry logic** — High. Single-attempt etcd operations; transient failures cause immediate cluster disruption.
- **H4: EtcdMetadataStore single client** — High. No connection pooling; bottleneck under load.
- **H5: PersistenceProviderRegistry JVM singleton** — High. Test isolation failures; no per-system provider configuration.
- 4 medium findings: file-based persistence is node-local; `unwatch()` mock is a no-op in tests; `Thread.sleep` timing in cluster tests; `shutdown()` discards its `CompletableFuture`.

11 findings total.

## Test Written
`StatefulActorClusterStateTest.java` — `@Disabled`, documents the state-loss-on-reassignment bug.
The test verifies count should be 6 (5 from system1 + 1 from system2) but gets 1 because system2
starts with fresh state. Will be enabled when Phase 26 delivers shared persistence.

## Commits
- `ac2ceab` test(22-1): add disabled failing test for state-loss-on-reassignment bug
- `6219f76` docs(22-1): audit findings for cluster and persistence modules
