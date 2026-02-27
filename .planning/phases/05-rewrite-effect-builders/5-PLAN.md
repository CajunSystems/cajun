# Phase 5 Plan: Rewrite Effect Actor Builders

## Objective
Create `EffectActorBuilder` and `ActorSystemEffectExtensions` with a Roux-native API.
Both were deleted in Phase 3. This phase recreates them from scratch using Roux's
`Effect<E, A>` instead of the old `Effect<State, Error, Result>`.

## Context

### Confirmed current state
- `EffectActorBuilder.java` — does NOT exist (deleted in Phase 3) ✓
- `ActorSystemEffectExtensions.java` — does NOT exist (deleted in Phase 3) ✓
- `functional/` package contains only `ActorEffectRuntime.java` + `capabilities/`

### Cajun actor API (confirmed from source)

```java
// Handler interface (stateless)
interface Handler<Message> {
    void receive(Message message, ActorContext context);  // void — no checked throws
}

// ActorSystem factory methods
ActorBuilder<Message> actorOf(Handler<Message> handler)
ActorBuilder<Message> actorOf(Class<? extends Handler<Message>> handlerClass)

// ActorBuilder fluent API
ActorBuilder<Message> withId(String id)
// ... other config methods
Pid spawn()

// Pid is a record
public record Pid(String actorId, ActorSystem system)
// pid.tell(message) — send message to actor
```

### Design: what an effect actor does

For each message received, the actor:
1. Calls a user-provided `Function<Message, Effect<E, A>>` to produce an effect
2. Runs the effect via `ActorEffectRuntime` (on the actor system's executor)
3. Discards the result `A` (fire-and-forget from the actor's perspective)

Error handling: if the effect throws a checked `E`, wrap it in `RuntimeException` so
`Handler.receive()` (which cannot declare checked throws) can propagate it.

### EffectActorBuilder design

```java
public class EffectActorBuilder<E extends Throwable, Message, A> {
    // Constructor: system + handler function
    // withId(String) → fluent
    // withCapabilityHandler(CapabilityHandler<Capability<?>>) → fluent
    //   used when the effect contains Effect.from(capability) calls
    // spawn() → creates Handler<Message> wrapper + calls system.actorOf(...).spawn()
}
```

The spawned `Handler<Message>` wrapper:
- Calls `handler.apply(message)` to get the `Effect<E, A>`
- If `capabilityHandler != null`: calls `runtime.unsafeRunWithHandler(effect, cap)`
- Else: calls `runtime.unsafeRun(effect)`
- Catches `Throwable`, re-throws `RuntimeException` as-is, wraps others

### ActorSystemEffectExtensions design

```java
public final class ActorSystemEffectExtensions {
    // factory → returns builder for further config
    static <E, Message, A> EffectActorBuilder<E, Message, A>
        effectActorOf(ActorSystem system, Function<Message, Effect<E, A>> handler)

    // spawn immediately, system-generated ID
    static <E, Message, A> Pid
        spawnEffectActor(ActorSystem system, Function<Message, Effect<E, A>> handler)

    // spawn immediately, explicit ID
    static <E, Message, A> Pid
        spawnEffectActor(ActorSystem system, String actorId, Function<Message, Effect<E, A>> handler)
}
```

### Key API facts confirmed
- `Unit.unit()` is the public factory (not `Unit.INSTANCE` which is private)
- `Handler.receive()` declares no checked throws → must wrap checked exceptions
- `Pid` is a record → `pid.actorId()` gives the ID
- `CapabilityHandler.widen()` converts `CapabilityHandler<LogCapability>` → `CapabilityHandler<Capability<?>>`

### File locations
- New: `lib/src/main/java/com/cajunsystems/functional/EffectActorBuilder.java`
- New: `lib/src/main/java/com/cajunsystems/functional/ActorSystemEffectExtensions.java`
- New: `lib/src/test/java/com/cajunsystems/functional/EffectActorBuilderTest.java`

---

## Tasks

### Task 1 — Create EffectActorBuilder.java

**File**: `lib/src/main/java/com/cajunsystems/functional/EffectActorBuilder.java`

```java
package com.cajunsystems.functional;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.handler.Handler;
import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.capability.Capability;
import com.cajunsystems.roux.capability.CapabilityHandler;

import java.util.function.Function;

/**
 * Fluent builder for spawning actors whose message handling is expressed as
 * Roux {@link Effect} pipelines.
 *
 * <p>For each message received, the configured {@code handler} function produces
 * an {@link Effect}, which is executed via {@link ActorEffectRuntime} — dispatched
 * through the actor system's executor rather than a fresh virtual-thread pool.
 * The result value {@code A} is discarded after execution.
 *
 * <p>Basic usage:
 * <pre>{@code
 * Pid pid = new EffectActorBuilder<>(system,
 *     (String msg) -> Effect.suspend(() -> {
 *         System.out.println("Got: " + msg);
 *         return Unit.unit();
 *     }))
 *     .withId("my-actor")
 *     .spawn();
 *
 * pid.tell("hello");
 * }</pre>
 *
 * <p>With capabilities:
 * <pre>{@code
 * Pid pid = new EffectActorBuilder<>(system,
 *     (String msg) -> Effect.from(new LogCapability.Info("got: " + msg)))
 *     .withCapabilityHandler(new ConsoleLogHandler().widen())
 *     .spawn();
 * }</pre>
 *
 * @param <E>       the error type of the effects
 * @param <Message> the actor's message type
 * @param <A>       the result type of the effects (discarded after execution)
 */
public class EffectActorBuilder<E extends Throwable, Message, A> {

    private final ActorSystem system;
    private final ActorEffectRuntime runtime;
    private final Function<Message, Effect<E, A>> handler;
    private CapabilityHandler<Capability<?>> capabilityHandler;
    private String actorId;

    /**
     * Creates a builder for an effect-based actor.
     *
     * @param system  the actor system to spawn into
     * @param handler maps each incoming message to an {@link Effect} to execute
     */
    public EffectActorBuilder(ActorSystem system, Function<Message, Effect<E, A>> handler) {
        this.system = system;
        this.runtime = new ActorEffectRuntime(system);
        this.handler = handler;
    }

    /**
     * Sets the actor's ID. If not called, the system generates a unique ID.
     */
    public EffectActorBuilder<E, Message, A> withId(String actorId) {
        this.actorId = actorId;
        return this;
    }

    /**
     * Sets the {@link CapabilityHandler} used when effects contain
     * {@link com.cajunsystems.roux.Effect#from(Capability)} calls.
     *
     * <p>Pass a widened handler: {@code myHandler.widen()} or
     * {@code CapabilityHandler.compose(myHandler)}.
     */
    public EffectActorBuilder<E, Message, A> withCapabilityHandler(
            CapabilityHandler<Capability<?>> capabilityHandler) {
        this.capabilityHandler = capabilityHandler;
        return this;
    }

    /**
     * Spawns the actor and returns its {@link Pid}.
     */
    public Pid spawn() {
        final ActorEffectRuntime rt = this.runtime;
        final Function<Message, Effect<E, A>> h = this.handler;
        final CapabilityHandler<Capability<?>> cap = this.capabilityHandler;

        Handler<Message> wrappedHandler = (message, context) -> {
            Effect<E, A> effect = h.apply(message);
            try {
                if (cap != null) {
                    rt.unsafeRunWithHandler(effect, cap);
                } else {
                    rt.unsafeRun(effect);
                }
            } catch (RuntimeException re) {
                throw re;
            } catch (Throwable t) {
                throw new RuntimeException(
                        "Effect execution failed for message: " + message, t);
            }
        };

        var builder = system.actorOf(wrappedHandler);
        if (actorId != null) {
            builder = builder.withId(actorId);
        }
        return builder.spawn();
    }
}
```

Commit:
```bash
git add lib/src/main/java/com/cajunsystems/functional/EffectActorBuilder.java
git commit -m "feat(5-5): create Roux-native EffectActorBuilder"
```

### Task 2 — Create ActorSystemEffectExtensions.java

**File**: `lib/src/main/java/com/cajunsystems/functional/ActorSystemEffectExtensions.java`

```java
package com.cajunsystems.functional;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.roux.Effect;

import java.util.function.Function;

/**
 * Static extension-method-style helpers for spawning Roux effect-based actors
 * on an {@link ActorSystem}.
 *
 * <p>Convenience wrappers around {@link EffectActorBuilder}. Use
 * {@link #effectActorOf} when you need to configure the builder further (e.g.,
 * set a capability handler), or the {@code spawnEffectActor} variants for
 * one-liner spawning.
 *
 * <p>Usage:
 * <pre>{@code
 * import static com.cajunsystems.functional.ActorSystemEffectExtensions.*;
 *
 * Pid pid = spawnEffectActor(system, "greeter",
 *     (String msg) -> Effect.suspend(() -> {
 *         System.out.println("Hello, " + msg + "!");
 *         return Unit.unit();
 *     }));
 *
 * pid.tell("world");
 * }</pre>
 */
public final class ActorSystemEffectExtensions {

    private ActorSystemEffectExtensions() {}

    /**
     * Returns an {@link EffectActorBuilder} for the given handler.
     * Use the builder to set a capability handler, actor ID, or other options
     * before calling {@link EffectActorBuilder#spawn()}.
     *
     * @param system  the actor system
     * @param handler maps each message to an {@link Effect} to execute
     * @return a builder for further configuration
     */
    public static <E extends Throwable, Message, A>
    EffectActorBuilder<E, Message, A> effectActorOf(
            ActorSystem system,
            Function<Message, Effect<E, A>> handler) {
        return new EffectActorBuilder<>(system, handler);
    }

    /**
     * Spawns an effect-based actor with a system-generated ID.
     *
     * @param system  the actor system
     * @param handler maps each message to an {@link Effect} to execute
     * @return the {@link Pid} of the spawned actor
     */
    public static <E extends Throwable, Message, A>
    Pid spawnEffectActor(
            ActorSystem system,
            Function<Message, Effect<E, A>> handler) {
        return new EffectActorBuilder<>(system, handler).spawn();
    }

    /**
     * Spawns an effect-based actor with the given ID.
     *
     * @param system   the actor system
     * @param actorId  the actor's ID
     * @param handler  maps each message to an {@link Effect} to execute
     * @return the {@link Pid} of the spawned actor
     */
    public static <E extends Throwable, Message, A>
    Pid spawnEffectActor(
            ActorSystem system,
            String actorId,
            Function<Message, Effect<E, A>> handler) {
        return new EffectActorBuilder<>(system, handler).withId(actorId).spawn();
    }
}
```

Commit:
```bash
git add lib/src/main/java/com/cajunsystems/functional/ActorSystemEffectExtensions.java
git commit -m "feat(5-5): create Roux-native ActorSystemEffectExtensions"
```

### Task 3 — Write EffectActorBuilderTest.java

**File**: `lib/src/test/java/com/cajunsystems/functional/EffectActorBuilderTest.java`

Tests (7):
1. Effect actor processes message via `Effect.suspend` (side-effect capture + latch)
2. Effect actor processes message via `Effect.succeed` + `flatMap`
3. `withId` assigns the actor ID (verified via `pid.actorId()`)
4. `withCapabilityHandler` enables capability effects (`Effect.from(LogCapability)`)
5. Multiple messages processed sequentially (3 messages, counting total)
6. `effectActorOf()` static factory returns a configurable builder
7. `spawnEffectActor(system, actorId, handler)` 3-arg variant assigns ID

```java
package com.cajunsystems.functional;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.functional.capabilities.ConsoleLogHandler;
import com.cajunsystems.functional.capabilities.LogCapability;
import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.data.Unit;
import org.junit.jupiter.api.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.cajunsystems.functional.ActorSystemEffectExtensions.*;
import static org.junit.jupiter.api.Assertions.*;

class EffectActorBuilderTest {

    private ActorSystem system;

    @BeforeEach
    void setUp() {
        system = new ActorSystem();
    }

    @AfterEach
    void tearDown() {
        system.shutdown();
    }

    @Test
    void effectActorProcessesMessageViaSuspend() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> captured = new AtomicReference<>();

        Pid pid = spawnEffectActor(
                system,
                (String msg) -> Effect.suspend(() -> {
                    captured.set(msg);
                    latch.countDown();
                    return Unit.unit();
                })
        );

        pid.tell("hello-effect");
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals("hello-effect", captured.get());
    }

    @Test
    void effectActorProcessesMessageViaSucceedAndFlatMap() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        Pid pid = spawnEffectActor(
                system,
                (String msg) -> Effect.<RuntimeException, Unit>succeed(Unit.unit())
                        .flatMap(__ -> Effect.suspend(() -> {
                            latch.countDown();
                            return Unit.unit();
                        }))
        );

        pid.tell("trigger");
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    void withIdAssignsActorId() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        Pid pid = new EffectActorBuilder<>(
                system,
                (String msg) -> Effect.suspend(() -> {
                    latch.countDown();
                    return Unit.unit();
                })
        ).withId("named-effect-actor").spawn();

        assertEquals("named-effect-actor", pid.actorId());
        pid.tell("ping");
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    void withCapabilityHandlerEnablesCapabilityEffects() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        ConsoleLogHandler logHandler = new ConsoleLogHandler();

        // Effect uses Effect.from(capability) — requires capabilityHandler on the builder
        Pid pid = new EffectActorBuilder<>(
                system,
                (String msg) -> Effect.<RuntimeException, Unit>from(
                                new LogCapability.Info("received: " + msg))
                        .flatMap(__ -> Effect.suspend(() -> {
                            latch.countDown();
                            return Unit.unit();
                        }))
        ).withCapabilityHandler(logHandler.widen()).spawn();

        pid.tell("cap-test");
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    void multipleMessagesProcessedByEffectActor() throws InterruptedException {
        int count = 3;
        CountDownLatch latch = new CountDownLatch(count);
        AtomicInteger total = new AtomicInteger(0);

        Pid pid = spawnEffectActor(
                system,
                (Integer msg) -> Effect.suspend(() -> {
                    total.addAndGet(msg);
                    latch.countDown();
                    return Unit.unit();
                })
        );

        pid.tell(1);
        pid.tell(2);
        pid.tell(3);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(6, total.get());
    }

    @Test
    void effectActorOfReturnsConfigurableBuilder() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        Pid pid = effectActorOf(
                system,
                (String msg) -> Effect.suspend(() -> {
                    latch.countDown();
                    return Unit.unit();
                })
        ).withId("builder-actor").spawn();

        assertEquals("builder-actor", pid.actorId());
        pid.tell("test");
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    void spawnEffectActorThreeArgVariantAssignsId() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        Pid pid = spawnEffectActor(
                system,
                "explicit-id-actor",
                (String msg) -> Effect.suspend(() -> {
                    latch.countDown();
                    return Unit.unit();
                })
        );

        assertEquals("explicit-id-actor", pid.actorId());
        pid.tell("ping");
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}
```

**Note on type inference**: If `Effect.suspend(...)` fails to infer `E = RuntimeException`,
add a type witness: `Effect.<RuntimeException, Unit>suspend(...)`.

**Note on `Unit.unit()`**: Use `Unit.unit()` (public static factory), NOT `Unit.INSTANCE` (private).

Commit:
```bash
git add lib/src/test/java/com/cajunsystems/functional/EffectActorBuilderTest.java
git commit -m "test(5-5): add EffectActorBuilderTest integration tests"
```

### Task 4 — Compile and test verification

```bash
./gradlew :lib:compileJava
./gradlew :lib:compileTestJava
```

Both must produce BUILD SUCCESSFUL.

**Common compile issues and fixes**:
- `Unit.unit()` vs `Unit.INSTANCE` — use `Unit.unit()`
- `rt.unsafeRun(effect)` throws `E` (checked) → must be caught. The `catch (Throwable t)` block handles this.
- `var builder = system.actorOf(wrappedHandler)` — if `var` causes issues with chained `withId`, assign as `ActorBuilder<Message>` explicitly
- Type inference on `Effect.suspend` lambdas — add `<RuntimeException, Unit>` type witness if needed

After fixing any compile errors, commit: `fix(5-5): fix compile errors`

Then run tests:
```bash
./gradlew :lib:test --tests "com.cajunsystems.functional.EffectActorBuilderTest" --no-daemon
```

All 7 tests must pass.

```bash
./gradlew test --no-daemon
```

Full suite green. No regressions.

---

## Verification

- [ ] `EffectActorBuilder.java` created — generic `<E, Message, A>`, fluent builder, `spawn()`
- [ ] `ActorSystemEffectExtensions.java` created — 3 static methods, final class
- [ ] `EffectActorBuilderTest.java` created — 7 tests
- [ ] All 7 `EffectActorBuilderTest` tests pass
- [ ] `./gradlew :lib:compileJava` → BUILD SUCCESSFUL
- [ ] `./gradlew test` → BUILD SUCCESSFUL, no regressions

## Success Criteria

`EffectActorBuilder` and `ActorSystemEffectExtensions` exist, compile, and integrate
end-to-end: actors spawn, receive messages, execute Roux `Effect<E, A>` pipelines via
`ActorEffectRuntime`, and support capability injection via `withCapabilityHandler()`.

## Output
- Created: `lib/src/main/java/com/cajunsystems/functional/EffectActorBuilder.java`
- Created: `lib/src/main/java/com/cajunsystems/functional/ActorSystemEffectExtensions.java`
- Created: `lib/src/test/java/com/cajunsystems/functional/EffectActorBuilderTest.java`
