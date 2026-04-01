# Phase 10, Plan 2: EffectTestableCapabilityExample

## Objective
Write `EffectTestableCapabilityExample.java` — demonstrates swappable capability handlers for
production vs test environments. A single `NotifyCapability` is expressed once as an effect;
`ConsoleNotifyHandler` sends to stdout (production path) and `CapturingNotifyHandler` stores
notifications in a list (test double). Handler is injected at spawn time via
`EffectActorBuilder.withCapabilityHandler()`.

## Context

### The testability pattern
Effects describe *what* to do; handlers decide *how* to do it. Injecting the handler via
`withCapabilityHandler()` means the actor's message-handling logic never changes — only the
handler instance changes between production and test.

```
Same effect function:
  (OrderEvent event) -> Effect.from(new NotifyCapability.Send(...))
                              .flatMap(__ -> ...)

Production spawn:           Test spawn:
  .withCapabilityHandler      .withCapabilityHandler
    (new ConsoleNotifyHandler()   (new CapturingNotifyHandler()
       .widen())                     .widen())
```

### `Effect.from()` vs `Effect.generate()` for this pattern
`Effect.from(cap)` defers capability resolution to runtime — the handler is NOT baked in.
`EffectActorBuilder.withCapabilityHandler(handler)` injects the handler; the builder calls
`rt.unsafeRunWithHandler(effect, handler)` to execute each incoming message's effect.

This separation is what makes the same effect function work with different handlers.

### EffectActorBuilder.withCapabilityHandler() — confirmed API
```java
// From EffectActorBuilder.java (lines 36–39, javadoc):
// "Pass a widened handler: myHandler.widen() or CapabilityHandler.compose(myHandler)."
public EffectActorBuilder<E, Message, A> withCapabilityHandler(
        CapabilityHandler<Capability<?>> capabilityHandler) { ... }
```
Accepts `CapabilityHandler<Capability<?>>` — always call `.widen()` on the handler before passing.

### Capability design
```
NotifyCapability extends Capability<Unit>
  └─ Send(String message, String recipient)
```
Simple single-variant capability — enough to show the swappable pattern without distracting
complexity.

### Handler design
```
ConsoleNotifyHandler  implements CapabilityHandler<NotifyCapability>
  └─ handle(Send s) → prints "[NOTIFY → recipient] message" to System.out; returns Unit.unit()

CapturingNotifyHandler implements CapabilityHandler<NotifyCapability>
  └─ handle(Send s) → appends "[recipient] message" to CopyOnWriteArrayList; returns Unit.unit()
```

### Test design
| Test | Handler | Assertion |
|------|---------|-----------|
| 1 | `ConsoleNotifyHandler` | Latch completes — no assertion on output (stdout is ephemeral) |
| 2 | `CapturingNotifyHandler` | `captured` list has 2 entries with expected recipient/message content |

### Key API facts
- `Effect.from(new NotifyCapability.Send(...))` returns `Effect<RuntimeException, Unit>`
- `.flatMap(__ -> Effect.suspend(() -> { latch.countDown(); return Unit.unit(); }))` chains the latch
  release without needing a capability — `Effect.suspend()` is capability-agnostic
- `withCapabilityHandler()` and `withId()` are independently chainable on the builder
- `EffectActorBuilder` message types do NOT need `Serializable`

### File location
`lib/src/test/java/examples/EffectTestableCapabilityExample.java`

---

## Tasks

### Task 1 — Write EffectTestableCapabilityExample.java

```java
package examples;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.functional.EffectActorBuilder;
import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.capability.Capability;
import com.cajunsystems.roux.capability.CapabilityHandler;
import com.cajunsystems.roux.data.Unit;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates swappable capability handlers for production vs test environments.
 *
 * <p>A {@code NotifyCapability} is expressed once as an {@link Effect}. Two handlers
 * implement the same capability differently:
 * <ul>
 *   <li>{@code ConsoleNotifyHandler} — production path; writes to {@code System.out}.</li>
 *   <li>{@code CapturingNotifyHandler} — test double; stores notifications in a list so
 *       tests can assert on the exact messages and recipients without stdout.</li>
 * </ul>
 *
 * <p>The actor's message-handling lambda is identical in both tests; only the
 * {@link CapabilityHandler} injected via
 * {@link EffectActorBuilder#withCapabilityHandler(CapabilityHandler)} differs.
 *
 * <p>Key patterns shown:
 * <ul>
 *   <li>{@code Effect.from(cap)} defers handler resolution to spawn time — the effect
 *       is pure data until run</li>
 *   <li>{@code withCapabilityHandler(handler.widen())} injects the handler without
 *       touching the effect definition</li>
 *   <li>Test doubles as plain Java objects — no mocking framework needed</li>
 *   <li>Chaining {@code Effect.from()} with {@code Effect.suspend()} for latches</li>
 * </ul>
 */
class EffectTestableCapabilityExample {

    // -------------------------------------------------------------------------
    // Custom capability
    // -------------------------------------------------------------------------

    /**
     * Capability for sending notifications. Returns {@link Unit} — callers care that
     * the notification was dispatched, not about a return value.
     */
    sealed interface NotifyCapability extends Capability<Unit>
            permits NotifyCapability.Send {
        record Send(String message, String recipient) implements NotifyCapability {}
    }

    // -------------------------------------------------------------------------
    // Handlers
    // -------------------------------------------------------------------------

    /** Production handler: writes notifications to {@code System.out}. */
    static class ConsoleNotifyHandler implements CapabilityHandler<NotifyCapability> {
        @Override
        @SuppressWarnings("unchecked")
        public <R> R handle(NotifyCapability capability) {
            return (R) switch (capability) {
                case NotifyCapability.Send s -> {
                    System.out.println("[NOTIFY → " + s.recipient() + "] " + s.message());
                    yield Unit.unit();
                }
            };
        }
    }

    /**
     * Test double: captures notifications in-memory instead of printing.
     *
     * <p>Hold a reference to this handler after injection to assert on
     * {@code captured} without any additional plumbing.
     */
    static class CapturingNotifyHandler implements CapabilityHandler<NotifyCapability> {
        final List<String> captured = new CopyOnWriteArrayList<>();

        @Override
        @SuppressWarnings("unchecked")
        public <R> R handle(NotifyCapability capability) {
            return (R) switch (capability) {
                case NotifyCapability.Send s -> {
                    captured.add("[" + s.recipient() + "] " + s.message());
                    yield Unit.unit();
                }
            };
        }
    }

    // -------------------------------------------------------------------------
    // Message type
    // -------------------------------------------------------------------------

    record OrderEvent(String orderId, String status, String customerEmail) {}

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    private ActorSystem system;

    @BeforeEach
    void setUp() { system = new ActorSystem(); }

    @AfterEach
    void tearDown() { system.shutdown(); }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    /**
     * ConsoleNotifyHandler writes to stdout — the production handler path.
     *
     * <p>This test verifies the actor processes both events end-to-end. The actual
     * notification output goes to stdout and is not asserted; the latch confirms
     * both messages were processed.
     */
    @Test
    void consoleHandlerProcessesOrderEvents() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(2);

        Pid notifier = new EffectActorBuilder<>(
            system,
            (OrderEvent event) ->
                Effect.<RuntimeException, Unit>from(new NotifyCapability.Send(
                        "Order " + event.orderId() + " is now " + event.status(),
                        event.customerEmail()))
                    .flatMap(__ -> Effect.suspend(() -> {
                        latch.countDown();
                        return Unit.unit();
                    }))
        ).withCapabilityHandler(new ConsoleNotifyHandler().widen())
         .withId("console-notifier")
         .spawn();

        notifier.tell(new OrderEvent("ORD-001", "shipped",   "alice@example.com"));
        notifier.tell(new OrderEvent("ORD-002", "delivered", "bob@example.com"));

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    /**
     * CapturingNotifyHandler intercepts notifications for assertions — the test path.
     *
     * <p>Same effect function as {@link #consoleHandlerProcessesOrderEvents()}; only the
     * injected {@link CapabilityHandler} changes. Holding a reference to the
     * {@code CapturingNotifyHandler} before spawn lets the test assert the exact content
     * of each captured notification after the latch completes.
     */
    @Test
    void capturingHandlerInterceptsNotificationsForAssertion() throws InterruptedException {
        CapturingNotifyHandler capturer = new CapturingNotifyHandler();
        CountDownLatch latch = new CountDownLatch(2);

        Pid notifier = new EffectActorBuilder<>(
            system,
            (OrderEvent event) ->
                Effect.<RuntimeException, Unit>from(new NotifyCapability.Send(
                        "Order " + event.orderId() + " is now " + event.status(),
                        event.customerEmail()))
                    .flatMap(__ -> Effect.suspend(() -> {
                        latch.countDown();
                        return Unit.unit();
                    }))
        ).withCapabilityHandler(capturer.widen())
         .withId("capturing-notifier")
         .spawn();

        notifier.tell(new OrderEvent("ORD-003", "shipped",   "charlie@example.com"));
        notifier.tell(new OrderEvent("ORD-004", "delivered", "diana@example.com"));

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(2, capturer.captured.size());
        assertTrue(capturer.captured.stream()
                .anyMatch(n -> n.contains("ORD-003") && n.contains("charlie@example.com")));
        assertTrue(capturer.captured.stream()
                .anyMatch(n -> n.contains("ORD-004") && n.contains("diana@example.com")));
    }
}
```

Commit:
```bash
git add lib/src/test/java/examples/EffectTestableCapabilityExample.java
git commit -m "feat(10-2): add EffectTestableCapabilityExample demonstrating swappable capability handlers"
```

---

### Task 2 — Run tests and verify

```bash
./gradlew test --no-daemon
```

Must produce BUILD SUCCESSFUL. The new class adds 2 tests.

Expected minimum: **362 tests** (360 existing after 10-1 + 2 new).

No commit for this task — verification only.

---

## Potential deviations and fixes

**If `Effect.from(new NotifyCapability.Send(...))` causes a type inference error:**

Add explicit type witness:
```java
Effect.<RuntimeException, Unit>from(new NotifyCapability.Send(...))
```
Already included in the plan code above; if there's still an error, also add type witnesses
to `.flatMap()`:
```java
.<Unit>flatMap(__ -> Effect.<RuntimeException, Unit>suspend(() -> { ... }))
```

**If `.flatMap()` chains `Effect<E, Unit>` → `Effect<E, Unit>` won't compile due to type param mismatch:**

Ensure the error type is consistent across the chain. All effects in the chain should use
`RuntimeException` as E. If Effect.suspend() infers a different E, replace with:
```java
Effect.<RuntimeException, Unit>suspend(() -> { ... })
```

**If `CapturingNotifyHandler.widen()` produces a type that `withCapabilityHandler()` won't accept:**

Use explicit cast or compose:
```java
CapabilityHandler<Capability<?>> testHandler =
        CapabilityHandler.compose(new CapturingNotifyHandler());
```

**If `captured.stream().anyMatch(n -> n.contains("ORD-003") && n.contains("charlie@example.com"))` is flaky:**

The `CopyOnWriteArrayList` is thread-safe and the latch ensures all messages are processed
before assertions run. This pattern is already proven in EffectFanOutExample.

---

## Verification

- [ ] `EffectTestableCapabilityExample.java` created with 2 tests
- [ ] Test 1: `consoleHandlerProcessesOrderEvents` — latch completes within 5 seconds
- [ ] Test 2: `capturingHandlerInterceptsNotificationsForAssertion` — 2 captured entries with correct ORD-003 / ORD-004 content
- [ ] `./gradlew test` → BUILD SUCCESSFUL, ≥ 362 tests pass

## Success Criteria

Both tests pass. The example clearly shows:
1. `Effect.from(cap)` + `withCapabilityHandler()` separates effect description from handler
2. Swapping the handler at spawn time switches between production and test behaviour
3. `CapturingNotifyHandler` as a plain Java test double — no mocking framework required

## Output
- Created: `lib/src/test/java/examples/EffectTestableCapabilityExample.java`
