# Phase 8 Plan: Stateful Actor + Effect Actor Composition

## Objective
Write two self-contained examples showing `StatefulHandler` and `EffectActorBuilder` actors
working side-by-side in the same ActorSystem. Target: Cajun library users learning when and
how to combine the two actor models.

## Context

### Architecture decision (from milestone setup)
**Ask-pattern composition**: a `StatefulHandler` actor holds domain state; an
`EffectActorBuilder` actor performs computation/logging with capabilities.
They interoperate via message-passing.

### Key API facts

**StatefulHandler**
```java
// Interface
State receive(Message message, State state, ActorContext context);
default State preStart(State state, ActorContext context);
default void postStop(State state, ActorContext context);
default boolean onError(Message message, State state, Throwable exception, ActorContext context);

// Spawning (handler instance variant — allows constructor args)
Pid cartPid = system.statefulActorOf(new CartHandler(auditPid), CartState.empty())
                    .withId("cart").spawn();
```

**Ask pattern with StatefulHandler**
- `system.ask(pid, message, Duration)` → `CompletableFuture<ResponseType>`
- Inside `receive()`, `context.getSender()` returns `Optional<Pid>` — populated for ask messages
- Reply with: `context.getSender().ifPresent(s -> context.tell(s, responseValue))`
- Blocking `.get()` on CompletableFuture inside a handler is **safe** — Cajun uses virtual threads

**Effect actor reply via embedded Pid**
- `EffectActorBuilder` does not expose `ActorContext` to the effect function
- For effect actors to reply: embed `Pid replyTo` in the request message
- Inside the effect: `req.replyTo().tell(result)` — routes directly via Pid.tell()
- Pattern: `record ComputeRequest(String input, Pid replyTo) {}`

**ActorContext.tell**
- `context.tell(Pid target, T message)` — send from within a StatefulHandler

### Existing examples to not overlap
- `EffectActorExample.java` — basic EffectActorBuilder (no StatefulHandler)
- `EffectErrorHandlingExample.java` — error operators (no stateful actor)

### File locations
- New: `lib/src/test/java/examples/EffectStatefulCompositionExample.java`
- New: `lib/src/test/java/examples/EffectAskPatternExample.java`

---

## Tasks

### Task 1 — Write EffectStatefulCompositionExample.java

**File**: `lib/src/test/java/examples/EffectStatefulCompositionExample.java`

Shopping cart (`StatefulHandler`) + audit logger (`EffectActorBuilder`).
Cart tells audit actor after each mutation; test queries total via `system.ask()`.

```java
package examples;

import com.cajunsystems.ActorContext;
import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.functional.EffectActorBuilder;
import com.cajunsystems.functional.capabilities.ConsoleLogHandler;
import com.cajunsystems.functional.capabilities.LogCapability;
import com.cajunsystems.handler.StatefulHandler;
import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.capability.Capability;
import com.cajunsystems.roux.capability.CapabilityHandler;
import com.cajunsystems.roux.data.Unit;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates {@link StatefulHandler} and {@link EffectActorBuilder} actors
 * working side-by-side in the same ActorSystem.
 *
 * <p>A shopping cart actor ({@code StatefulHandler}) manages cart state immutably.
 * An audit actor ({@code EffectActorBuilder} + {@code LogCapability}) logs each
 * cart mutation. The cart notifies the audit actor after every change (fire-and-forget),
 * and the test queries the cart total via the ask pattern.
 *
 * <p>Key patterns shown:
 * <ul>
 *   <li>Wiring a StatefulHandler to an EffectActorBuilder via constructor injection</li>
 *   <li>Fire-and-forget from a StatefulHandler to an effect actor via {@code context.tell}</li>
 *   <li>Ask-pattern query on a StatefulHandler using {@code system.ask()}</li>
 * </ul>
 */
class EffectStatefulCompositionExample {

    // --- Domain model ---

    record CartItem(String name, double price) {}

    record CartState(List<CartItem> items) {
        CartState { items = List.copyOf(items); }

        static CartState empty() { return new CartState(List.of()); }

        CartState add(CartItem item) {
            var next = new ArrayList<>(items);
            next.add(item);
            return new CartState(next);
        }

        double total() { return items.stream().mapToDouble(CartItem::price).sum(); }
    }

    // --- Cart messages ---

    sealed interface CartMessage permits CartMessage.AddItem, CartMessage.GetTotal {
        record AddItem(String name, double price) implements CartMessage {}
        record GetTotal() implements CartMessage {}
    }

    // --- Audit messages (sent to the effect actor) ---

    sealed interface AuditMessage permits AuditMessage.ItemAdded {
        record ItemAdded(String name, double price, double runningTotal) implements AuditMessage {}
    }

    // --- StatefulHandler: the shopping cart ---

    /**
     * Manages an immutable shopping cart. After each AddItem mutation, notifies
     * the audit effect actor. Replies to GetTotal queries via the ask pattern.
     */
    static class CartHandler implements StatefulHandler<CartState, CartMessage> {
        private final Pid auditPid;

        CartHandler(Pid auditPid) { this.auditPid = auditPid; }

        @Override
        public CartState receive(CartMessage message, CartState state, ActorContext context) {
            if (message instanceof CartMessage.AddItem add) {
                CartState newState = state.add(new CartItem(add.name(), add.price()));
                // Fire-and-forget to the effect actor — never blocks the cart
                context.tell(auditPid,
                        new AuditMessage.ItemAdded(add.name(), add.price(), newState.total()));
                return newState;
            } else if (message instanceof CartMessage.GetTotal) {
                // Reply to the ask-pattern caller with the current total
                context.getSender().ifPresent(s -> context.tell(s, state.total()));
                return state;
            }
            return state;
        }
    }

    private ActorSystem system;

    @BeforeEach
    void setUp() { system = new ActorSystem(); }

    @AfterEach
    void tearDown() { system.shutdown(); }

    /**
     * Shopping cart accumulates items and notifies the audit effect actor.
     * After processing, the test queries the cart total via ask pattern.
     */
    @Test
    void shoppingCartWithEffectAuditActor() throws Exception {
        CountDownLatch auditLatch = new CountDownLatch(3);
        CapabilityHandler<Capability<?>> logHandler = new ConsoleLogHandler().widen();

        // Effect actor: logs each cart mutation via LogCapability
        Pid auditPid = new EffectActorBuilder<>(
            system,
            (AuditMessage msg) -> {
                if (msg instanceof AuditMessage.ItemAdded added) {
                    return Effect.<RuntimeException, Unit>generate(ctx -> {
                        ctx.perform(new LogCapability.Info(
                                "Cart audit: added '" + added.name() +
                                "' $" + added.price() +
                                " | running total: $" + added.runningTotal()));
                        auditLatch.countDown();
                        return Unit.unit();
                    }, logHandler);
                }
                return Effect.succeed(Unit.unit());
            }
        ).withId("audit-actor").spawn();

        // Stateful cart actor — passes the audit Pid via constructor
        Pid cartPid = system.statefulActorOf(new CartHandler(auditPid), CartState.empty())
                .withId("shopping-cart")
                .spawn();

        // Mutate cart
        cartPid.tell(new CartMessage.AddItem("Widget",   29.99));
        cartPid.tell(new CartMessage.AddItem("Gadget",   49.99));
        cartPid.tell(new CartMessage.AddItem("Doohickey", 9.99));

        // Verify the effect actor processed all audit events
        assertTrue(auditLatch.await(5, TimeUnit.SECONDS),
                "Effect actor should have logged 3 audit events");

        // Query the stateful actor for total via ask pattern
        double total = system.<CartMessage, Double>ask(
                cartPid, new CartMessage.GetTotal(), Duration.ofSeconds(5)).get();

        assertEquals(89.97, total, 0.01);
    }

    /**
     * Verifies that state accumulates correctly across multiple mutations and that
     * the StatefulHandler actor and EffectActorBuilder actor run independently.
     */
    @Test
    void cartStateAccumulatesCorrectlyWithEffectAuditLogging() throws Exception {
        CountDownLatch auditLatch = new CountDownLatch(2);
        CapabilityHandler<Capability<?>> logHandler = new ConsoleLogHandler().widen();

        Pid auditPid = new EffectActorBuilder<>(
            system,
            (AuditMessage msg) -> Effect.<RuntimeException, Unit>generate(ctx -> {
                if (msg instanceof AuditMessage.ItemAdded added) {
                    ctx.perform(new LogCapability.Debug("audit: " + added.name()));
                }
                auditLatch.countDown();
                return Unit.unit();
            }, logHandler)
        ).withId("audit-actor-b").spawn();

        Pid cartPid = system.statefulActorOf(new CartHandler(auditPid), CartState.empty())
                .withId("cart-b")
                .spawn();

        cartPid.tell(new CartMessage.AddItem("Alpha", 10.00));
        cartPid.tell(new CartMessage.AddItem("Beta",  20.00));

        assertTrue(auditLatch.await(5, TimeUnit.SECONDS));

        double total = system.<CartMessage, Double>ask(
                cartPid, new CartMessage.GetTotal(), Duration.ofSeconds(5)).get();

        assertEquals(30.0, total, 0.001);
    }
}
```

Commit:
```bash
git add lib/src/test/java/examples/EffectStatefulCompositionExample.java
git commit -m "feat(8-8): add EffectStatefulCompositionExample — StatefulHandler + EffectActorBuilder"
```

---

### Task 2 — Write EffectAskPatternExample.java

**File**: `lib/src/test/java/examples/EffectAskPatternExample.java`

Shows how effect actors reply to callers by embedding a `replyTo Pid` in the request message.
The caller creates a reply actor (another `EffectActorBuilder`) to capture the response.

```java
package examples;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.functional.EffectActorBuilder;
import com.cajunsystems.functional.capabilities.ConsoleLogHandler;
import com.cajunsystems.functional.capabilities.LogCapability;
import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.capability.Capability;
import com.cajunsystems.roux.capability.CapabilityHandler;
import com.cajunsystems.roux.data.Unit;
import org.junit.jupiter.api.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.cajunsystems.functional.ActorSystemEffectExtensions.spawnEffectActor;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates the reply-via-Pid pattern for effect actors.
 *
 * <p>{@link com.cajunsystems.functional.EffectActorBuilder} does not expose
 * {@code ActorContext} to the effect function, so effect actors cannot use
 * {@code context.getSender()} to reply. Instead, embed the reply destination
 * as a {@code Pid replyTo} field in the request message.
 *
 * <p>The effect actor calls {@code req.replyTo().tell(result)} to send the response.
 * The {@code replyTo} can be any actor: a temporary reply actor, a stateful actor,
 * or another effect actor.
 */
class EffectAskPatternExample {

    // Request embeds the reply-to Pid
    record ComputeRequest(String input, Pid replyTo) {}

    // Response type
    record TransformResult(String upper, int length) {}

    private ActorSystem system;
    private CapabilityHandler<Capability<?>> logHandler;

    @BeforeEach
    void setUp() {
        system = new ActorSystem();
        logHandler = new ConsoleLogHandler().widen();
    }

    @AfterEach
    void tearDown() { system.shutdown(); }

    /**
     * An effect actor processes a request and replies to the embedded {@code replyTo} Pid.
     *
     * <p>The caller spawns a lightweight reply actor (another EffectActorBuilder) to
     * capture the response. This avoids any shared mutable state between caller and worker.
     */
    @Test
    void effectActorRepliesViaEmbeddedReplyToPid() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<TransformResult> captured = new AtomicReference<>();

        // 1. Reply receiver — captures the response
        Pid replyPid = spawnEffectActor(system,
            (TransformResult result) -> Effect.suspend(() -> {
                captured.set(result);
                latch.countDown();
                return Unit.unit();
            })
        );

        // 2. Worker effect actor — transforms input and logs via LogCapability
        Pid transformActor = new EffectActorBuilder<>(
            system,
            (ComputeRequest req) -> Effect.generate(ctx -> {
                ctx.perform(new LogCapability.Info("Transforming: \"" + req.input() + "\""));
                String upper  = req.input().toUpperCase();
                int    length = req.input().length();
                ctx.perform(new LogCapability.Debug(
                        "Result: " + upper + " (length=" + length + ")"));
                // Reply directly to the embedded replyTo Pid
                req.replyTo().tell(new TransformResult(upper, length));
                return Unit.unit();
            }, logHandler)
        ).withId("transform-actor").spawn();

        // 3. Send request with reply destination embedded in the message
        transformActor.tell(new ComputeRequest("hello world", replyPid));

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals("HELLO WORLD", captured.get().upper());
        assertEquals(11, captured.get().length());
    }

    /**
     * Multiple callers share one effect actor, each providing their own reply Pid.
     *
     * <p>Because the reply Pid is embedded in the message rather than inferred from
     * sender context, concurrent callers never interfere with each other's responses.
     */
    @Test
    void multipleCallersShareOneEffectActor() throws InterruptedException {
        int callerCount = 3;
        CountDownLatch latch = new CountDownLatch(callerCount);
        AtomicInteger totalLength = new AtomicInteger(0);

        // Shared worker effect actor
        Pid counterActor = new EffectActorBuilder<>(
            system,
            (ComputeRequest req) -> Effect.generate(ctx -> {
                ctx.perform(new LogCapability.Info(
                        "Counting chars in: \"" + req.input() + "\""));
                req.replyTo().tell(req.input().length());
                return Unit.unit();
            }, logHandler)
        ).withId("char-counter").spawn();

        // Three independent callers — each with its own reply actor
        String[] inputs = {"hello", "world", "cajun"};  // 5 + 5 + 5 = 15 total
        for (String input : inputs) {
            Pid replyPid = spawnEffectActor(system,
                (Integer count) -> Effect.suspend(() -> {
                    totalLength.addAndGet(count);
                    latch.countDown();
                    return Unit.unit();
                })
            );
            counterActor.tell(new ComputeRequest(input, replyPid));
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(15, totalLength.get()); // 5 + 5 + 5
    }
}
```

Commit:
```bash
git add lib/src/test/java/examples/EffectAskPatternExample.java
git commit -m "feat(8-8): add EffectAskPatternExample — reply-via-Pid pattern for effect actors"
```

---

### Task 3 — Run tests and verify

```bash
./gradlew test --no-daemon
```

Must produce BUILD SUCCESSFUL. The two new example classes add 4 tests.

Expected minimum: **599 tests** (595 existing + 2 from EffectStatefulCompositionExample + 2 from EffectAskPatternExample).

No commit for this task — verification only.

---

## Potential deviations and fixes

**If `system.ask()` type inference fails:**
```java
// Explicit type witnesses if needed:
CompletableFuture<Double> future = system.ask(cartPid, new CartMessage.GetTotal(), Duration.ofSeconds(5));
double total = future.get();
```

**If `CartState` record compact constructor fails to compile:**
Replace with regular constructor:
```java
CartState(List<CartItem> items) { this.items = List.copyOf(items); }
```

**If `sealed interface` with `permits` requires separate compilation units:**
Move the `CartMessage`/`AuditMessage` sealed interfaces to top-level static inner classes (they're already defined as nested interfaces — this should be fine).

**If `req.replyTo().tell(result)` doesn't route correctly:**
Verify `Pid.tell()` delegates to `system.routeMessage()`. From existing tests, `pid.tell(msg)` is confirmed to work from within effect lambdas (closures capture the Pid correctly).

---

## Verification

- [ ] `EffectStatefulCompositionExample.java` created — 2 tests (cart+audit, state accumulation)
- [ ] `EffectAskPatternExample.java` created — 2 tests (single caller, multiple callers)
- [ ] `CartHandler` uses `context.getSender()` for ask-pattern reply
- [ ] Effect actors use `req.replyTo().tell(result)` for reply
- [ ] `./gradlew test` → BUILD SUCCESSFUL, ≥ 599 tests pass

## Success Criteria

Both files compile and all 4 new tests pass. Each test is self-contained with its own
`ActorSystem`. The examples clearly show when to use `StatefulHandler` (long-lived mutable
state) vs `EffectActorBuilder` (computation/IO with capabilities), and how they interoperate.

## Output
- Created: `lib/src/test/java/examples/EffectStatefulCompositionExample.java`
- Created: `lib/src/test/java/examples/EffectAskPatternExample.java`
