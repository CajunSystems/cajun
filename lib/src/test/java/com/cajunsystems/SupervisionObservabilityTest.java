package com.cajunsystems;

import com.cajunsystems.handler.Handler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that a parent handler can observe child failures and restarts via the
 * {@code onChildFailed} / {@code onChildRestarted} callbacks — not just configure a strategy.
 */
class SupervisionObservabilityTest {

    private ActorSystem system;

    @BeforeEach
    void setUp() {
        system = new ActorSystem();
    }

    @AfterEach
    void tearDown() {
        if (system != null) {
            system.shutdown();
        }
        system = null;
    }

    static final List<String> events = new CopyOnWriteArrayList<>();

    /** Parent that records supervision observations. */
    public static class ObservingParent implements Handler<String> {
        @Override
        public void receive(String message, ActorContext context) {
            // no-op
        }

        @Override
        public void onChildFailed(Pid child, Throwable cause, ActorContext context) {
            events.add("failed:" + child.actorId() + ":" + cause.getMessage());
        }

        @Override
        public void onChildRestarted(Pid child, ActorContext context) {
            events.add("restarted:" + child.actorId());
        }
    }

    /** Child that throws on "boom". */
    public static class CrashingChild implements Handler<String> {
        @Override
        public void receive(String message, ActorContext context) {
            if ("boom".equals(message)) {
                throw new IllegalStateException("kaboom");
            }
        }
    }

    private static void awaitCondition(BooleanSupplier condition, long timeoutMillis, String message)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError(message + " (events=" + events + ")");
    }

    @Test
    void parentObservesChildFailureAndRestart() throws Exception {
        events.clear();

        Pid parent = system.actorOf(new ObservingParent())
                .withId("sup")
                .withSupervisionStrategy(SupervisionStrategy.RESTART)
                .spawn();

        AtomicReference<Pid> childRef = new AtomicReference<>(
                system.actorOf(new CrashingChild())
                        .withId("worker")
                        .withParent(system.getActor(parent))
                        .withSupervisionStrategy(SupervisionStrategy.ESCALATE)
                        .spawn());

        childRef.get().tell("boom");

        awaitCondition(() -> events.stream().anyMatch(e -> e.startsWith("failed:"))
                        && events.stream().anyMatch(e -> e.startsWith("restarted:")),
                3000, "parent should observe both failure and restart");

        assertTrue(events.stream().anyMatch(e -> e.contains("kaboom")),
                "failure event should carry the cause message");
    }
}
