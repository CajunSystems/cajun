package com.cajunsystems.direct;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ActorScope} and generator-style actors via {@link ActorScope#actor}.
 */
class ActorScopeTest {

    // -------------------------------------------------------------------------
    // Message types
    // -------------------------------------------------------------------------

    sealed interface CalcMsg {
        record Add(double a, double b)      implements CalcMsg {}
        record Multiply(double a, double b) implements CalcMsg {}
        record Echo(String text)            implements CalcMsg {}
    }

    sealed interface CounterMsg {
        record Increment() implements CounterMsg {}
        record GetValue() implements CounterMsg {}
        record Reset()    implements CounterMsg {}
    }

    // -------------------------------------------------------------------------
    // Generator-style actor: basic call
    // -------------------------------------------------------------------------

    @Test
    void actor_callReturnsCorrectReply() {
        try (ActorScope scope = ActorScope.open()) {
            DirectPid<CalcMsg, Double> calc = scope.actor(channel -> {
                while (channel.isOpen()) {
                    channel.handle(msg -> switch (msg) {
                        case CalcMsg.Add(double a, double b)      -> a + b;
                        case CalcMsg.Multiply(double a, double b) -> a * b;
                        case CalcMsg.Echo _                       -> 0.0;
                    });
                }
            });

            assertEquals(7.0, calc.call(new CalcMsg.Add(3, 4)));
            assertEquals(42.0, calc.call(new CalcMsg.Multiply(6, 7)));
        }
    }

    @Test
    void actor_sequentialCallsPreserveOrder() {
        try (ActorScope scope = ActorScope.open()) {
            List<String> received = new CopyOnWriteArrayList<>();
            DirectPid<CalcMsg, Double> actor = scope.actor(channel -> {
                while (channel.isOpen()) {
                    channel.handle(msg -> {
                        received.add(msg.toString());
                        return 0.0;
                    });
                }
            });

            actor.call(new CalcMsg.Add(1, 0));
            actor.call(new CalcMsg.Add(2, 0));
            actor.call(new CalcMsg.Add(3, 0));

            assertEquals(3, received.size());
            assertTrue(received.get(0).contains("1.0"));
            assertTrue(received.get(1).contains("2.0"));
            assertTrue(received.get(2).contains("3.0"));
        }
    }

    // -------------------------------------------------------------------------
    // Generator-style actor: tell (fire-and-forget)
    // -------------------------------------------------------------------------

    @Test
    void actor_tellIsProcessedWithoutBlocking() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(3);

        try (ActorScope scope = ActorScope.open()) {
            DirectPid<CalcMsg, Double> actor = scope.actor(channel -> {
                while (channel.isOpen()) {
                    channel.handle(msg -> {
                        latch.countDown();
                        return 0.0;
                    });
                }
            });

            actor.tell(new CalcMsg.Add(1, 0));
            actor.tell(new CalcMsg.Add(2, 0));
            actor.tell(new CalcMsg.Add(3, 0));

            assertTrue(latch.await(5, TimeUnit.SECONDS), "All tells should be processed");
        }
    }

    // -------------------------------------------------------------------------
    // Generator-style actor: callAsync
    // -------------------------------------------------------------------------

    @Test
    void actor_callAsyncReturnsFuture() throws Exception {
        try (ActorScope scope = ActorScope.open()) {
            DirectPid<CalcMsg, Double> calc = scope.actor(channel -> {
                while (channel.isOpen()) {
                    channel.handle(msg -> switch (msg) {
                        case CalcMsg.Add(double a, double b)      -> a + b;
                        case CalcMsg.Multiply(double a, double b) -> a * b;
                        case CalcMsg.Echo _                       -> 0.0;
                    });
                }
            });

            CompletableFuture<Double> future = calc.callAsync(new CalcMsg.Add(10, 20));
            assertEquals(30.0, future.get(5, TimeUnit.SECONDS));
        }
    }

    // -------------------------------------------------------------------------
    // Generator-style actor: error propagation
    // -------------------------------------------------------------------------

    @Test
    void actor_throwingHandlerPropagatesAsDirectActorException() {
        try (ActorScope scope = ActorScope.open()) {
            DirectPid<CalcMsg, Double> calc = scope.actor(channel -> {
                while (channel.isOpen()) {
                    channel.handle(msg -> {
                        if (msg instanceof CalcMsg.Add(double a, double b) && b == 0) {
                            throw new ArithmeticException("zero");
                        }
                        return 0.0;
                    });
                }
            });

            DirectActorException ex = assertThrows(DirectActorException.class,
                    () -> calc.call(new CalcMsg.Add(1, 0)));
            assertNotNull(ex.getCause());
            assertTrue(ex.getCause().getMessage().contains("zero"));
        }
    }

    // -------------------------------------------------------------------------
    // Generator-style actor: stateful counter (loop maintains state internally)
    // -------------------------------------------------------------------------

    @Test
    void actor_loopCanMaintainInternalState() {
        try (ActorScope scope = ActorScope.open()) {
            DirectPid<CounterMsg, Integer> counter = scope.actor(channel -> {
                // State lives as a local variable in the loop — safe because the channel
                // delivers one message at a time.
                int[] state = {0};
                while (channel.isOpen()) {
                    channel.handle(msg -> switch (msg) {
                        case CounterMsg.Increment _ -> ++state[0];
                        case CounterMsg.GetValue _  -> state[0];
                        case CounterMsg.Reset _     -> { state[0] = 0; yield 0; }
                    });
                }
            });

            assertEquals(1, counter.call(new CounterMsg.Increment()));
            assertEquals(2, counter.call(new CounterMsg.Increment()));
            assertEquals(3, counter.call(new CounterMsg.Increment()));
            assertEquals(3, counter.call(new CounterMsg.GetValue()));
            assertEquals(0, counter.call(new CounterMsg.Reset()));
            assertEquals(0, counter.call(new CounterMsg.GetValue()));
        }
    }

    // -------------------------------------------------------------------------
    // Generator-style: handle() return value drives the loop
    // -------------------------------------------------------------------------

    @Test
    void actor_handleReturnValueStyle() {
        try (ActorScope scope = ActorScope.open()) {
            DirectPid<CalcMsg, Double> calc = scope.actor(channel -> {
                // Alternative loop style: use handle's return value as the condition
                //noinspection StatementWithEmptyBody
                while (channel.handle(msg -> switch (msg) {
                    case CalcMsg.Add(double a, double b)      -> a + b;
                    case CalcMsg.Multiply(double a, double b) -> a * b;
                    case CalcMsg.Echo _                       -> -1.0;
                })) { /* loop body intentionally empty */ }
            });

            assertEquals(5.0, calc.call(new CalcMsg.Add(2, 3)));
            assertEquals(20.0, calc.call(new CalcMsg.Multiply(4, 5)));
        }
    }

    // -------------------------------------------------------------------------
    // Scope lifecycle: actors stopped on close
    // -------------------------------------------------------------------------

    @Test
    void scope_actorsAreStoppedOnClose() throws InterruptedException {
        AtomicBoolean loopExited = new AtomicBoolean(false);
        CountDownLatch loopExitLatch = new CountDownLatch(1);

        ActorScope scope = ActorScope.open();
        DirectPid<CalcMsg, Double> calc = scope.actor(channel -> {
            while (channel.isOpen()) {
                channel.handle(msg -> switch (msg) {
                    case CalcMsg.Add(double a, double b)      -> a + b;
                    case CalcMsg.Multiply(double a, double b) -> a * b;
                    case CalcMsg.Echo _                       -> 0.0;
                });
            }
            loopExited.set(true);
            loopExitLatch.countDown();
        });

        // Actor is functional before close
        assertEquals(7.0, calc.call(new CalcMsg.Add(3, 4)));

        scope.close();

        // Loop should exit shortly after scope closes
        assertTrue(loopExitLatch.await(5, TimeUnit.SECONDS), "Loop should exit when scope closes");
        assertTrue(loopExited.get());
    }

    @Test
    void scope_multipleActorsStoppedOnClose() throws InterruptedException {
        AtomicInteger stoppedCount = new AtomicInteger(0);
        CountDownLatch allStopped = new CountDownLatch(3);

        ActorScope scope = ActorScope.open();
        for (int i = 0; i < 3; i++) {
            scope.actor(channel -> {
                while (channel.isOpen()) {
                    channel.handle(msg -> 0.0);
                }
                stoppedCount.incrementAndGet();
                allStopped.countDown();
            });
        }

        scope.close();

        assertTrue(allStopped.await(5, TimeUnit.SECONDS), "All loops should exit");
        assertEquals(3, stoppedCount.get());
    }

    // -------------------------------------------------------------------------
    // Scope: handler-style actors (ScopedPidBuilder)
    // -------------------------------------------------------------------------

    @Test
    void scope_handlerStyleActorWorks() {
        try (ActorScope scope = ActorScope.open()) {
            DirectPid<CalcMsg, Double> calc = scope.actorOf(new CalcHandler())
                    .withId("scoped-calc")
                    .withTimeout(Duration.ofSeconds(5))
                    .spawn();

            assertEquals(9.0, calc.call(new CalcMsg.Add(4, 5)));
            assertEquals(12.0, calc.call(new CalcMsg.Multiply(3, 4)));
        }
    }

    @Test
    void scope_statefulHandlerStyleActorWorks() {
        try (ActorScope scope = ActorScope.open()) {
            DirectPid<CounterMsg, Integer> counter = scope
                    .statefulActorOf(new CounterHandler(), 0)
                    .withId("scoped-counter")
                    .spawn();

            assertEquals(1, counter.call(new CounterMsg.Increment()));
            assertEquals(2, counter.call(new CounterMsg.Increment()));
            assertEquals(2, counter.call(new CounterMsg.GetValue()));
        }
    }

    // -------------------------------------------------------------------------
    // Scope: sharing an existing DirectActorSystem
    // -------------------------------------------------------------------------

    @Test
    void scope_sharedSystemNotShutDownOnClose() {
        DirectActorSystem shared = new DirectActorSystem();
        try {
            try (ActorScope scope = ActorScope.open(shared)) {
                DirectPid<CalcMsg, Double> calc = scope.actor(channel -> {
                    while (channel.isOpen()) {
                        channel.handle(msg -> switch (msg) {
                            case CalcMsg.Add(double a, double b)      -> a + b;
                            case CalcMsg.Multiply(double a, double b) -> a * b;
                            case CalcMsg.Echo _                       -> 0.0;
                        });
                    }
                });
                assertEquals(3.0, calc.call(new CalcMsg.Add(1, 2)));
            } // scope closed here, but shared system should still be alive

            // Spawn a new actor in the shared system to verify it's still running
            DirectPid<CalcMsg, Double> another = shared.actorOf(new CalcHandler())
                    .withTimeout(Duration.ofSeconds(5))
                    .spawn();
            assertEquals(10.0, another.call(new CalcMsg.Add(4, 6)));
        } finally {
            shared.shutdown();
        }
    }

    // -------------------------------------------------------------------------
    // Custom default timeout for generator actor
    // -------------------------------------------------------------------------

    @Test
    void actor_customDefaultTimeout() {
        try (ActorScope scope = ActorScope.open()) {
            DirectPid<CalcMsg, Double> calc = scope.actor(
                    channel -> {
                        while (channel.isOpen()) {
                            channel.handle(msg -> 1.0);
                        }
                    },
                    Duration.ofSeconds(10));

            assertEquals(Duration.ofSeconds(10), calc.defaultTimeout());
            assertEquals(1.0, calc.call(new CalcMsg.Add(0, 0)));
        }
    }

    // -------------------------------------------------------------------------
    // Parallel virtual thread calls
    // -------------------------------------------------------------------------

    @Test
    void actor_parallelCallsFromVirtualThreads() throws Exception {
        try (ActorScope scope = ActorScope.open()) {
            DirectPid<CalcMsg, Double> calc = scope.actor(channel -> {
                while (channel.isOpen()) {
                    channel.handle(msg -> switch (msg) {
                        case CalcMsg.Multiply(double a, double b) -> a * b;
                        default -> 0.0;
                    });
                }
            });

            int n = 10;
            var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
            List<CompletableFuture<Double>> futures = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                final int val = i;
                futures.add(CompletableFuture.supplyAsync(
                        () -> calc.call(new CalcMsg.Multiply(val, val)),
                        executor));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(10, TimeUnit.SECONDS);

            for (int i = 0; i < n; i++) {
                assertEquals((double) i * i, futures.get(i).get());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Handler helpers used in tests
    // -------------------------------------------------------------------------

    static class CalcHandler implements DirectHandler<CalcMsg, Double> {
        @Override
        public Double handle(CalcMsg msg, DirectContext context) {
            return switch (msg) {
                case CalcMsg.Add(double a, double b)      -> a + b;
                case CalcMsg.Multiply(double a, double b) -> a * b;
                case CalcMsg.Echo _                       -> 0.0;
            };
        }
    }

    static class CounterHandler implements StatefulDirectHandler<Integer, CounterMsg, Integer> {
        @Override
        public DirectResult<Integer, Integer> handle(
                CounterMsg msg, Integer state, DirectContext context) {
            return switch (msg) {
                case CounterMsg.Increment _ -> DirectResult.of(state + 1, state + 1);
                case CounterMsg.GetValue _  -> DirectResult.of(state, state);
                case CounterMsg.Reset _     -> DirectResult.of(0, 0);
            };
        }
    }
}
