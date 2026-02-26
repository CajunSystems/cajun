# Concerns

## Overall Risk: MEDIUM

Core actor model is solid and battle-tested. Several areas need attention before large-scale production deployments.

---

## Critical

### 1. Disabled Test — Effect.sequence() Failure Propagation
**File**: `lib/src/test/java/com/cajunsystems/functional/NewEffectOperatorsTest.java:286`

```java
@Disabled("TODO: Fix sequence failure propagation")
void testSequence_failsOnFirstError() { ... }
```

`Effect.sequence()` does not properly short-circuit on failure. Subsequent effects may execute even when an earlier one fails. Risk: silent partial failures, data consistency issues in multi-step workflows.

### 2. TestPid.tellAndWait() Uses Thread.sleep
**File**: `test-utils/src/main/java/com/cajunsystems/test/TestPid.java:90`

```java
// TODO: Implement proper message tracking
Thread.sleep(timeout.toMillis());
```

Tests relying on `tellAndWait()` may have race conditions and intermittent failures.

---

## High Priority

### 3. System.out/err.println in Production Code
**Files**:
- `lib/.../metrics/MetricsRegistry.java`
- `lib/.../metrics/ActorMetrics.java`
- `lib/.../FunctionalActor.java` (lines 96-97: `System.err.println` + `e.printStackTrace()`)
- `lib/.../backpressure/BackpressureBuilder.java`
- `lib/.../functional/capabilities/ConsoleLogHandler.java`

Direct stdout/stderr bypasses logging configuration, cannot be suppressed or routed to structured logging. `printStackTrace()` in FunctionalActor is particularly problematic.

### 4. Supervision Strategy Test Coverage Gaps
**Source**: `docs/supervision_audit.md:217-225`

Missing tests for:
- RESUME strategy — no test verifying actor continues after error
- STOP strategy — no test verifying actor stops permanently
- Multiple restart scenarios (consecutive failures)
- Concurrent error handling during batch processing
- shouldReprocess flag behavior
- Full mailbox during restart (message loss risk)

### 5. Deprecated APIs Still Present
**Files**: `lib/.../Actor.java`, `lib/.../ActorSystem.java`, `lib/.../config/MailboxConfig.java`, `MailboxProvider.java`, `DefaultMailboxProvider.java`, `ResizableMailboxConfig.java`

Marked `@Deprecated(forRemoval = true)` since v0.2.0–0.4.0, scheduled removal at v0.5.0. Dual maintenance burden; confusing for new users.

### 6. Legacy lib/ Module
**File**: `settings.gradle`

Monolithic `lib/` module coexists with new modular structure (`cajun-core`, `cajun-mailbox`, etc.). Dual maintenance burden, potential version inconsistencies, confusing entry point for new users. Scheduled removal at v0.5.0.

---

## Medium Priority

### 7. ThreadLocal Cleanup Risk in Virtual Threads
**Files**: `lib/.../Actor.java:58-72`, `lib/.../StatefulActor.java` (asyncSenderContext)

Three ThreadLocals in use:
- `senderContext` (Actor)
- `currentExecutingActor` (Actor, static)
- `asyncSenderContext` (StatefulActor)

`asyncSenderContext` is cleaned in finally blocks (good). `currentExecutingActor` may not be cleared in all error paths. With virtual threads running hundreds of instances, missed `.remove()` calls cause memory leaks.

### 8. MailboxProcessor Polling Timeout: 1ms
**File**: `lib/.../MailboxProcessor.java:24`

```java
// Performance tuning: reduced from 100ms to 1ms for lower latency
private static final long POLL_TIMEOUT_MS = 1;
```

Aggressive 1ms polling on idle actors wastes CPU cycles. Not configurable. Tradeoff between latency and CPU utilization is undocumented.

### 9. BackpressureManager Mixed Synchronization
**File**: `lib/.../backpressure/BackpressureManager.java`

Inconsistent synchronization: some fields use `synchronized`, others use `AtomicXXX`, some collections (`LinkedList`, `ArrayList`) are accessed both with and without synchronization. Risk: race conditions in edge cases, performance degradation.

### 10. Effect.sequence() Memory Risk with Large Lists
**File**: `lib/.../functional/Effect.java`

`sequence()` collects all results into a List. For large sequences (100K+ events), this creates memory pressure and potential OOM. No streaming alternative exists.

### 11. Complex Files with High Cyclomatic Complexity
| File | LOC | Risk |
|------|-----|------|
| `StatefulActor.java` | ~1200 | Async processing + persistence + state management mixed |
| `ActorSystem.java` | ~1200 | Multiple responsibilities |
| `BackpressureManager.java` | ~967 | Metrics + retries + state logic |
| `Effect.java` | ~931 | Large interface with many default methods |
| `NewEffectOperatorsTest.java` | ~1272 | Hard to maintain test file |

### 12. LMDB Configuration Risk
**Documented in**: `docs/persistence_guide.md`

- `MDB_MAP_FULL` error if mapSize under-configured (causes production outage)
- `MDB_READERS_FULL` if too many concurrent readers
- Worse performance on Windows due to memory-mapping differences
- No automatic size management

---

## Low Priority

### 13. Java Serialization for Persistence
**File**: `lib/.../StatefulActor.java:42-52`

State and message classes require `implements Serializable`. No runtime validation. `serialVersionUID` not enforced. Java serialization has known security risks. Consider migrating to JSON or Protobuf.

### 14. FunctionalActor Backward Compatibility Burden
**File**: `lib/.../FunctionalActor.java`

Legacy API with acknowledged backward-compat comments. Uses System.err + printStackTrace (see #3). No recent updates. Should be removed in next major version.

### 15. Missing Observability
- No Prometheus/Micrometer metrics export
- No distributed tracing hooks
- Backpressure state changes not easily observable externally
- Metrics registry logs to System.out (see #3)

### 16. ExecutorService Shutdown Completeness
**Files**: `StatefulActor.java` (persistenceExecutor), `BackpressureManager.java` (retryExecutor)

Thread pools may not be fully shut down on actor stop. Should verify all ExecutorServices have proper `postStop` cleanup.

### 17. Missing Operational Documentation
- No production deployment checklist
- No capacity planning guide
- No tuning guide for different workload types
- No troubleshooting guide
- Limited cluster mode documentation

---

## Resolved (Previously Critical)
From `docs/supervision_audit.md` (fixed Nov 27, 2025):
- ✅ Infinite loop prevention — processedCount incremented for failed messages
- ✅ Message loss prevention — unprocessed messages returned to mailbox before restart
- ✅ ConcurrentModificationException — deferred restart mechanism
- ✅ shouldReprocess bug in HandlerActor and StatefulHandlerActor
