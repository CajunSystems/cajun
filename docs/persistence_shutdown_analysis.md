# Persistence Shutdown & Lock Contention Analysis

## Executive Summary

**CRITICAL ISSUES FOUND:**
1. ❌ **Blocking shutdown in StatefulActor** - `takeSnapshot().join()` blocks indefinitely
2. ❌ **Lock contention between operations** - Snapshot, prune, and truncate compete for same lock
3. ❌ **Sequential chaining causes cascading delays** - Operations wait for each other in sequence
4. ⚠️ **No isolation between snapshot and cleanup** - They share the same lock and executor

---

## Problem 1: Blocking Shutdown (CRITICAL)

### Location
`/lib/src/main/java/com/cajunsystems/StatefulActor.java:417`

### Current Code
```java
protected void postStop() {
    if (stateInitialized && currentState.get() != null) {
        if (stateChanged) {
            logger.debug("Taking final snapshot for actor {} before shutdown", actorId);
            try {
                takeSnapshot().get(1, TimeUnit.SECONDS);  // ← BLOCKS for 1 second!
            } catch (TimeoutException e) {
                logger.warn("Timeout waiting for final snapshot for actor {}, skipping", actorId);
            }
        }
    }
}
```

### Issues
- **Blocks shutdown thread** waiting for snapshot to complete
- If snapshot is waiting for lock (held by prune), this waits the full timeout
- **1 second timeout** is still too long for benchmarks
- **No cancellation** of in-flight operations

### Impact
- Benchmarks hang for 1 second during shutdown
- JMH times out and kills the VM
- Cannot achieve fast shutdown

---

## Problem 2: Lock Contention (CRITICAL)

### Location
`/persistence/src/main/java/com/cajunsystems/persistence/runtime/persistence/FileSnapshotStore.java`

### Current Architecture
```
Actor Thread                    IO Executor Thread
    |                                  |
    | takeSnapshot()                   |
    |--------------------------------->|
    |                                  | tryLock(500ms) ← WAITING
    |                                  |
    | (meanwhile)                      |
    | pruneOldSnapshots()              |
    |--------------------------------->|
    |                                  | tryLock(100ms) ← BLOCKED by saveSnapshot
    |                                  |
    | shutdown()                       |
    | get(1s timeout)                  |
    | ← WAITING ← WAITING ← WAITING    |
```

### Issues
1. **Same lock for all operations**:
   - `saveSnapshot()` - 500ms timeout
   - `deleteSnapshots()` - 100ms timeout  
   - `pruneOldSnapshots()` - 100ms timeout

2. **Sequential execution** via `thenCompose()`:
   ```java
   saveSnapshot()
     .thenCompose(v -> truncateJournal())    // ← Waits for snapshot
     .thenCompose(v -> pruneOldSnapshots())  // ← Waits for truncate
   ```

3. **Lock held during I/O**:
   - Lock acquired before file operations
   - Held during serialization, write, fsync
   - Released only after completion

### Impact
- Operations serialize even though they could be parallel
- Cleanup operations timeout waiting for snapshot
- Shutdown hangs waiting for cleanup

---

## Problem 3: No Operation Isolation

### Current Design
All operations share:
1. **Same lock** (`actorLocks.get(actorId)`)
2. **Same executor** (`ioExecutor`)
3. **Sequential chaining** (`.thenCompose()`)

### What Should Happen
```
SNAPSHOT PATH (Critical - must complete)
  ├─ Write snapshot file
  └─ Return immediately

CLEANUP PATH (Best-effort - can be skipped)
  ├─ Truncate journal (independent)
  └─ Prune snapshots (independent)
```

### What Actually Happens
```
SINGLE PATH (All or nothing)
  ├─ Write snapshot file (holds lock)
  ├─ Wait for truncate (holds lock)
  └─ Wait for prune (holds lock)
```

---

## Problem 4: Cascading Timeouts

### Scenario
```
Time 0ms:   saveSnapshot() starts, acquires lock
Time 50ms:  pruneOldSnapshots() called (from previous snapshot)
Time 50ms:  prune tries tryLock(100ms) - BLOCKED
Time 150ms: prune times out, skips
Time 200ms: saveSnapshot() completes, releases lock
Time 200ms: Actor shutdown called
Time 200ms: takeSnapshot().get(1000ms) called
Time 200ms: New snapshot tries tryLock(500ms)
Time 200ms: Lock available, snapshot starts
Time 300ms: Snapshot writing...
Time 400ms: Snapshot completes
Time 400ms: Shutdown continues
```

**Total delay: 400ms minimum, up to 1000ms if contention**

---

## Recommended Solutions

### Solution 1: Remove Blocking from Shutdown (IMMEDIATE)

**Change:**
```java
protected void postStop() {
    // DON'T WAIT for snapshot - let it complete async
    if (stateInitialized && currentState.get() != null && stateChanged) {
        logger.debug("Triggering final snapshot for actor {} (async)", actorId);
        takeSnapshot(); // Fire and forget
    }
    
    // Shutdown persistence executor immediately
    persistenceExecutor.shutdown();
}
```

**Benefits:**
- ✅ Instant shutdown
- ✅ No blocking
- ✅ Snapshot completes in background if possible
- ✅ If snapshot fails, it's logged but doesn't block

**Tradeoffs:**
- ⚠️ Final snapshot might not complete if JVM exits immediately
- ✅ Acceptable for benchmarks (state is ephemeral)
- ✅ Production can use shutdown hooks if needed

---

### Solution 2: Separate Locks for Different Operations

**Change:**
```java
private final ConcurrentHashMap<String, ReentrantLock> snapshotLocks = new ConcurrentHashMap<>();
private final ConcurrentHashMap<String, ReentrantLock> cleanupLocks = new ConcurrentHashMap<>();

// In saveSnapshot()
ReentrantLock lock = snapshotLocks.computeIfAbsent(actorId, k -> new ReentrantLock());

// In pruneOldSnapshots()
ReentrantLock lock = cleanupLocks.computeIfAbsent(actorId, k -> new ReentrantLock());
```

**Benefits:**
- ✅ Snapshot and cleanup don't block each other
- ✅ Multiple cleanup operations can run concurrently
- ✅ Shutdown only waits for critical path (snapshot)

---

### Solution 3: Parallel Cleanup Operations

**Change:**
```java
private CompletableFuture<Void> takeSnapshot() {
    State state = currentState.get();
    long sequence = lastProcessedSequence.get();
    
    // Save snapshot (critical path)
    CompletableFuture<Void> snapshotFuture = snapshotStore.saveSnapshot(actorId, state, sequence);
    
    // Cleanup operations (parallel, best-effort)
    CompletableFuture<Void> truncateFuture = CompletableFuture.completedFuture(null);
    CompletableFuture<Void> pruneFuture = CompletableFuture.completedFuture(null);
    
    if (truncationConfig.isTruncateJournalOnSnapshot()) {
        truncateFuture = messageJournal.truncateBefore(actorId, sequence)
            .exceptionally(e -> {
                logger.warn("Truncate failed (non-critical): {}", e.getMessage());
                return null;
            });
    }
    
    if (truncationConfig.isSnapshotBasedTruncationEnabled()) {
        pruneFuture = snapshotStore.pruneOldSnapshots(actorId, truncationConfig.getSnapshotsToKeep())
            .exceptionally(e -> {
                logger.warn("Prune failed (non-critical): {}", e.getMessage());
                return null;
            });
    }
    
    // Return immediately after snapshot, cleanup continues async
    return snapshotFuture;
}
```

**Benefits:**
- ✅ Snapshot completes immediately
- ✅ Cleanup runs in parallel
- ✅ Cleanup failures don't affect snapshot
- ✅ Fast shutdown (only waits for snapshot)

---

### Solution 4: Use Lock-Free Approach (ADVANCED)

**Change:**
```java
// Instead of locks, use atomic operations and file system guarantees
private CompletableFuture<Void> saveSnapshot(String actorId, S state, long sequenceNumber) {
    return CompletableFuture.runAsync(() -> {
        // Write to unique temp file (no lock needed)
        String tempFileName = String.format("snapshot_%020d_%s_%s.tmp", 
            sequenceNumber, System.currentTimeMillis(), UUID.randomUUID());
        Path tempFile = actorSnapshotDir.resolve(tempFileName);
        
        // Write data
        writeSnapshotData(tempFile, state);
        
        // Atomic rename (filesystem guarantees atomicity)
        String finalFileName = String.format("snapshot_%020d_%s.snap", 
            sequenceNumber, System.currentTimeMillis());
        Files.move(tempFile, actorSnapshotDir.resolve(finalFileName), 
            StandardCopyOption.ATOMIC_MOVE);
        
        // No locks needed!
    }, ioExecutor);
}
```

**Benefits:**
- ✅ No lock contention
- ✅ Truly parallel operations
- ✅ Filesystem handles atomicity
- ✅ Instant shutdown

---

## Recommended Implementation Order

### Phase 1: Immediate Fixes (< 1 hour)
1. ✅ **Remove blocking from shutdown** - Change `get(1s)` to fire-and-forget
2. ✅ **Reduce timeouts** - Already done (500ms/100ms)

### Phase 2: Isolation (< 2 hours)
3. **Separate locks** - Different locks for snapshot vs cleanup
4. **Parallel cleanup** - Run truncate and prune in parallel

### Phase 3: Lock-Free (Future)
5. **Remove locks entirely** - Use filesystem atomicity guarantees

---

## Testing Strategy

### Test 1: Fast Shutdown
```java
@Test
void testFastShutdown() {
    StatefulActor actor = createActor();
    actor.tell(new Message()); // Trigger snapshot
    
    long start = System.currentTimeMillis();
    actor.stop();
    long duration = System.currentTimeMillis() - start;
    
    assertThat(duration).isLessThan(100); // Should be instant
}
```

### Test 2: Concurrent Operations
```java
@Test
void testConcurrentSnapshotAndPrune() {
    // Start snapshot
    CompletableFuture<Void> snapshot = actor.takeSnapshot();
    
    // Start prune immediately (should not block)
    CompletableFuture<Integer> prune = snapshotStore.pruneOldSnapshots(actorId, 2);
    
    // Both should complete without timeout
    CompletableFuture.allOf(snapshot, prune).get(1, TimeUnit.SECONDS);
}
```

### Test 3: Benchmark Shutdown
```java
@Benchmark
public void benchmarkWithShutdown() {
    // Run operations
    for (int i = 0; i < 1000; i++) {
        actor.tell(new Message());
    }
    
    // Shutdown should be instant
    actor.stop(); // Should not hang
}
```

---

## Metrics to Track

1. **Shutdown Duration**: Should be < 50ms
2. **Lock Wait Time**: Should be 0ms (no contention)
3. **Snapshot Completion Rate**: Should be 100% (no timeouts)
4. **Cleanup Completion Rate**: Can be < 100% (best-effort)

---

## Conclusion

The current persistence implementation has **critical blocking issues** that prevent fast shutdown:

1. **Blocking wait** in `postStop()` - MUST be removed
2. **Lock contention** between operations - SHOULD be separated
3. **Sequential execution** of cleanup - SHOULD be parallel

**Immediate action required**: Remove the blocking `get()` call from `postStop()` to enable instant shutdown.
