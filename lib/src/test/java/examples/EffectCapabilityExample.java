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
