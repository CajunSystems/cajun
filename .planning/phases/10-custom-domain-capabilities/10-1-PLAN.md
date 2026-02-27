# Phase 10, Plan 1: EffectCapabilityExample

## Objective
Write `EffectCapabilityExample.java` — demonstrates how to define and compose custom domain
capabilities for realistic scenarios. Shows `ValidationCapability` (returns `Boolean`) and
`MetricsCapability` (returns `Unit`, stateful in-memory handler), both composed with
`ConsoleLogHandler` via `CapabilityHandler.compose()`.

## Context

### What "custom capability" means
A sealed interface extending `Capability<R>` — pure data describing an operation that returns `R`.
The implementation lives in a `CapabilityHandler`, not the capability itself. Swapping the
handler changes behaviour without touching the effect.

### Capability patterns confirmed by CapabilityIntegrationTest
- `sealed interface EchoCapability extends Capability<String>` — non-Unit return type works
- `ctx.perform(new EchoCapability.Echo("gen-test"))` → `String echoed = ctx.perform(...)` — return
  value is captured by assignment inference (no explicit cast at call site)
- `CapabilityHandler.compose(new EchoHandler(), new ConsoleLogHandler())` — accepts raw
  unwidened handlers, returns `CapabilityHandler<Capability<?>>`
- `Effect.generate(ctx -> ..., composed)` — bakes composed handler into effect; `rt.unsafeRun()`
  is sufficient (no `withCapabilityHandler()` needed)

### Capability design for this example
```
ValidationCapability extends Capability<Boolean>
  └─ IsNonEmpty(String value)
  └─ HasMinLength(String value, int min)

MetricsCapability extends Capability<Unit>
  └─ Increment(String counter)     — increments AtomicInteger in ConcurrentHashMap
  └─ Record(String metric, double) — stores double gauge in ConcurrentHashMap
```
Both sealed interfaces extend `Capability<R>` directly (not `Capability<R>` with generic R),
matching the EchoCapability pattern — cleaner sealed switch exhaustiveness.

### Handler implementation pattern (from ConsoleLogHandler + EchoHandler)
```java
static class ValidationHandler implements CapabilityHandler<ValidationCapability> {
    @Override
    @SuppressWarnings("unchecked")
    public <R> R handle(ValidationCapability capability) {
        return (R) switch (capability) {
            case ValidationCapability.IsNonEmpty v  -> !v.value().isEmpty();
            case ValidationCapability.HasMinLength v -> v.value().length() >= v.min();
        };
    }
}
```
`@SuppressWarnings("unchecked")` belongs in the handler (not the call site).
The `(R)` cast is safe because the sealed switch exhaustively maps each variant to its
declared return type.

### Three tests
| Test | What it shows |
|------|--------------|
| 1 | `ValidationCapability` → Boolean return captured via `ctx.perform()` |
| 2 | `MetricsCapability` handler tracks counters across messages; handler instance stays testable |
| 3 | `CapabilityHandler.compose()` dispatches 3 capability types to the correct handler |

### Key API facts (from prior phases + CapabilityIntegrationTest)
- `Effect.generate(ctx -> { ... return Unit.unit(); }, handler)` — handler baked in, `unsafeRun()` used
- `ctx.perform(new ValidationCapability.IsNonEmpty(...))` returns `Boolean` — assigned directly
- `CapabilityHandler.compose(h1, h2, h3)` — no `.widen()` needed on inputs
- `spawnEffectActor(system, handler)` — quick spawn for collector actors
- Reply-via-Pid pattern: embed `Pid replyTo` in request record (EffectActorBuilder exposes no ActorContext)
- `EffectActorBuilder` message types do NOT need `Serializable`

### File location
`lib/src/test/java/examples/EffectCapabilityExample.java`

---

## Tasks

### Task 1 — Write EffectCapabilityExample.java

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

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.cajunsystems.functional.ActorSystemEffectExtensions.spawnEffectActor;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates how to define and compose custom domain capabilities.
 *
 * <p>Two custom capability types are defined:
 * <ul>
 *   <li>{@code ValidationCapability} — returns {@code Boolean}; two variants test whether
 *       a string is non-empty and meets a minimum length requirement.</li>
 *   <li>{@code MetricsCapability} — returns {@link Unit}; the handler is a stateful Java
 *       object that accumulates counters and gauges in memory.</li>
 * </ul>
 *
 * <p>Key patterns shown:
 * <ul>
 *   <li>Sealing a capability interface to return a domain type (not just {@code Unit})</li>
 *   <li>Capturing the return value of {@code ctx.perform()} inside {@code Effect.generate()}</li>
 *   <li>Stateful handler: a plain Java class with a {@code ConcurrentHashMap} whose reference
 *       is held by the test for post-run assertions</li>
 *   <li>{@link CapabilityHandler#compose} combining three capability types into one handler</li>
 * </ul>
 */
class EffectCapabilityExample {

    // -------------------------------------------------------------------------
    // Custom capabilities
    // -------------------------------------------------------------------------

    /**
     * Capability for text validation. All variants return {@code Boolean}.
     */
    sealed interface ValidationCapability extends Capability<Boolean>
            permits ValidationCapability.IsNonEmpty, ValidationCapability.HasMinLength {
        record IsNonEmpty(String value) implements ValidationCapability {}
        record HasMinLength(String value, int min) implements ValidationCapability {}
    }

    /**
     * Capability for in-memory metrics. All variants return {@link Unit}.
     */
    sealed interface MetricsCapability extends Capability<Unit>
            permits MetricsCapability.Increment, MetricsCapability.Record {
        /** Increment the named counter by 1. */
        record Increment(String counter) implements MetricsCapability {}
        /** Record a named gauge value (overwrites the previous value). */
        record Record(String metric, double value) implements MetricsCapability {}
    }

    // -------------------------------------------------------------------------
    // Handlers
    // -------------------------------------------------------------------------

    /** Evaluates validation rules; returns {@code Boolean} for each variant. */
    static class ValidationHandler implements CapabilityHandler<ValidationCapability> {
        @Override
        @SuppressWarnings("unchecked")
        public <R> R handle(ValidationCapability capability) {
            return (R) switch (capability) {
                case ValidationCapability.IsNonEmpty v  -> !v.value().isEmpty();
                case ValidationCapability.HasMinLength v -> v.value().length() >= v.min();
            };
        }
    }

    /**
     * In-memory metrics handler.
     *
     * <p>Holding a reference to this handler after injecting it into an actor lets
     * the test read accumulated state directly — no extra plumbing needed.
     */
    static class MetricsHandler implements CapabilityHandler<MetricsCapability> {
        final ConcurrentHashMap<String, AtomicInteger> counters = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, Double> gauges = new ConcurrentHashMap<>();

        @Override
        @SuppressWarnings("unchecked")
        public <R> R handle(MetricsCapability capability) {
            return (R) switch (capability) {
                case MetricsCapability.Increment inc -> {
                    counters.computeIfAbsent(inc.counter(), k -> new AtomicInteger(0))
                            .incrementAndGet();
                    yield Unit.unit();
                }
                case MetricsCapability.Record rec -> {
                    gauges.put(rec.metric(), rec.value());
                    yield Unit.unit();
                }
            };
        }
    }

    // -------------------------------------------------------------------------
    // Message types
    // -------------------------------------------------------------------------

    record ValidateRequest(String text, Pid replyTo) {}
    record ValidationResult(String text, boolean isValid) {}
    record ProcessItem(String key) {}

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
     * {@code ValidationCapability} returns a typed Boolean from {@code ctx.perform()}.
     *
     * <p>Two validation rules are applied per request — {@code IsNonEmpty} and
     * {@code HasMinLength} — and the combined result is forwarded to a collector via
     * the reply-to {@link Pid} embedded in the request.
     *
     * <p>Sending {@code "hello world"} (11 chars) passes both rules; {@code "hi"} (2 chars)
     * fails the minimum-length check.
     */
    @Test
    void validationCapabilityReturnsBooleanResults() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(2);
        List<ValidationResult> results = new CopyOnWriteArrayList<>();

        Pid collector = spawnEffectActor(system,
            (ValidationResult r) -> Effect.suspend(() -> {
                results.add(r);
                latch.countDown();
                return Unit.unit();
            })
        );

        Pid validator = new EffectActorBuilder<>(
            system,
            (ValidateRequest req) -> Effect.generate(ctx -> {
                Boolean nonEmpty = ctx.perform(new ValidationCapability.IsNonEmpty(req.text()));
                Boolean hasMin   = ctx.perform(
                        new ValidationCapability.HasMinLength(req.text(), 5));
                req.replyTo().tell(new ValidationResult(req.text(), nonEmpty && hasMin));
                return Unit.unit();
            }, new ValidationHandler())
        ).withId("validator").spawn();

        validator.tell(new ValidateRequest("hello world", collector)); // 11 chars → true
        validator.tell(new ValidateRequest("hi",          collector)); // 2 chars  → false

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(2, results.size());
        assertTrue(results.stream()
                .anyMatch(r -> r.text().equals("hello world") && r.isValid()));
        assertTrue(results.stream()
                .anyMatch(r -> r.text().equals("hi") && !r.isValid()));
    }

    /**
     * {@code MetricsHandler} maintains in-memory state across multiple messages.
     *
     * <p>Handlers can be stateful Java objects. Holding a reference to the
     * {@code MetricsHandler} instance after passing it to the actor lets the test
     * query accumulated state without any additional plumbing.
     *
     * <p>Three messages are processed; the counter reaches 3 and the gauge reflects
     * the key length of the last item processed ({@code "charlie"} = 7 chars).
     */
    @Test
    void metricsHandlerTracksCountersAcrossMessages() throws InterruptedException {
        MetricsHandler mh = new MetricsHandler();
        CountDownLatch latch = new CountDownLatch(3);

        Pid actor = new EffectActorBuilder<>(
            system,
            (ProcessItem item) -> Effect.generate(ctx -> {
                ctx.perform(new MetricsCapability.Increment("items.processed"));
                ctx.perform(new MetricsCapability.Record(
                        "last.key.length", (double) item.key().length()));
                latch.countDown();
                return Unit.unit();
            }, mh)
        ).withId("metrics-actor").spawn();

        actor.tell(new ProcessItem("alpha"));   // 5 chars
        actor.tell(new ProcessItem("bravo"));   // 5 chars
        actor.tell(new ProcessItem("charlie")); // 7 chars

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(3, mh.counters.get("items.processed").get());
        // "charlie" processed last → gauge = 7.0
        assertEquals(7.0, mh.gauges.get("last.key.length"), 0.001);
    }

    /**
     * {@link CapabilityHandler#compose} routes three capability types to their correct handlers.
     *
     * <p>One actor uses {@code ValidationCapability}, {@code MetricsCapability}, and
     * {@link LogCapability} within the same {@code Effect.generate()} block. The composed
     * handler dispatches each {@code ctx.perform()} call to the appropriate handler at runtime.
     *
     * <p>After processing two requests, the {@code MetricsHandler}'s counters should show
     * exactly one "valid" and one "invalid" increment.
     */
    @Test
    void composedHandlerDispatchesThreeCapabilityTypes() throws InterruptedException {
        MetricsHandler mh = new MetricsHandler();
        CapabilityHandler<Capability<?>> composed =
                CapabilityHandler.compose(new ValidationHandler(), mh, new ConsoleLogHandler());

        CountDownLatch latch = new CountDownLatch(2);

        Pid actor = new EffectActorBuilder<>(
            system,
            (ValidateRequest req) -> Effect.generate(ctx -> {
                Boolean isValid = ctx.perform(
                        new ValidationCapability.HasMinLength(req.text(), 5));
                ctx.perform(new MetricsCapability.Increment(isValid ? "valid" : "invalid"));
                ctx.perform(new LogCapability.Info(
                        "[composed] \"" + req.text() + "\" → " + isValid));
                latch.countDown();
                return Unit.unit();
            }, composed)
        ).withId("composed-actor").spawn();

        actor.tell(new ValidateRequest("hello world", null)); // length 11 ≥ 5 → valid
        actor.tell(new ValidateRequest("hi",          null)); // length 2  < 5 → invalid

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(1, mh.counters.getOrDefault("valid",   new AtomicInteger(0)).get());
        assertEquals(1, mh.counters.getOrDefault("invalid", new AtomicInteger(0)).get());
    }
}
```

Commit:
```bash
git add lib/src/test/java/examples/EffectCapabilityExample.java
git commit -m "feat(10-1): add EffectCapabilityExample demonstrating custom domain capabilities"
```

---

### Task 2 — Run tests and verify

```bash
./gradlew test --no-daemon
```

Must produce BUILD SUCCESSFUL. The new class adds 3 tests.

Expected minimum: **360 tests** (357 existing + 3 new).

No commit for this task — verification only.

---

## Potential deviations and fixes

**If `CapabilityHandler.compose(h1, h2, h3)` doesn't accept raw (non-widened) handlers:**

Call `.widen()` on each before composing:
```java
CapabilityHandler<Capability<?>> composed = CapabilityHandler.compose(
    new ValidationHandler().widen(), mh.widen(), new ConsoleLogHandler().widen());
```

**If `Boolean nonEmpty = ctx.perform(new ValidationCapability.IsNonEmpty(...))` won't compile:**

Use an explicit cast:
```java
@SuppressWarnings("unchecked")
Boolean nonEmpty = (Boolean) (Object) ctx.perform(new ValidationCapability.IsNonEmpty(req.text()));
```
Or switch the test to use `Effect.from()` + `withCapabilityHandler()`:
```java
Effect.<RuntimeException, Boolean>from(new ValidationCapability.IsNonEmpty(req.text()))
    .flatMap(nonEmpty -> Effect.<RuntimeException, Boolean>from(
            new ValidationCapability.HasMinLength(req.text(), 5))
        .flatMap(hasMin -> Effect.suspend(() -> {
            req.replyTo().tell(new ValidationResult(req.text(), nonEmpty && hasMin));
            return Unit.unit();
        })))
```
And replace `.withId(...)` with `.withCapabilityHandler(new ValidationHandler().widen()).withId(...)`.

**If Test 3 `ValidateRequest(text, null)` causes NullPointerException (null replyTo):**

Add a no-arg variant or use a separate message type that has no replyTo:
```java
record ValidateRequest(String text, Pid replyTo) {
    ValidateRequest(String text) { this(text, null); }
}
```
Or define a separate `record Query(String text) {}` for Test 3 and register a separate
`EffectActorBuilder` for it.

**If sealed switch on `ValidationCapability` is non-exhaustive:**

Ensure both `IsNonEmpty` and `HasMinLength` appear in the `permits` clause. Java 21 sealed
switches require exhaustive coverage or a default arm.

---

## Verification

- [ ] `EffectCapabilityExample.java` created with 3 tests
- [ ] Test 1: `validationCapabilityReturnsBooleanResults` — Boolean captured from ctx.perform(), results forwarded via reply-to Pid
- [ ] Test 2: `metricsHandlerTracksCountersAcrossMessages` — counter == 3, gauge == 7.0 after 3 items
- [ ] Test 3: `composedHandlerDispatchesThreeCapabilityTypes` — "valid"==1, "invalid"==1 in MetricsHandler
- [ ] `./gradlew test` → BUILD SUCCESSFUL, ≥ 360 tests pass

## Success Criteria

All three tests pass. The example clearly shows:
1. Custom `Capability<R>` with non-Unit return type and how to capture the result
2. Stateful handler pattern (hold reference for test assertions)
3. `CapabilityHandler.compose()` dispatching to the correct handler per capability type

## Output
- Created: `lib/src/test/java/examples/EffectCapabilityExample.java`
