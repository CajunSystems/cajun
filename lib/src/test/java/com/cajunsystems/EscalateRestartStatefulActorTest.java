package com.cajunsystems;

import com.cajunsystems.handler.Handler;
import com.cajunsystems.handler.StatefulHandler;
import com.cajunsystems.persistence.MockBatchedMessageJournal;
import com.cajunsystems.persistence.MockSnapshotStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the escalation-restart bug: a persistent child that ESCALATEs
 * to a RESTART parent must resume processing messages after the parent restarts it.
 *
 * <p>Prior to the fix, a child handled via ESCALATE was fully stopped
 * ({@link Actor#stop()} → {@code ActorSystem.shutdown(actorId)}), which removed it from
 * the system's actor registry. The parent's RESTART branch then revived it with a raw
 * {@code start()} that never re-registered it, so the restarted child's mailbox ran but
 * the system could no longer route messages to it — every subsequent {@code ask} timed
 * out forever. Additionally, the full stop shut down the persistence executor, which the
 * restart did not re-create.
 */
class EscalateRestartStatefulActorTest {

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

    /** Stateless parent that simply supervises its child. */
    public static class Parent implements Handler<String> {
        @Override
        public void receive(String message, ActorContext context) {
            // no-op supervisor
        }
    }

    /** Stateful child that increments a counter and replies, and blows up on "boom". */
    public static class Child implements StatefulHandler<Integer, String> {
        @Override
        public Integer receive(String message, Integer state, ActorContext context) {
            if ("boom".equals(message)) {
                throw new IllegalStateException("boom");
            }
            context.getSender().ifPresent(sender -> context.tell(sender, "pong:" + state));
            return state + 1;
        }
    }

    private Pid spawnChild(Pid parent, SupervisionStrategy childStrategy) {
        return system.statefulActorOf(new Child(), 0)
                .withId("child")
                .withPersistence(new MockBatchedMessageJournal<>(), new MockSnapshotStore<>())
                .withParent(system.getActor(parent))
                .withSupervisionStrategy(childStrategy)
                .spawn();
    }

    /**
     * Asks the child and retries until a reply arrives or the deadline elapses. Retrying
     * tolerates cold-start state-initialization latency and the fact that a message delivered
     * before state init is re-delivered without its sender context. It does NOT paper over the
     * escalation bug: when the restarted child is unreachable, every retry times out and this
     * method returns {@code null}.
     */
    private String pingUntilReply(Pid child, Duration overallTimeout) throws Exception {
        long deadline = System.nanoTime() + overallTimeout.toNanos();
        Exception last = null;
        while (System.nanoTime() < deadline) {
            try {
                return system.<String, String>ask(child, "ping", Duration.ofSeconds(2))
                        .get(2, TimeUnit.SECONDS);
            } catch (Exception e) {
                last = e;
                // brief pause before retrying
                Thread.sleep(50);
            }
        }
        if (last != null) {
            // Surface the last failure cause for diagnostics but return null so callers can assert.
            System.err.println("pingUntilReply exhausted; last failure: " + last);
        }
        return null;
    }

    @Test
    void escalatingChildResumesAfterParentRestart() throws Exception {
        Pid parent = system.actorOf(new Parent())
                .withId("parent")
                .withSupervisionStrategy(SupervisionStrategy.RESTART)
                .spawn();

        Pid child = spawnChild(parent, SupervisionStrategy.ESCALATE);

        // Warm up: cold state init happens here.
        String warmup = pingUntilReply(child, Duration.ofSeconds(15));
        assertNotNull(warmup, "warm-up ping should succeed");
        assertTrue(warmup.startsWith("pong:"), "warm-up ping should reply pong, got: " + warmup);

        // Trigger a crash that escalates to the parent, whose RESTART strategy restarts the child.
        try {
            system.<String, String>ask(child, "boom", Duration.ofSeconds(2)).get(3, TimeUnit.SECONDS);
        } catch (Exception expected) {
            // The failing message produces no reply; that is fine.
        }

        // After escalation + parent restart, the child must process messages again.
        String afterCrash = pingUntilReply(child, Duration.ofSeconds(15));
        assertNotNull(afterCrash,
                "Child should resume after ESCALATE -> parent RESTART, but never replied");
        assertTrue(afterCrash.startsWith("pong:"),
                "Child should resume after ESCALATE -> parent RESTART, but got: " + afterCrash);
    }

    @Test
    void selfRestartingChildResumesAfterRestart() throws Exception {
        // Control case: the self-RESTART path already worked; keep it green as a guard.
        Pid parent = system.actorOf(new Parent())
                .withId("parent")
                .withSupervisionStrategy(SupervisionStrategy.RESTART)
                .spawn();

        Pid child = spawnChild(parent, SupervisionStrategy.RESTART);

        String warmup = pingUntilReply(child, Duration.ofSeconds(15));
        assertNotNull(warmup, "warm-up ping should succeed");

        try {
            system.<String, String>ask(child, "boom", Duration.ofSeconds(2)).get(3, TimeUnit.SECONDS);
        } catch (Exception expected) {
            // no reply expected
        }

        String afterCrash = pingUntilReply(child, Duration.ofSeconds(15));
        assertNotNull(afterCrash, "Self-restarting child should resume, but never replied");
        assertTrue(afterCrash.startsWith("pong:"),
                "Self-restarting child should resume, but got: " + afterCrash);
    }
}
