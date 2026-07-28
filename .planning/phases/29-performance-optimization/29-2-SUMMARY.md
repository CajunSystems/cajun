---
plan: 29-2
phase: 29 — Performance Optimization
title: Code Review P1 Fixes
status: complete
commit: 159fc2c
---

## What Was Done

Fixed three P1 issues identified in a Greptile code review of Phases 27–29.

### Fix 1 — Double-counted remote message failures (bug)

`ClusterActorSystem.routeToNode().exceptionally()` and `ReliableMessagingSystem.doSendMessage()` shared the same `ClusterMetrics` instance (injected via `rms.setClusterMetrics()`), causing every transport failure to increment `remoteMessageFailures` twice.

Resolution: `routeToNode()` now skips the increment when `messagingSystem instanceof ReliableMessagingSystem` — `doSendMessage()` owns the count for that path.

### Fix 2 — Silent SerializationException in handleClient() (bug)

`SerializationException extends RuntimeException` was uncaught by the `IOException` catch in `handleClient()`, letting deserialization failures vanish into the executor's uncaught handler with no log entry.

Resolution: Added explicit `catch (SerializationException e)` before `catch (IOException e)` in both `ReliableMessagingSystem` copies (`cajun-core` and `lib`).

### Fix 3 — Jackson polymorphic deserialization RCE (security)

`allowIfBaseType(Object.class)` + `DefaultTyping.EVERYTHING` was functionally equivalent to `enableDefaultTyping()` — allows arbitrary class instantiation via crafted JSON (gadget chain RCE).

Resolution: `JsonSerializationProvider` rewritten with configurable trusted package prefixes and `DefaultTyping.NON_FINAL`. Default `INSTANCE` allows only `com.cajunsystems.*`, `java.*`, `javax.*`. Users add their own package prefixes by constructing a custom instance.

## Test Results

- Compile: `./gradlew :cajun-core:compileJava :lib:compileJava` — BUILD SUCCESSFUL
- Tests: `./gradlew test` — BUILD SUCCESSFUL (all passing)

## Commit

`159fc2c` — fix(cluster): address three P1 code review findings
