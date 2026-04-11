---
plan: 29-2
phase: 29 — Performance Optimization
title: Code Review P1 Fixes
type: fix
status: complete
commit: 159fc2c
---

## Objective

Address three P1 security/correctness issues identified in the Greptile code review covering Phases 27–29.

## Tasks

### Task 1 — Fix double-counted remote message failures [fix]

**File**: `lib/src/main/java/com/cajunsystems/cluster/ClusterActorSystem.java`

**Problem**: `routeToNode().exceptionally()` and `ReliableMessagingSystem.doSendMessage()` both call `clusterMetrics.incrementRemoteMessageFailures()` on the same transport failure because `ClusterActorSystem` injects the same `ClusterMetrics` instance into `ReliableMessagingSystem` via `rms.setClusterMetrics(clusterMetrics)`.

**Fix**: In `routeToNode().exceptionally()`, guard the increment:
```java
if (!(messagingSystem instanceof ReliableMessagingSystem)) {
    clusterMetrics.incrementRemoteMessageFailures();
}
```
`doSendMessage()` remains the canonical counter for `ReliableMessagingSystem` paths.

---

### Task 2 — Catch SerializationException in handleClient() [fix]

**Files**:
- `cajun-core/src/main/java/com/cajunsystems/cluster/ReliableMessagingSystem.java`
- `lib/src/main/java/com/cajunsystems/cluster/ReliableMessagingSystem.java`

**Problem**: `handleClient()` only caught `IOException`. `SerializationException extends RuntimeException` was silently swallowed by the executor's uncaught handler, leaving no log trail for deserialization failures.

**Fix**: Add explicit catch before `IOException`:
```java
} catch (SerializationException e) {
    logger.error("Deserialization/serialization error for message from {}:{}: {}",
            clientSocket.getInetAddress(), clientSocket.getPort(), e.getMessage(), e);
} catch (IOException e) { ... }
```

---

### Task 3 — Fix Jackson RCE in JsonSerializationProvider [fix]

**File**: `lib/src/main/java/com/cajunsystems/serialization/JsonSerializationProvider.java`

**Problem**: `allowIfBaseType(Object.class)` + `DefaultTyping.EVERYTHING` is functionally equivalent to the deprecated `enableDefaultTyping()`, allowing deserialization of arbitrary gadget classes (RCE).

**Fix**: Replace with configurable trusted package prefixes + `NON_FINAL`:
- New constructor `JsonSerializationProvider(String... trustedPackagePrefixes)` adds each prefix via `allowIfSubType(pkg)`
- Default `INSTANCE` trusts `com.cajunsystems.`, `java.`, `javax.` only
- Users with message types in other packages construct a custom instance

## Verification

- `./gradlew :cajun-core:compileJava :lib:compileJava` — BUILD SUCCESSFUL
- `./gradlew test` — BUILD SUCCESSFUL (629 tests, 0 failures)
