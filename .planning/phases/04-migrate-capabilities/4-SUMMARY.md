# Phase 4 Summary: Migrate Capabilities

## Result: COMPLETE ✅

LogCapability and ConsoleLogHandler created using Roux's Capability<R> / CapabilityHandler<C>
model. Both Effect.from() and Effect.generate() patterns tested end-to-end through ActorEffectRuntime.

## Commits

| Task | Commit | Description |
|------|--------|-------------|
| Task 1 | `338f0cc` | feat(4-4): create LogCapability sealed interface |
| Task 2 | `6c250a8` | feat(4-4): create ConsoleLogHandler capability handler |
| Task 3 | `d72f2b5` | test(4-4): add LogCapabilityTest end-to-end integration |
| Fix    | `310cd71` | fix(4-4): fix compile error in LogCapability/ConsoleLogHandler |

## What Was Done

**Task 1**: Created `LogCapability` sealed interface extending `Capability<Unit>` with 4 variants:
Info, Debug, Warn, Error. Uses Roux's Unit type (via `Unit.unit()` factory) for result.

**Task 2**: Created `ConsoleLogHandler` implementing `CapabilityHandler<LogCapability>`.
Exhaustive sealed switch — no default branch. INFO/DEBUG/WARN → stdout, ERROR → stderr.

**Task 3**: Created `LogCapabilityTest` with 7 integration tests covering Effect.from(),
Effect.generate()/ctx.perform(), and CapabilityHandler.compose().

## Deviations from Plan

**`Unit.INSTANCE` is private**: The plan used `Unit.INSTANCE` but javap revealed it has private
access. The public factory method is `Unit.unit()`. All usages updated accordingly. A fix commit
was created after the initial compile failure: `310cd71`.

## Package Structure

```
functional/
├── ActorEffectRuntime.java
└── capabilities/
    ├── LogCapability.java        ← new
    └── ConsoleLogHandler.java    ← new

test/functional/
├── ActorEffectRuntimeTest.java
├── RouxSmokeTest.java
└── capabilities/
    └── LogCapabilityTest.java    ← new
```

## Verification Checklist

- [x] LogCapability.java created — sealed, extends Capability<Unit>, 4 records
- [x] ConsoleLogHandler.java created — implements CapabilityHandler<LogCapability>
- [x] LogCapabilityTest.java created — 7 tests
- [x] All 7 LogCapabilityTest tests pass
- [x] ./gradlew :lib:compileJava → BUILD SUCCESSFUL
- [x] ./gradlew test → BUILD SUCCESSFUL, no regressions
