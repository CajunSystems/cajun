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
