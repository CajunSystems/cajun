# Phase 5 Summary: Rewrite Effect Actor Builders

## Result: COMPLETE

EffectActorBuilder and ActorSystemEffectExtensions created with Roux-native API.
Actors now spawn with Effect<E,A> message handling via ActorEffectRuntime, supporting
capability injection through CapabilityHandler.

## Commits

| Task | Commit | Description |
|------|--------|-------------|
| Task 1 | `44d84dc` | feat(5-5): create Roux-native EffectActorBuilder |
| Task 2 | `6d1ebe4` | feat(5-5): create Roux-native ActorSystemEffectExtensions |
| Task 3 | `a311da1` | test(5-5): add EffectActorBuilderTest integration tests |

## What Was Done

**Task 1**: Created EffectActorBuilder<E, Message, A> — fluent builder that wraps a
Function<Message, Effect<E, A>> in a Handler<Message>, executing effects via
ActorEffectRuntime. Supports withId() and withCapabilityHandler(). The handler lambda
calls rt.unsafeRun(effect) or rt.unsafeRunWithHandler(effect, cap) depending on whether
a CapabilityHandler is configured. RuntimeExceptions are rethrown directly; other
Throwables are wrapped in RuntimeException with the failing message in the message.

**Task 2**: Created ActorSystemEffectExtensions with three static methods:
effectActorOf() (returns builder for further configuration), spawnEffectActor() with
two args (auto-generated ID), spawnEffectActor() with three args (explicit actor ID).

**Task 3**: Created EffectActorBuilderTest with 7 integration tests covering:
- effectActorProcessesMessageViaSuspend: basic Effect.suspend() + Unit.unit() roundtrip
- effectActorProcessesMessageViaSucceedAndFlatMap: Effect.succeed().flatMap() chain
- withIdAssignsActorId: verifies pid.actorId() matches the withId() value
- withCapabilityHandlerEnablesCapabilityEffects: LogCapability.Info through ConsoleLogHandler
- multipleMessagesProcessedByEffectActor: 3 integer messages, sum verified
- effectActorOfReturnsConfigurableBuilder: builder factory pattern with withId()
- spawnEffectActorThreeArgVariantAssignsId: 3-arg static spawn with explicit ID

## API Deviations

None. All planned APIs existed as specified:
- `Effect.suspend(ThrowingSupplier<A>)` — confirmed present
- `Unit.unit()` — confirmed public static factory
- `EffectRuntime.unsafeRun(Effect<E,A>)` — confirmed signature
- `EffectRuntime.unsafeRunWithHandler(Effect<E,A>, CapabilityHandler<Capability<?>>)` — confirmed
- `ActorSystem.actorOf(Handler<Message>)` — confirmed accepts handler instance

## Final functional/ Package Structure

```
functional/
├── ActorEffectRuntime.java
├── EffectActorBuilder.java            <- new
├── ActorSystemEffectExtensions.java   <- new
└── capabilities/
    ├── LogCapability.java
    └── ConsoleLogHandler.java
```

## Verification Checklist

- [x] EffectActorBuilder.java created
- [x] ActorSystemEffectExtensions.java created
- [x] EffectActorBuilderTest.java created -- 7 tests
- [x] All 7 tests pass (failures=0, errors=0, skipped=0)
- [x] ./gradlew :lib:compileJava -- BUILD SUCCESSFUL
- [x] ./gradlew :lib:compileTestJava -- BUILD SUCCESSFUL
- [x] ./gradlew test -- BUILD SUCCESSFUL, no regressions
