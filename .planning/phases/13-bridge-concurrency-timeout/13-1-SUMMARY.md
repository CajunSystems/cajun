# Phase 13, Plan 1 Summary: Bridge Concurrency & Timeout

## Status: Complete

## Tasks Completed

### Task 1 — Write ActorEffectRuntimeConcurrencyTest.java (new file)
**Commit**: b717fad

6 tests in new file `lib/src/test/java/com/cajunsystems/functional/ActorEffectRuntimeConcurrencyTest.java`:
- `parAllCollectsResultsInOrder` — 3 effects [1,2,3], asserts List.of(1,2,3) preserving order
- `parAllRunsEffectsConcurrently` — 3×50ms effects complete in <200ms (not ~150ms sequential)
- `parAllPropagatesFirstFailure` — failure in one effect propagates as RuntimeException
- `raceReturnsFirstCompletingEffect` — 10ms "fast" wins over 200ms "slow"
- `traverseAppliesFunctionToAllItems` — [1,2,3] → [10,20,30] via n*10 mapping
- `traverseShortCircuitsOnFailure` — fails at n==2, throws RuntimeException

All compiled and passed on first attempt.

### Task 2 — Add timeout tests to ActorEffectRuntimeTest.java
**Commit**: fda4ff4

Two tests added to existing `ActorEffectRuntimeTest`:
- `timeoutExpiresWhenEffectExceedsDeadline` — 500ms effect with 50ms deadline throws TimeoutException
- `timeoutDoesNotTriggerForFastEffect` — 10ms effect with 500ms deadline returns "on time"

**Deviation**: The plan suggested `java.util.concurrent.TimeoutException` but Roux throws its own
`com.cajunsystems.roux.exception.TimeoutException`. Fixed by checking `getClass().getName().contains("TimeoutException")` instead of instanceof — avoids coupling to internal package.

### Task 3 — Add scoped-fork capability inheritance test
**Commit**: 9b70148

Test `scopedForkInheritsCapabilityHandlerFromParent` added to `ActorEffectRuntimeTest`:
- Verifies Roux v0.2.1 fix: `forkIn(scope)` now propagates parent `ExecutionContext` (including capability handlers)
- Uses `Effect.scoped()` + `forkIn(scope)` to fork a child that calls `TrackCapability.Label("child")`
- Joins fiber, then calls `TrackCapability.Label("parent")` in parent context
- Handler injected via `runtime.unsafeRunWithHandler(effect, tracker)`
- Expected: `"handled-child+handled-parent"` — both parent and forked-child calls resolved

**Note**: `TrackCapability` defined as static nested sealed interface (Java 21 does not allow
local sealed interfaces). Handler built with `CapabilityHandler.builder()`.

### Task 4 — Run full test suite
Result: **371 tests, 0 failures** — BUILD SUCCESSFUL

Previously 362 tests; added 9 new tests across two files.

## Additional Work (not in original plan)

### Refactor: CapabilityHandler.builder() dispatch (committed before plan execution)
**Commit**: d760e93

Per user feedback on Phase 12 fix, replaced the `instanceof + UnsupportedOperationException`
pattern across all four handlers with `CapabilityHandler.builder()`:
- `ConsoleLogHandler` (production): static DELEGATE with 4 `.on()` registrations
- `EchoHandler` (CapabilityIntegrationTest): static DELEGATE
- `ValidationHandler` (EffectCapabilityExample): static DELEGATE
- `MetricsHandler` (EffectCapabilityExample): instance delegate (stateful — closures over maps)

The builder's dispatch automatically throws `UOE` for unregistered types, satisfying the
`compose()`/`orElse()` contract without any hand-written instanceof checks.

## Verification Checklist
- [x] `ActorEffectRuntimeConcurrencyTest.java` created with 6 tests
- [x] Tests 1.1–1.3 (parAll) pass
- [x] Test 1.4 (race) passes
- [x] Tests 1.5–1.6 (traverse) pass
- [x] Timeout tests (2.1, 2.2) added and pass
- [x] Scoped-fork capability inheritance test (3.1) added and passes
- [x] Full suite: 371 tests, 0 failures
- [x] All tasks committed individually

## Key Learnings
- **Roux TimeoutException**: Roux v0.2.0 uses `com.cajunsystems.roux.exception.TimeoutException`,
  not `java.util.concurrent.TimeoutException`. Class name check is the coupling-free assertion strategy.
- **Local sealed interfaces**: Java 21 does not permit `sealed interface` inside a method body.
  Workaround: static nested type in the test class.
- **CapabilityHandler.builder()**: Clean, composable dispatch registration; no manual instanceof
  needed. Stateless handlers use `static final DELEGATE`; stateful use instance field.
