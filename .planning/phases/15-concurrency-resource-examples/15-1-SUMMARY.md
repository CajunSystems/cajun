# Phase 15, Plan 1 Summary: New Concurrency & Resource Examples

## Status: Complete

## Tasks Completed

### Task 1 — Create EffectParallelExample.java
**Commit**: 7a2af84

New file `lib/src/test/java/examples/EffectParallelExample.java` with 3 tests:

- `parAllFansOutWorkAndCollectsResultsInOrder` — 3 concurrent fetches (50/30/40ms); results in insertion order A→B→C; elapsed <200ms confirms concurrent execution
- `raceSelectsFastestDataSource` — 3 sources (300/100/20ms); "fast-source" wins race
- `traverseTransformsEachItemSequentiallyInOrder` — `["hello","world","cajun"]` → lengths `[5,5,5]`; uses `throws RuntimeException` (traverse does NOT widen to Throwable)

All 3 compiled and passed on first attempt.

### Task 2 — Create EffectResourceExample.java
**Commit**: aedc365

New file `lib/src/test/java/examples/EffectResourceExample.java` with 4 tests:

- `resourceIsAlwaysReleasedAfterSuccessfulUse` — `Resource.make()` with `AtomicBoolean` tracking; both acquired and released confirmed after successful `use()`
- `resourceIsReleasedEvenWhenUseFails` — `use()` throws `RuntimeException`; release still runs (no resource leak)
- `fromCloseableReleasesAutoCloseableOnCompletion` — `Resource.fromCloseable()` wraps `AutoCloseable` mock; `close()` confirmed via `AtomicBoolean`
- `ensuringRunsFinalizerOnBothSuccessAndFailure` — `Resource.ensuring()` as try-finally; finalizer confirmed on both success and failure paths (two sub-cases in one test)

All 4 compiled and passed on first attempt.

### Task 3 — Full test suite
Result: **383 tests, 0 failures** — BUILD SUCCESSFUL

Previously 376 tests; added 7 new tests (3 parallel + 4 resource).

## Deviations

None. Both files compiled and passed tests on the first attempt.

## Key Learnings
- **`resource.use()` widens to `Throwable`**: test methods must declare `throws Throwable`, not `throws Exception`
- **`Resource.ensuring()` widens to `Throwable`**: same — declare `throws Throwable`
- **`traverse()` does NOT widen**: stays as `E`; test methods only need `throws RuntimeException` (or whatever E is)
- **`Resource.fromCloseable()` is the clean path for `AutoCloseable`**: no release lambda needed
- **Nested `assertThrows` + `assertTrue` in same test**: confirms the finalizer runs even after an assertion-throwing path (test 4 resets the flag and re-checks in the failure sub-case)
- **`AtomicBoolean` for lifecycle tracking**: clean and thread-safe; no `CountDownLatch` needed for non-async resource tests
