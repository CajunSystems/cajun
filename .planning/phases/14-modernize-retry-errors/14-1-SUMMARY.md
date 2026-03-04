# Phase 14, Plan 1 Summary: Modernize Retry & Error Examples

## Status: Complete

## Tasks Completed

### Task 1 — Rewrite EffectRetryExample.java
**Commit**: 5c64fb5

Replaced the hand-rolled `withRetry(catchAll)` static helper with Roux's native operators.
Same 3 test scenarios, now demonstrating all 3 built-in retry strategies:

- `builtInRetrySucceedsAfterTransientFailures` — `effect.retry(2)` = 2 additional attempts = 3 total; immediate retry, no delay
- `retryWithDelayPausesAndSucceeds` — `effect.retryWithDelay(2, Duration.ofMillis(10))` = same counting; 10ms pause between retries; error type widens to Throwable
- `retryPolicyExhaustsThenFails` — `RetryPolicy.immediate().maxAttempts(2)`; always-failing effect; verifies 3 total calls; result captured as `Either.Left` via `attempt()`

Net change: -80 lines (removed helper + old imports), +57 lines (new API). Same 3 tests.

### Task 2 — Add tap/tapError tests to EffectErrorHandlingExample.java
**Commit**: 7c7eda5

Two new tests appended (existing 5 unchanged):

- `tapObservesSuccessValueWithoutAltering` — `tap(v -> observed.set(...))` fires on success; `"hello"` passes through unchanged; `AtomicReference` confirms side-effect ran
- `tapErrorObservesFailureWithoutSuppressing` — `tapError(e -> observed.set(...))` fires on failure; original `RuntimeException("boom")` re-thrown; side-effect confirmed

Total: 5 → 7 tests in EffectErrorHandlingExample.

### Task 3 — Create EffectTimeoutExample.java
**Commit**: 94c8a3d

New file with 3 tests demonstrating `timeout(Duration)` + `catchAll` graceful degradation:

- `timeoutFallbackReturnsDefaultWhenDeadlineExceeded` — 500ms effect with 50ms deadline; `catchAll` catches `TimeoutException` → returns `"fallback"`
- `fastEffectCompletesNormallyUnderTimeout` — 10ms effect with 500ms deadline; completes normally, `"on time"` returned, `catchAll` never fires
- `actorAlwaysRepliesEvenOnTimeout` — effect actor with 100ms deadline; slow request (500ms) gets `"timeout-reply"`; fast request (20ms) gets `"processed-20"`

**Deviation**: Test methods must declare `throws Throwable` (not `throws Exception`).
`timeout().catchAll(...)` produces `Effect<Throwable, ...>` — the compiler enforces this.

### Task 4 — Full test suite
Result: **376 tests, 0 failures** — BUILD SUCCESSFUL

Previously 371 tests; added 5 new tests across Tasks 2 and 3.

## Deviations

### `throws Throwable` in EffectTimeoutExample
`timeout(Duration)` widens the error type to `Throwable`. When chained with `catchAll`, the
resulting `Effect<Throwable, A>` means `unsafeRun()` must declare `throws Throwable`, not just
`throws Exception`. Fixed immediately. Plan updated to note this.

## Key Learnings
- **`retry(n)` counting**: n = ADDITIONAL attempts (not total). `retry(2)` = 3 total.
- **`retryWithDelay` / `retry(RetryPolicy)` widen to `Throwable`**: methods need `throws Throwable`.
- **`RetryPolicy` maxAttempts = additional retries**, same as `retry(int)`.
- **`tap()` is success-side only** — runs action, passes value through. Does not change error type.
- **`tapError()` is observe-and-rethrow** — fires on failure, does NOT recover. Error propagates unchanged.
- **`timeout().catchAll(...)` → `Effect<Throwable,...>`** — both test methods needed `throws Throwable`.
- **`timeout` + `catchAll` = idiomatic degradation** — no explicit `instanceof TimeoutException` needed; `catchAll` catches anything.
