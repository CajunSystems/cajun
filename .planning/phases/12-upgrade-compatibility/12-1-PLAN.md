<objective>
Upgrade Cajun's Roux dependency from v0.1.0 to v0.2.1, fix the ActorEffectRuntime.close()
executor-ownership bug introduced by DefaultEffectRuntime becoming AutoCloseable, and verify
the full test suite stays green.

Roux v0.2.0 made DefaultEffectRuntime implement AutoCloseable. Its close() calls
executor.shutdown(). ActorEffectRuntime passes the ActorSystem's executor to super() —
if close() is not overridden, calling it (or using try-with-resources) would terminate
actor execution. Fix: override close() as a documented no-op.

No Tuple2/Tuple3 migration needed — Cajun does not use these types.
</objective>

<execution_context>
- Bridge code: `lib/src/main/java/com/cajunsystems/functional/`
  - `ActorEffectRuntime.java` — extends DefaultEffectRuntime; needs close() override
  - `EffectActorBuilder.java` — spawns effect actors; creates ActorEffectRuntime instances
  - `ActorSystemEffectExtensions.java` — static helpers; no runtime lifecycle
  - `capabilities/LogCapability.java` — sealed Capability interface; no changes expected
  - `capabilities/ConsoleLogHandler.java` — CapabilityHandler impl; no changes expected
- Version catalog: `gradle/libs.versions.toml` — `roux = "0.1.0"` to change
- All effect examples in `lib/src/test/java/examples/` — should compile unchanged
- Current test count: 362 (all passing except pre-existing ClusterModeTest flakiness)
</execution_context>

<context>
## Roux v0.2.0/v0.2.1 breaking changes relevant to Cajun

| Change | Impact | Action |
|--------|--------|--------|
| `DefaultEffectRuntime` implements `AutoCloseable` | `close()` shuts down executor — dangerous if executor belongs to ActorSystem | Override `close()` as no-op in `ActorEffectRuntime` |
| Capability handler composition uses `UnsupportedOperationException` for unmatched caps | Was previously undefined behavior | No change needed — our tests properly wire all capabilities |
| `Tuple2/Tuple3` accessor rename (`_1()/_2()` → `first()/second()`) | Breaking rename | No impact — Cajun does not use Tuple2/Tuple3 |
| `Either` gains `map/flatMap/fold/swap` | Additive | No action |
| v0.2.1: scoped fork inherits ExecutionContext | Correctness fix for capability propagation into forks | No code change needed; verify in Phase 13 tests |

## DefaultEffectRuntime.close() implementation (v0.2.1)
```java
@Override
public void close() {
    executor.shutdown();
    try {
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
            executor.shutdownNow();
        }
    } catch (InterruptedException e) {
        executor.shutdownNow();
        Thread.currentThread().interrupt();
    }
}
```
This MUST NOT run with the ActorSystem's executor.

## ActorEffectRuntime current constructor
```java
public ActorEffectRuntime(ActorSystem system) {
    super(resolveExecutor(system), true);
    // true = useTrampoline (stack safety) — correct, keep as-is
}
```
The `true` parameter is `useTrampoline`, not executor ownership — no change needed there.

## Pre-existing known failures
- `ClusterModeTest.testRemoteActorCommunication` — requires etcd, not our issue
</context>

<tasks>

## Task 1 — Bump Roux version

**File**: `gradle/libs.versions.toml`

Change:
```toml
roux = "0.1.0"
```
To:
```toml
roux = "0.2.1"
```

Run build to check compilation:
```bash
./gradlew compileJava compileTestJava --rerun-tasks
```

Fix any compile errors before proceeding. Expected: clean compile with no errors.
If there are compile errors, document them and fix.

---

## Task 2 — Override ActorEffectRuntime.close()

**File**: `lib/src/main/java/com/cajunsystems/functional/ActorEffectRuntime.java`

Add a `close()` override after the constructor:

```java
/**
 * No-op override. The {@link ExecutorService} passed to this runtime is owned by
 * the {@link ActorSystem}, not by this runtime. Shutting it down here would
 * terminate actor execution for the entire system.
 *
 * <p>Lifecycle cleanup is managed by {@link ActorSystem#shutdown()}.
 */
@Override
public void close() {
    // Intentional no-op: executor lifecycle belongs to ActorSystem.
}
```

Do not change anything else in this file.

---

## Task 3 — Run full test suite

```bash
./gradlew test
```

Expected outcome:
- All existing tests pass (362+ tests)
- `ClusterModeTest.testRemoteActorCommunication` may fail — this is pre-existing and unrelated
- No new failures introduced by the upgrade

If new failures appear:
1. Read the failure message carefully
2. Check if it's a Roux API change (look at the stack trace for roux package references)
3. Fix the minimal change needed — do not refactor surrounding code
4. Document the failure and fix in the SUMMARY

</tasks>

<verification>
- [ ] `gradle/libs.versions.toml` shows `roux = "0.2.1"`
- [ ] `./gradlew compileJava compileTestJava` succeeds with no errors
- [ ] `ActorEffectRuntime.close()` is overridden with no-op + Javadoc explaining why
- [ ] `./gradlew test` succeeds — same or more tests pass than before (≥362)
- [ ] Only pre-existing `ClusterModeTest` failure present (if any)
- [ ] No other bridge files were changed unless strictly required to fix a compile error
</verification>

<success_criteria>
- Roux dependency is v0.2.1 in the version catalog
- `ActorEffectRuntime` cannot accidentally shut down the actor system executor
- Full test suite green (362+ tests, no new failures)
- Build output shows no deprecation warnings related to our bridge code
</success_criteria>

<output>
- Modified: `gradle/libs.versions.toml`
- Modified: `lib/src/main/java/com/cajunsystems/functional/ActorEffectRuntime.java`
- Possibly modified: other bridge/example files if compile errors require fixes (document any)
- Create: `.planning/phases/12-upgrade-compatibility/12-1-SUMMARY.md`
</output>
