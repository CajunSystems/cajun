# Phase 9, Plan 1 Summary: EffectPipelineExample

## Status: Complete

## Tasks Completed

### Task 1 — EffectPipelineExample.java
**Commit**: c58edb8
- Created `lib/src/test/java/examples/EffectPipelineExample.java`
- 2 tests: `validRecordFlowsThroughAllFourStages`, `invalidRecordsAreMarkedNotDropped`
- 4 pipeline stages: Enricher → Validator → Transformer → Sink
- Wired via Pid closure capture (sink-first build order)
- No deviations — code matched plan exactly

### Task 2 — Full test suite
- Our 2 new tests: BUILD SUCCESSFUL (isolated run: `examples.EffectPipelineExample`)
- Full suite: 355 tests, 1 failure (`ClusterModeTest.testRemoteActorCommunication`) — **pre-existing, unrelated to our changes**
- `ClusterModeTest` fails standalone too (requires etcd/network infrastructure not present in CI)

## Deviations
None. Both tests compiled and passed on first attempt with no modifications required.

## API Findings
- `EffectActorBuilder` actors with different message types can be wired in a sink-first
  order by capturing each downstream `Pid` in the upstream stage's lambda closure —
  no constructors or setters needed
- `Effect.generate(ctx -> ..., logHandler)` and `Effect.suspend(() -> ...)` can be
  mixed freely across stages in the same pipeline
- `LogCapability.Warn` fires correctly for rejected records without affecting message flow
- `CopyOnWriteArrayList` + `CountDownLatch` is the right test harness for unordered multi-message collection
- Pipeline record types (`RawRecord`, `EnrichedRecord`, etc.) do NOT need `Serializable`
  because `EffectActorBuilder` actors are not `StatefulActor` — no message journaling
