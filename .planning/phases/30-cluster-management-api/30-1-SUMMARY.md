---
plan: 30-1
phase: 30 — Cluster Management API
title: ClusterConfiguration Builder + ClusterManagementApi (read ops)
status: complete
---

## What Was Done

**Task 1** — `c884fd5` `refactor(30-1): make cluster key-prefix constants package-private`
Changed `ACTOR_ASSIGNMENT_PREFIX` and `NODE_PREFIX` in `ClusterActorSystem.java` from `private static final` to package-private (`static final`) so `DefaultClusterManagementApi` can reference them directly.

**Task 2** — `8d55b8d` `feat(30-1): implement ClusterConfiguration builder`
Created `ClusterConfiguration.java` — a fluent builder that wraps `ClusterActorSystem` construction. Required fields: `metadataStore`, `messagingSystem`. Optional: `systemId` (auto-generated UUID prefix if not set), `deliveryGuarantee`, `persistenceProvider`. `Builder.build()` validates required fields, constructs the system, applies optional configuration, and returns the `ClusterActorSystem`.

**Task 3** — `002251a` `feat(30-1): implement ClusterManagementApi interface and DefaultClusterManagementApi`
Created `ClusterManagementApi.java` — public interface with 4 method signatures: `listNodes()`, `listActors(nodeId)`, `migrateActor(actorId, targetNodeId)`, `drainNode(nodeId)`. Created `DefaultClusterManagementApi.java` — package-private implementation. `listNodes()` strips the `cajun/node/` prefix from `metadataStore.listKeys()`. `listActors()` lists all actor keys and filters by assigned node using `CompletableFuture.allOf()`. `migrateActor` and `drainNode` throw `UnsupportedOperationException` as stubs for plan 30-2.

**Task 4** — `3ccf5fb` `feat(30-1): wire getManagementApi() and invalidateActorAssignmentCache() into ClusterActorSystem`
Added `private final ClusterManagementApi managementApi` field initialized at the end of the constructor (after `metadataStore` is set, so `getMetadataStore()` returns non-null). Added `getManagementApi()` public accessor after `getClusterMetrics()`. Added `invalidateActorAssignmentCache(String actorId)` package-private method for use by plan 30-2.

**Task 5** — `92d315f` `test(30-1): ClusterConfiguration builder and ClusterManagementApi read-op tests`
Created `ClusterManagementApiReadTest.java` with 10 unit tests. Uses in-memory `InMemoryMetadataStore` (ConcurrentHashMap-backed) and `NoopMessagingSystem` stubs — no etcd, Redis, or real messaging required. Does NOT call `system.start()`. Tests cover: builder with all fields, auto-generated systemId, missing-metadataStore/messagingSystem validation, explicit systemId, listNodes with 2 nodes, listNodes empty, listActors filtering by node, listActors empty, and 3 actors on same node.

## Deviations

- `managementApi` field is initialized in the constructor body (after `metadataStore` assignment) rather than as a field initializer. This is required because field initializers run before the constructor body, so `getMetadataStore()` would return `null` if initialized via a field expression. Functionally equivalent to the plan's intent.

## Test Results

All 10 tests in `ClusterManagementApiReadTest` pass. Full `./gradlew :lib:test` suite passes (BUILD SUCCESSFUL).

## Commits

| Hash | Message |
|------|---------|
| `c884fd5` | `refactor(30-1): make cluster key-prefix constants package-private` |
| `8d55b8d` | `feat(30-1): implement ClusterConfiguration builder` |
| `002251a` | `feat(30-1): implement ClusterManagementApi interface and DefaultClusterManagementApi` |
| `3ccf5fb` | `feat(30-1): wire getManagementApi() and invalidateActorAssignmentCache() into ClusterActorSystem` |
| `92d315f` | `test(30-1): ClusterConfiguration builder and ClusterManagementApi read-op tests` |
