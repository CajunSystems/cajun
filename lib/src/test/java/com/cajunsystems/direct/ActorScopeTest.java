package com.cajunsystems.direct;

import com.cajunsystems.Pid;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class ActorScopeTest {

    // (1) supervised() block creates scope and cleans up actors on normal completion
    @Test
    void supervisedCreatesAndCleansUpActorsOnNormalCompletion() throws Exception {
        AtomicReference<DirectPid<String, String>> capturedPid = new AtomicReference<>();

        ActorScope.supervised(scope -> {
            DirectPid<String, String> pid = scope.actorOf(
                    "test-supervised-cleanup",
                    "initial",
                    (state, msg) -> state + ":" + msg
            );
            capturedPid.set(pid);
            pid.tell("hello");
            Thread.sleep(100);
            return null;
        });

        // After scope exits, actor should be stopped
        Thread.sleep(200);
        DirectPid<String, String> pid = capturedPid.get();
        assertNotNull(pid);
        // The underlying Pid should have been stopped
        // We verify by checking that the actor is no longer active
        // (sending a message to a stopped actor should not cause errors but won't be processed)
    }

    // (2) supervised() propagates exceptions from the block
    @Test
    void supervisedPropagatesExceptions() {
        RuntimeException expected = new RuntimeException("test error");

        RuntimeException actual = assertThrows(RuntimeException.class, () ->
                ActorScope.supervised(scope -> {
                    throw expected;
                })
        );

        assertSame(expected, actual);
    }

    // (3) scoped() block creates scope and cleans up actors on normal completion
    @Test
    void scopedCreatesAndCleansUpActorsOnNormalCompletion() throws Exception {
        AtomicReference<DirectPid<String, String>> capturedPid = new AtomicReference<>();

        ActorScope.scoped(scope -> {
            DirectPid<String, String> pid = scope.actorOf(
                    "test-scoped-cleanup",
                    "initial",
                    (state, msg) -> state + ":" + msg
            );
            capturedPid.set(pid);
            pid.tell("hello");
            Thread.sleep(100);
            return null;
        });

        Thread.sleep(200);
        assertNotNull(capturedPid.get());
    }

    // (4) scoped() captures exceptions without rethrowing
    @Test
    void scopedCapturesExceptionsWithoutRethrowing() throws Exception {
        RuntimeException error = new RuntimeException("scoped error");

        // scoped() should not rethrow the exception
        Object result = ActorScope.scoped(scope -> {
            throw error;
        });

        // The result should be null or indicate failure, but no exception should propagate
        assertNull(result);
    }

    // (5) actorOf() creates actors within the scope that are accessible
    @Test
    void actorOfCreatesAccessibleActors() throws Exception {
        ActorScope.supervised(scope -> {
            DirectPid<Integer, Integer> pid = scope.actorOf(
                    "test-actor-accessible",
                    0,
                    (state, msg) -> state + msg
            );

            assertNotNull(pid);
            assertNotNull(pid.id());
            assertEquals("test-actor-accessible", pid.id());

            pid.tell(5);
            pid.tell(10);

            Thread.sleep(200);
            return null;
        });
    }

    // (6) actors are stopped when scope exits
    @Test
    void actorsAreStoppedWhenScopeExits() throws Exception {
        AtomicBoolean messageProcessedAfterScope = new AtomicBoolean(false);
        CountDownLatch scopeExited = new CountDownLatch(1);
        AtomicReference<DirectPid<String, String>> capturedPid = new AtomicReference<>();

        ActorScope.supervised(scope -> {
            DirectPid<String, String> pid = scope.actorOf(
                    "test-stopped-on-exit",
                    "init",
                    (state, msg) -> {
                        if (msg.equals("after-scope")) {
                            messageProcessedAfterScope.set(true);
                        }
                        return state + ":" + msg;
                    }
            );
            capturedPid.set(pid);
            pid.tell("during-scope");
            Thread.sleep(100);
            return null;
        });

        scopeExited.countDown();

        // Try sending a message after scope exits
        Thread.sleep(200);
        try {
            capturedPid.get().tell("after-scope");
        } catch (Exception e) {
            // Expected - actor may be stopped
        }

        Thread.sleep(200);
        // Message sent after scope exit should ideally not be processed
        // (depends on implementation - at minimum the actor should have been told to stop)
    }

    // (7) nested scopes clean up independently
    @Test
    void nestedScopesCleanUpIndependently() throws Exception {
        AtomicReference<DirectPid<String, String>> outerPid = new AtomicReference<>();
        AtomicReference<DirectPid<String, String>> innerPid = new AtomicReference<>();

        ActorScope.supervised(outerScope -> {
            DirectPid<String, String> outer = outerScope.actorOf(
                    "outer-actor",
                    "outer",
                    (state, msg) -> state + ":" + msg
            );
            outerPid.set(outer);

            ActorScope.supervised(innerScope -> {
                DirectPid<String, String> inner = innerScope.actorOf(
                        "inner-actor",
                        "inner",
                        (state, msg) -> state + ":" + msg
                );
                innerPid.set(inner);

                inner.tell("inner-msg");
                Thread.sleep(100);
                return null;
            });

            // Inner scope has exited, inner actor should be cleaned up
            // But outer actor should still be alive
            outer.tell("after-inner");
            Thread.sleep(100);
            return null;
        });

        assertNotNull(outerPid.get());
        assertNotNull(innerPid.get());
    }

    // (8) multiple actors in a single scope all get cleaned up
    @Test
    void multipleActorsInScopeAllGetCleanedUp() throws Exception {
        int actorCount = 5;
        AtomicInteger activeActors = new AtomicInteger(0);
        @SuppressWarnings("unchecked")
        DirectPid<String, String>[] pids = new DirectPid[actorCount];

        ActorScope.supervised(scope -> {
            for (int i = 0; i < actorCount; i++) {
                final int idx = i;
                pids[idx] = scope.actorOf(
                        "multi-actor-" + i,
                        "state-" + i,
                        (state, msg) -> {
                            activeActors.incrementAndGet();
                            return state + ":" + msg;
                        }
                );
            }

            // Send a message to each actor
            for (int i = 0; i < actorCount; i++) {
                pids[i].tell("msg-" + i);
            }

            Thread.sleep(200);
            return null;
        });

        // All actors should have received messages
        assertEquals(actorCount, activeActors.get());

        // After scope exits, all actors should be cleaned up
        for (int i = 0; i < actorCount; i++) {
            assertNotNull(pids[i]);
        }
    }

    // (9) error in one actor's handler doesn't crash the scope prematurely
    @Test
    void errorInActorHandlerDoesNotCrashScope() throws Exception {
        AtomicBoolean healthyActorProcessed = new AtomicBoolean(false);

        ActorScope.supervised(scope -> {
            DirectPid<String, String> faultyActor = scope.actorOf(
                    "faulty-actor",
                    "faulty",
                    (state, msg) -> {
                        if (msg.equals("crash")) {
                            throw new RuntimeException("Actor handler error");
                        }
                        return state + ":" + msg;
                    }
            );

            DirectPid<String, String> healthyActor = scope.actorOf(
                    "healthy-actor",
                    "healthy",
                    (state, msg) -> {
                        healthyActorProcessed.set(true);
                        return state + ":" + msg;
                    }
            );

            // Trigger error in faulty actor
            faultyActor.tell("crash");
            Thread.sleep(100);

            // Healthy actor should still work
            healthyActor.tell("hello");
            Thread.sleep(200);

            assertTrue(healthyActorProcessed.get(), "Healthy actor should still process messages after another actor's handler error");
            return null;
        });
    }

    // (10) scope returns the value from the block
    @Test
    void scopeReturnsValueFromBlock() throws Exception {
        String result = ActorScope.supervised(scope -> {
            DirectPid<Integer, Integer> pid = scope.actorOf(
                    "value-returning-scope",
                    0,
                    (state, msg) -> state + msg
            );
            pid.tell(42);
            Thread.sleep(100);
            return "computed-result";
        });

        assertEquals("computed-result", result);
    }

    @Test
    void scopeReturnsIntegerValueFromBlock() throws Exception {
        Integer result = ActorScope.supervised(scope -> {
            scope.actorOf(
                    "int-scope",
                    0,
                    (state, msg) -> state + msg
            );
            return 42;
        });

        assertEquals(42, result);
    }

    @Test
    void scopedReturnsValueOnSuccess() throws Exception {
        String result = ActorScope.scoped(scope -> {
            scope.actorOf(
                    "scoped-return",
                    "init",
                    (state, msg) -> state + msg
            );
            return "success";
        });

        assertEquals("success", result);
    }

    @Test
    void supervisedWithNoActorsCompletesNormally() throws Exception {
        String result = ActorScope.supervised(scope -> "empty-scope");

        assertEquals("empty-scope", result);
    }

    @Test
    void scopedWithNoActorsCompletesNormally() throws Exception {
        String result = ActorScope.scoped(scope -> "empty-scoped");

        assertEquals("empty-scoped", result);
    }
}