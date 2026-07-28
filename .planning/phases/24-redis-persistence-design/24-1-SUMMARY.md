# Phase 24 Plan 1 Summary — Redis Persistence Design

## Status: Complete

## What Was Done
- Wrote comprehensive Redis schema design document (`24-1-DESIGN.md`) — 7 sections, 213 lines
- Documented Redis Hash for journal, Redis String for snapshot
- Specified key namespace with Redis Cluster hash tag co-location (`cajun:journal:{actorId}`, `cajun:journal:{actorId}:seq`, `cajun:snapshot:{actorId}`)
- Mapped all `MessageJournal` and `SnapshotStore` methods to Redis commands
- Documented Lettuce async API patterns (Lua script for atomic INCR+HSET append)
- Compared Redis vs LMDB vs file persistence (throughput, latency, cross-node access, ops complexity)
- Identified 6 known limitations/risks for Phase 25 to address
- Added `io.lettuce:lettuce-core:6.3.2.RELEASE` to `cajun-persistence/build.gradle` and `lib/build.gradle`

## Key Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Journal data structure | Redis Hash (field=seqNum, value=bytes) | O(1) append/read; maps cleanly to explicit integer seqNums |
| Snapshot data structure | Redis String per actor | SnapshotStore only needs latest snapshot; SET/GET/DEL is simplest |
| Sequence atomicity | Lua script (INCR + HSET) | Eliminates sequence gap risk on partial write failure |
| Redis persistence | AOF `appendfsync everysec` | ≤1s data loss; practical throughput tradeoff |
| Client library | Lettuce 6.3.2 | Native CompletableFuture API matches async interface contract |
| Redis Streams rejected | — | Time-based IDs incompatible with Cajun's explicit integer seqNums |

## Test Results
All 644 existing tests pass. No regressions from Lettuce dependency addition.

## Commits
- `d10f265` docs(24-1): Redis persistence schema design document
- `18b0969` chore(24-1): add Lettuce 6.3.2.RELEASE dependency to cajun-persistence and lib
