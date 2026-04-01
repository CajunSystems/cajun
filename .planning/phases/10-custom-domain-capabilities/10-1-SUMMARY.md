# Phase 10, Plan 1 Summary: EffectCapabilityExample

## Status: Complete

## Tasks Completed

### Task 1 — EffectCapabilityExample.java
**Commit**: 69b22a6
- Created `lib/src/test/java/examples/EffectCapabilityExample.java`
- 3 tests: `validationCapabilityReturnsBooleanResults`, `metricsHandlerTracksCountersAcrossMessages`,
  `composedHandlerDispatchesThreeCapabilityTypes`
- `ValidationCapability extends Capability<Boolean>` — `IsNonEmpty` + `HasMinLength` variants
- `MetricsCapability extends Capability<Unit>` — `Increment` + `Record` variants with stateful `MetricsHandler`
- `CapabilityHandler.compose(new ValidationHandler(), mh, new ConsoleLogHandler())` — all three in one handler

### Deviation: `.widen()` required before `Effect.generate()`
The plan code used raw (non-widened) handlers in `Effect.generate(ctx -> ..., handler)`.
The actual working code calls `.widen()` on each handler before passing:
```java
Effect.generate(ctx -> { ... }, new ValidationHandler().widen())
Effect.generate(ctx -> { ... }, mh.widen())
```
`CapabilityHandler.compose(h1, h2, h3)` continues to work without `.widen()` on the inputs.

### Task 2 — Full test suite
- BUILD SUCCESSFUL
- Total tests: 360 (357 existing + 3 new) ✓
- `EffectCapabilityExample`: 3 tests, 0 failures, 0 errors

## Deviations
One deviation: handlers passed to `Effect.generate()` need `.widen()` to `CapabilityHandler<Capability<?>>`.
`CapabilityHandler.compose()` does NOT require `.widen()` on its arguments.

## API Findings
- `Effect.generate(ctx -> ..., handler)` requires `handler` to be `CapabilityHandler<Capability<?>>`
  (i.e., call `.widen()` first), even when all capabilities in the generate block are the same type
- `CapabilityHandler.compose(h1, h2, h3)` accepts raw unwidened handlers and returns
  `CapabilityHandler<Capability<?>>` — no `.widen()` needed on inputs
- Stateful handler pattern: hold a reference to the `MetricsHandler` instance before passing
  to the actor; the same object accumulates state and can be queried directly in assertions
- `ctx.perform(new ValidationCapability.IsNonEmpty(...))` → `Boolean nonEmpty = ctx.perform(...)`
  works correctly — return type inferred from assignment target
- Cast syntax in handler: `(R) (Boolean) !v.value().isEmpty()` is an alternative to
  `return (R) switch(...)` — both compile and work correctly
