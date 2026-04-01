# Phase 8 Summary: Stateful Actor + Effect Actor Composition

## Status: Complete

## Tasks Completed

### Task 1 — EffectStatefulCompositionExample.java
**Commit**: 875f841 (initial), 21f707e (fix)
- Created `lib/src/test/java/examples/EffectStatefulCompositionExample.java`
- 2 tests: `shoppingCartWithEffectAuditActor`, `cartStateAccumulatesCorrectlyWithEffectAuditLogging`
- Shopping cart `StatefulHandler` + audit `EffectActorBuilder` wired via constructor injection
- Fire-and-forget from `CartHandler.receive()` to effect actor via `context.tell(auditPid, ...)`
- Ask-pattern query (`system.ask()`) on the stateful actor to retrieve cart total

### Task 2 — EffectAskPatternExample.java
**Commit**: e7e2f14
- Created `lib/src/test/java/examples/EffectAskPatternExample.java`
- 2 tests: `effectActorRepliesViaEmbeddedReplyToPid`, `multipleCallersShareOneEffectActor`
- Demonstrates reply-via-Pid pattern: `ComputeRequest(String input, Pid replyTo)` record
- Single caller + multiple concurrent callers sharing one effect actor

### Task 3 — Full test suite
- BUILD SUCCESSFUL
- lib module: 353 tests, 0 failures, 0 errors
- `EffectStatefulCompositionExample`: 2 tests ✓
- `EffectAskPatternExample`: 2 tests ✓

## Deviations

### Deviation 1 — Serializable required on CartMessage/CartState/CartItem
**Root cause**: `StatefulActor` journals messages to disk **before** processing. Without `Serializable` on the inner records, the journal write fails with `NotSerializableException`. Because journaling is async and runs concurrently with message dispatch, the `context.tell(auditPid, ...)` call inside `CartHandler.receive()` never fires, causing `CountDownLatch` timeouts.

**Fix**: Added `implements Serializable` to `CartItem`, `CartState`, `CartMessage extends Serializable` (sealed interface), and `AuditMessage extends Serializable`.

### Deviation 2 — UUID-based actor IDs required
**Root cause**: The file journal for `StatefulActor` accumulates messages across test runs (by design — for recovery). Fixed actor IDs like `"shopping-cart"` and `"cart-b"` would replay prior runs' messages on subsequent test executions, inflating the cart total (e.g., expected 89.97, got 269.91 = 3× from three runs).

**Fix**: Each test generates a short UUID suffix (`UUID.randomUUID().toString().substring(0, 8)`) for actor IDs, ensuring a fresh journal per test run. This is the idiomatic pattern for example-style tests that use StatefulHandler with default file persistence.

## API Findings
- `StatefulActor` journals messages **before** calling `handler.receive()` — message/state types MUST implement `java.io.Serializable` for stateful actors
- Sealed interfaces can declare `extends Serializable`; the nested records inherit it
- File journal is append-only and persists across JVM runs — use unique actor IDs in tests to avoid cross-run state accumulation
- `system.statefulActorOf(handlerInstance, initialState)` accepts an instance (not class) — required when handler has constructor args (e.g., `Pid auditPid`)
- `context.getSender()` correctly populates for `system.ask()` calls on `StatefulHandler` actors
- `EffectActorBuilder` actors do NOT expose `ActorContext`, so the reply-via-Pid pattern (embedding `Pid replyTo` in the request message) is the canonical way to reply
