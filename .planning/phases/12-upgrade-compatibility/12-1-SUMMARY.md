# Phase 12, Plan 1 Summary: Upgrade & Compatibility

## Status: Complete

## Tasks Completed

### Task 1 — Bump Roux version to 0.2.1
**Commit**: 360df3e
- Changed `roux = "0.1.0"` → `roux = "0.2.1"` in `gradle/libs.versions.toml`
- Compiled cleanly on first attempt — no compile errors from the version bump
- Only pre-existing deprecation warnings (ResizableMailboxConfig, unrelated to Roux)

### Task 2 — Override ActorEffectRuntime.close() as no-op
**Commit**: 11cac29
- Added `close()` override to `ActorEffectRuntime` with Javadoc explaining the executor-ownership contract
- `DefaultEffectRuntime.close()` calls `executor.shutdown()` — if inherited, would terminate the ActorSystem's executor on any try-with-resources usage
- Override body is a single comment: `// Intentional no-op: executor lifecycle belongs to ActorSystem.`
- Compiled cleanly

### Task 3 — Run full test suite
**Commits**: 377c314 (fix required before green)
- **Initial run**: 4 tests failed — all in `CapabilityHandler.compose()` tests
- **Root cause discovered**: Roux v0.2.0 changed `compose()` to catch `UnsupportedOperationException` (was previously catching `ClassCastException` or RuntimeException). Handlers with narrowly-typed parameters (e.g., `CapabilityHandler<EchoCapability>`) throw `ClassCastException` when the JVM inserts a checkcast at the call site — this now propagates uncaught instead of being swallowed.
- **Fix applied**: Changed three handler classes from `CapabilityHandler<SpecificType>` to `CapabilityHandler<Capability<?>>` with explicit `instanceof` checks that throw `UnsupportedOperationException` for unhandled types:
  - `ConsoleLogHandler` (production class): `CapabilityHandler<LogCapability>` → `CapabilityHandler<Capability<?>>`
  - `CapabilityIntegrationTest.EchoHandler`: same pattern
  - `EffectCapabilityExample.ValidationHandler`: same pattern
  - `EffectCapabilityExample.MetricsHandler`: same pattern
- **Final result**: 362 tests, 0 failures — BUILD SUCCESSFUL

## Deviations

### Deviation 1 — Compose() contract change required handler rewrites
**Root cause**: Roux v0.2.0 changelog stated "Fixed capability handler composition to use `UnsupportedOperationException`". This means handlers intended for use with `compose()` must implement `CapabilityHandler<Capability<?>>` with explicit `instanceof` checks, not rely on type-narrowed parameters that throw `ClassCastException` for wrong types.

**Fix**: Updated `ConsoleLogHandler` (production), `EchoHandler` (test), `ValidationHandler` (example), `MetricsHandler` (example). Pattern: check `instanceof`, throw `UnsupportedOperationException` if no match, dispatch via `switch` on the specific type.

**This was not anticipated** in the plan's "breaking changes" table (listed as "No change needed — our tests properly wire all capabilities"). The composition _path_ was fine; the composition _dispatch mechanism_ changed.

## Verification Checklist
- [x] `gradle/libs.versions.toml` shows `roux = "0.2.1"`
- [x] `./gradlew compileJava compileTestJava` succeeds
- [x] `ActorEffectRuntime.close()` overridden — no-op with Javadoc
- [x] `./gradlew test` — 362 tests, 0 failures
- [x] No pre-existing ClusterModeTest failure this run
- [x] Only bridge/capability files changed — no unrelated modifications

## New API Knowledge (Roux v0.2.0 handler contract)
- Handlers used with `CapabilityHandler.compose()` or `.orElse()` MUST implement `CapabilityHandler<Capability<?>>` and throw `UnsupportedOperationException` for unhandled capability types
- `widen()` is a plain cast — it does NOT add `UnsupportedOperationException` behavior
- For type-safe composed dispatch, use either: (a) `CapabilityHandler.builder().on(Type.class, handler).build()` or (b) implement `CapabilityHandler<Capability<?>>` with `instanceof` + `UnsupportedOperationException`
- `CapabilityHandler.compose(h1, h2, h3)` — handlers CAN be unwidened but must already implement the UOE contract; passing `.widen()` on a `Capability<?>` handler is redundant but harmless
