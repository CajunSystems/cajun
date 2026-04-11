# Phase 28 Plan 1 — Reliability Hardening

## Objective
Add a per-node circuit breaker to `ReliableMessagingSystem`, exponential backoff with jitter to `EtcdMetadataStore`, an actor-assignment cache for graceful etcd-degradation in `ClusterActorSystem`, and improve error messages throughout cluster code.

## Execution Context

```
cajun-core/src/main/java/com/cajunsystems/cluster/NodeCircuitBreaker.java        — NEW
cajun-core/src/main/java/com/cajunsystems/cluster/CircuitBreakerOpenException.java — NEW
cajun-core/src/main/java/com/cajunsystems/cluster/ExponentialBackoff.java        — NEW
cajun-core/src/main/java/com/cajunsystems/cluster/ReliableMessagingSystem.java   — modify (circuit breaker)
lib/src/main/java/com/cajunsystems/cluster/ReliableMessagingSystem.java          — modify (circuit breaker — identical copy)
cajun-cluster/src/main/java/com/cajunsystems/cluster/impl/EtcdMetadataStore.java — modify (backoff retry)
lib/src/main/java/com/cajunsystems/cluster/ClusterActorSystem.java               — modify (cache + degraded routing)
```

## Key Constraints
- No external circuit-breaker library (Resilience4j etc.) — implement from scratch with AtomicInteger / volatile
- `cajun-cluster` depends on `cajun-core` (`api project(':cajun-core')`) — `EtcdMetadataStore` can import `ExponentialBackoff` from `cajun-core`
- Two copies of `ReliableMessagingSystem` exist (cajun-core and lib) — **both must be updated identically**
- Tests for circuit breaker and backoff must NOT require a live network — unit test only
- `./gradlew test` must stay green after all changes

## Tasks

### Task 1 — `NodeCircuitBreaker` and `CircuitBreakerOpenException`

**File 1**: `cajun-core/src/main/java/com/cajunsystems/cluster/NodeCircuitBreaker.java`

Thread-safe per-node circuit breaker with three states:

```java
package com.cajunsystems.cluster;

import java.util.concurrent.atomic.AtomicInteger;

public class NodeCircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final String nodeId;
    private final int failureThreshold;      // failures before opening (default 5)
    private final long resetTimeoutMs;        // ms to wait before probing (default 30_000)

    private volatile State state = State.CLOSED;
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private volatile long openedAt = 0;

    public NodeCircuitBreaker(String nodeId) {
        this(nodeId, 5, 30_000);
    }

    public NodeCircuitBreaker(String nodeId, int failureThreshold, long resetTimeoutMs) {
        this.nodeId = nodeId;
        this.failureThreshold = failureThreshold;
        this.resetTimeoutMs = resetTimeoutMs;
    }

    /**
     * Returns true if a call to the node is permitted.
     * CLOSED: always permitted.
     * OPEN: only permitted after resetTimeoutMs has elapsed (transitions to HALF_OPEN, allows one probe).
     * HALF_OPEN: permitted (already in probe state).
     */
    public synchronized boolean isCallPermitted() {
        return switch (state) {
            case CLOSED -> true;
            case HALF_OPEN -> true;
            case OPEN -> {
                if (System.currentTimeMillis() - openedAt >= resetTimeoutMs) {
                    state = State.HALF_OPEN;
                    yield true;
                }
                yield false;
            }
        };
    }

    /**
     * Record a successful call. Resets failure count and closes the circuit.
     */
    public synchronized void recordSuccess() {
        failureCount.set(0);
        state = State.CLOSED;
    }

    /**
     * Record a failed call. Opens the circuit when the failure threshold is reached.
     */
    public synchronized void recordFailure() {
        int count = failureCount.incrementAndGet();
        if (state == State.HALF_OPEN || count >= failureThreshold) {
            state = State.OPEN;
            openedAt = System.currentTimeMillis();
            failureCount.set(0);
        }
    }

    public State getState() { return state; }
    public String getNodeId() { return nodeId; }

    @Override
    public String toString() {
        return "NodeCircuitBreaker{nodeId='" + nodeId + "', state=" + state
                + ", failures=" + failureCount.get() + '}';
    }
}
```

**File 2**: `cajun-core/src/main/java/com/cajunsystems/cluster/CircuitBreakerOpenException.java`

```java
package com.cajunsystems.cluster;

public class CircuitBreakerOpenException extends RuntimeException {
    private final String nodeId;

    public CircuitBreakerOpenException(String nodeId) {
        super("Circuit breaker OPEN for node '" + nodeId + "' — call rejected");
        this.nodeId = nodeId;
    }

    public String getNodeId() { return nodeId; }
}
```

Compile check: `./gradlew :cajun-core:compileJava`

Commit: `feat(28-1): add NodeCircuitBreaker and CircuitBreakerOpenException`

---

### Task 2 — Wire circuit breaker into `ReliableMessagingSystem` (both copies)

Both files (`cajun-core/…/cluster/ReliableMessagingSystem.java` and `lib/…/cluster/ReliableMessagingSystem.java`) need identical changes:

**Imports to add:**
```java
import java.util.concurrent.ConcurrentHashMap;
// (ConcurrentHashMap may already be imported)
```

**New field** (after `nodeAddresses`):
```java
private final Map<String, NodeCircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();
```

**New helper method:**
```java
private NodeCircuitBreaker getCircuitBreaker(String nodeId) {
    return circuitBreakers.computeIfAbsent(nodeId, NodeCircuitBreaker::new);
}
```

**In `doSendMessage()`** — add circuit breaker check at the top of the method body and wrap the existing logic:
```java
// Circuit breaker check — before attempting socket connect
NodeCircuitBreaker cb = getCircuitBreaker(targetSystemId);
if (!cb.isCallPermitted()) {
    if (clusterMetrics != null) clusterMetrics.incrementRemoteMessageFailures();
    throw new CircuitBreakerOpenException(targetSystemId);
}

// existing try block:
try (Socket socket = new Socket()) {
    socket.connect(new InetSocketAddress(address.host, address.port), 5000);
    // ... existing logic ...
    cb.recordSuccess();   // ← add BEFORE closing brace of try block
} catch (IOException e) {
    cb.recordFailure();   // ← add to IOException catch, before re-throw or logging
    if (clusterMetrics != null) clusterMetrics.incrementRemoteMessageFailures();
    throw e;
}
```

Note: The existing `doSendMessage()` uses `try (Socket socket...)` — add `cb.recordSuccess()` after the ack-wait block (on successful path) and `cb.recordFailure()` in the catch block. Read the exact code to place correctly.

**In `sendMessage()` lambda** — improve the error log at `catch (Exception e)` (line ~199):
```java
} catch (Exception e) {
    if (e instanceof CircuitBreakerOpenException cbEx) {
        logger.warn("Circuit breaker OPEN — dropping message to actor '{}' on node '{}': {}",
                actorId, targetSystemId, cbEx.getMessage());
    } else {
        logger.error("Failed to send message to actor '{}' on node '{}' ({}:{}): {}",
                actorId, targetSystemId, address.host, address.port, e.getMessage(), e);
    }
    throw new RuntimeException("Failed to send message to " + targetSystemId + "/" + actorId, e);
}
```

**Add accessor** (at end of class):
```java
public NodeCircuitBreaker getCircuitBreaker(String nodeId) {
    return circuitBreakers.get(nodeId);  // returns null if never contacted
}
```
Wait — this conflicts with the private helper above. Rename the private helper to `getOrCreateCircuitBreaker(nodeId)` and add a public `getCircuitBreaker(nodeId)` that returns `circuitBreakers.get(nodeId)`.

Compile check: `./gradlew :cajun-core:compileJava :lib:compileJava`

Commit: `feat(28-1): wire per-node circuit breaker into ReliableMessagingSystem`

---

### Task 3 — `ExponentialBackoff` utility + wire into `EtcdMetadataStore`

**File**: `cajun-core/src/main/java/com/cajunsystems/cluster/ExponentialBackoff.java`

```java
package com.cajunsystems.cluster;

import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class ExponentialBackoff {

    private final long initialDelayMs;
    private final long maxDelayMs;
    private final int maxAttempts;
    private final double jitterFactor;  // 0.0 = no jitter, 0.25 = ±25%
    private final Random random = new Random();

    public ExponentialBackoff(long initialDelayMs, long maxDelayMs, int maxAttempts, double jitterFactor) {
        this.initialDelayMs = initialDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.maxAttempts = maxAttempts;
        this.jitterFactor = jitterFactor;
    }

    /** Returns delay in ms for a given attempt (0-based). */
    public long delayForAttempt(int attempt) {
        long base = Math.min(initialDelayMs * (1L << attempt), maxDelayMs);
        long jitter = (long) (base * jitterFactor * (random.nextDouble() * 2 - 1));
        return Math.max(0, base + jitter);
    }

    /**
     * Wraps a CompletableFuture supplier with exponential-backoff retry.
     * Retries only on exception (any Throwable in the future's failure chain).
     */
    public <T> CompletableFuture<T> withRetry(
            Supplier<CompletableFuture<T>> operation,
            ScheduledExecutorService scheduler) {
        return attempt(operation, scheduler, 0);
    }

    private <T> CompletableFuture<T> attempt(
            Supplier<CompletableFuture<T>> op,
            ScheduledExecutorService scheduler,
            int attemptNumber) {
        return op.get().exceptionallyCompose(ex -> {
            if (attemptNumber >= maxAttempts - 1) {
                return CompletableFuture.failedFuture(ex);
            }
            long delay = delayForAttempt(attemptNumber);
            CompletableFuture<T> future = new CompletableFuture<>();
            scheduler.schedule(() ->
                attempt(op, scheduler, attemptNumber + 1)
                    .whenComplete((r, e) -> {
                        if (e != null) future.completeExceptionally(e);
                        else future.complete(r);
                    }),
                delay, TimeUnit.MILLISECONDS);
            return future;
        });
    }
}
```

**Wire into `EtcdMetadataStore`** (`cajun-cluster/src/main/java/com/cajunsystems/cluster/impl/EtcdMetadataStore.java`):

1. Add import: `import com.cajunsystems.cluster.ExponentialBackoff;`
2. Add field: `private final ExponentialBackoff backoff = new ExponentialBackoff(100, 30_000, 5, 0.25);`
3. Wrap `put()`, `get()`, `delete()`, `listKeys()` with `backoff.withRetry(() -> <original impl>, scheduler)`

Example for `get()`:
```java
@Override
public CompletableFuture<Optional<String>> get(String key) {
    return backoff.withRetry(() -> {
        ByteSequence keyBytes = ByteSequence.from(key, StandardCharsets.UTF_8);
        return client.getKVClient().get(keyBytes)
                .thenApply(getResponse -> {
                    if (getResponse.getKvs().isEmpty()) return Optional.empty();
                    return Optional.of(getResponse.getKvs().get(0).getValue().toString(StandardCharsets.UTF_8));
                });
    }, scheduler);
}
```

Do NOT wrap `acquireLock()` (lock acquisition must not auto-retry), `watch()` or `unwatch()` (streaming, not request/response), or `connect()` / `close()`.

Compile check: `./gradlew :cajun-core:compileJava :cajun-cluster:compileJava`

Commit: `feat(28-1): add ExponentialBackoff and wire retry into EtcdMetadataStore`

---

### Task 4 — Actor assignment cache for graceful etcd degradation

**File**: `lib/src/main/java/com/cajunsystems/cluster/ClusterActorSystem.java`

**New field** (after `knownNodes`):
```java
// Local cache of actor→node assignments; used as fallback when metadataStore is unreachable
private final Map<String, String> actorAssignmentCache = new ConcurrentHashMap<>();
```

**In `routeMessage()`** — the `metadataStore.get()` call already has a `thenAccept` handler. Wrap it to:
1. On success: update cache before routing
2. On exception: fall back to cache

Modify the routing block around lines 255-300:

```java
metadataStore.get(ACTOR_ASSIGNMENT_PREFIX + actorId)
    .thenAccept(optionalNodeId -> {
        if (optionalNodeId.isPresent()) {
            String nodeId = optionalNodeId.get();
            actorAssignmentCache.put(actorId, nodeId);  // ← update cache
            // ... existing routing logic unchanged ...
        } else {
            logger.warn("Actor '{}' not found in metadata store — not registered in cluster", actorId);
        }
    })
    .exceptionally(ex -> {
        // Metadata store unreachable — attempt degraded routing from cache
        String cachedNodeId = actorAssignmentCache.get(actorId);
        if (cachedNodeId != null) {
            logger.warn("Metadata store unreachable for actor '{}' — using cached assignment to node '{}' (degraded mode)",
                    actorId, cachedNodeId);
            // Re-use the same routing logic: local check first, then remote send
            Actor<Message> localActor = (Actor<Message>) getActor(new Pid(actorId, this));
            if (localActor != null) {
                clusterMetrics.incrementLocalMessagesRouted();
                localActor.tell(message);
            } else if (!cachedNodeId.equals(systemId)) {
                long routeStart = System.nanoTime();
                clusterMetrics.incrementRemoteMessagesRouted();
                messagingSystem.sendMessage(cachedNodeId, actorId, message)
                    .thenRun(() -> clusterMetrics.recordRoutingLatency(System.nanoTime() - routeStart))
                    .exceptionally(sendEx -> {
                        clusterMetrics.incrementRemoteMessageFailures();
                        logger.error("Degraded-mode send failed for actor '{}' on node '{}': {}",
                                actorId, cachedNodeId, sendEx.getMessage());
                        return null;
                    });
            }
        } else {
            logger.error("Metadata store unreachable and no cached assignment for actor '{}' — message dropped", actorId);
        }
        return null;
    });
```

Also add a public accessor for testing:
```java
public Map<String, String> getActorAssignmentCache() {
    return Collections.unmodifiableMap(actorAssignmentCache);
}
```
(Add `import java.util.Collections;` if not present.)

Compile check: `./gradlew :lib:compileJava`

Commit: `feat(28-1): add actor-assignment cache for graceful etcd degradation`

---

### Task 5 — Improve error messages throughout cluster code

Scan and update error/warn log calls in these files to include full context:

**`cajun-core/…/cluster/ReliableMessagingSystem.java`** and **`lib/…/cluster/ReliableMessagingSystem.java`**:
- `retrySendMessage()` line ~219: currently `"Cannot retry message to unknown system: {}"` → add actorId: `"Cannot retry message {} to unknown system '{}' for actor '{}'"` (messageId, targetSystemId, actorId)
- `retrySendMessage()` catch line ~228: `"Failed to retry message to {}:{}"` → `"Failed to retry message {} to actor '{}' on node '{}' ({}:{}): {}"` (messageId, actorId, targetSystemId, host, port, message)
- `handleClient()` error line ~384: `"Error handling client connection"` → `"Error handling client connection from {}:{}"` (clientSocket.getInetAddress(), clientSocket.getPort())

**`cajun-core/…/cluster/MessageTracker.java`**:
- Line ~150 warn: `"Message {} to {}/{} failed after {} retries"` — already has good context; add failure reason if accessible (it won't be here — leave as is)

**`lib/…/cluster/ClusterActorSystem.java`**:
- Line ~294: `"Actor {} is registered to this node but not found locally"` → `"Actor '{}' is registered to node '{}' (this node) but not found in local actor registry — it may still be initializing"` (actorId, systemId)

Compile check: `./gradlew :cajun-core:compileJava :lib:compileJava`

Commit: `refactor(28-1): improve error messages in cluster code with full actor/node context`

---

### Task 6 — Tests

**`lib/src/test/java/com/cajunsystems/cluster/NodeCircuitBreakerTest.java`**:

Tests (all unit — no network):
- `testInitialStateClosed()` — new breaker → state=CLOSED, isCallPermitted=true
- `testOpensAfterFailureThreshold()` — record 5 failures → state=OPEN, isCallPermitted=false
- `testDoesNotOpenBeforeThreshold()` — record 4 failures → state=CLOSED
- `testHalfOpenAfterResetTimeout()` — open breaker, mock time past resetTimeout → isCallPermitted=true, state=HALF_OPEN
  - Use constructor with small resetTimeoutMs (e.g., 50ms) + Thread.sleep(60ms) to trigger reset
- `testClosesOnSuccessFromHalfOpen()` — open → wait → HALF_OPEN → recordSuccess() → CLOSED, failureCount=0
- `testReopensOnFailureFromHalfOpen()` — open → wait → HALF_OPEN → recordFailure() → OPEN again
- `testCircuitBreakerOpenExceptionMessage()` — exception message contains nodeId

**`lib/src/test/java/com/cajunsystems/cluster/ExponentialBackoffTest.java`**:

Tests:
- `testDelayForAttemptZeroEqualsInitialDelay()` — attempt 0 → delay ≈ initialDelayMs (within jitter range)
- `testDelayGrowsExponentially()` — attempt 0 < attempt 1 < attempt 2 (with jitterFactor=0 for determinism)
- `testDelayCapAtMaxDelay()` — large attempt number → delay <= maxDelayMs
- `testNoJitterWhenFactorIsZero()` — jitterFactor=0 → delay = exactly initialDelayMs * 2^attempt (capped at max)
- `testWithRetrySucceedsOnFirstAttempt()` — supplier always succeeds → completes immediately, no retry
- `testWithRetryRetriesOnFailure()` — supplier fails twice then succeeds → result is success; use AtomicInteger to count calls
  - Use `ScheduledExecutorService` with `Executors.newSingleThreadScheduledExecutor()` for async retry
- `testWithRetryExhaustsMaxAttempts()` — supplier always fails → future completes exceptionally after maxAttempts

**`lib/src/test/java/com/cajunsystems/cluster/DegradedRoutingTest.java`**:

Verify actor assignment cache + graceful degradation:
- `testCacheUpdatedOnSuccessfulLookup()` — route a message normally → `getActorAssignmentCache()` contains the actorId→nodeId mapping
- `testDegradedRoutingUsesCache()` — pre-populate cache manually, make metadataStore fail → message still routed (verify via mock messagingSystem.sendMessage call count)
  - Use `InMemoryMetadataStore` subclass that throws on `get()`, or an anonymous `MetadataStore` wrapper

  Implementation note: `InMemoryMetadataStore` is defined inline in test files as a private class. In `DegradedRoutingTest`, write an inline `FailingMetadataStore` that throws on `get()` but succeeds on other operations (needed for `ClusterActorSystem` startup).

Compile check: `./gradlew :lib:compileTestJava`

Commit: `test(28-1): unit tests for circuit breaker, exponential backoff, and degraded routing`

---

## Checkpoint: Verify after Task 6
```bash
./gradlew test
```
Expected: BUILD SUCCESSFUL. All non-`requires-redis`/non-`requires-etcd`/non-`performance` tests pass.

## Success Criteria
- [ ] `NodeCircuitBreaker` (3 states: CLOSED/OPEN/HALF_OPEN) implemented and wired into both `ReliableMessagingSystem` copies
- [ ] `CircuitBreakerOpenException` with nodeId
- [ ] `ExponentialBackoff` utility with jitter; wired into `EtcdMetadataStore` `put/get/delete/listKeys`
- [ ] Actor assignment cache in `ClusterActorSystem`; `routeMessage()` uses cache on metadata store failure
- [ ] Error messages improved in `ReliableMessagingSystem` and `ClusterActorSystem`
- [ ] Unit tests: `NodeCircuitBreakerTest` (7 tests), `ExponentialBackoffTest` (7 tests), `DegradedRoutingTest` (2 tests)
- [ ] `./gradlew test` green

## Output
New files:
- `cajun-core/src/main/java/com/cajunsystems/cluster/NodeCircuitBreaker.java`
- `cajun-core/src/main/java/com/cajunsystems/cluster/CircuitBreakerOpenException.java`
- `cajun-core/src/main/java/com/cajunsystems/cluster/ExponentialBackoff.java`
- `lib/src/test/java/com/cajunsystems/cluster/NodeCircuitBreakerTest.java`
- `lib/src/test/java/com/cajunsystems/cluster/ExponentialBackoffTest.java`
- `lib/src/test/java/com/cajunsystems/cluster/DegradedRoutingTest.java`

Modified files:
- `cajun-core/src/main/java/com/cajunsystems/cluster/ReliableMessagingSystem.java`
- `lib/src/main/java/com/cajunsystems/cluster/ReliableMessagingSystem.java`
- `cajun-cluster/src/main/java/com/cajunsystems/cluster/impl/EtcdMetadataStore.java`
- `lib/src/main/java/com/cajunsystems/cluster/ClusterActorSystem.java`
