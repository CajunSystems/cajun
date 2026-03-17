package com.cajunsystems.direct;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the direct style API with end-to-end scenarios.
 */
class DirectStyleIntegrationTest {

    @Test
    @Timeout(10)
    void testPingPongActorsViaChannels() throws Exception {
        TypedChannel<String> pingChannel = new TypedChannel<>(10);
        TypedChannel<String> pongChannel = new TypedChannel<>(10);

        int rounds = 5;
        List<String> pingLog = new CopyOnWriteArrayList<>();
        List<String> pongLog = new CopyOnWriteArrayList<>();

        CountDownLatch done = new CountDownLatch(1);

        // Ping actor: sends "ping" on pingChannel, waits for reply on pongChannel
        Thread pingActor = Thread.ofVirtual().start(() -> {
            try {
                for (int i = 0; i < rounds; i++) {
                    pingChannel.send("ping-" + i);
                    String reply = pongChannel.receive();
                    pingLog.add(reply);
                }
                done.countDown();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Pong actor: receives from pingChannel, sends "pong" on pongChannel
        Thread pongActor = Thread.ofVirtual().start(() -> {
            try {
                for (int i = 0; i < rounds; i++) {
                    String msg = pingChannel.receive();
                    pongLog.add(msg);
                    pongChannel.send("pong-" + i);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertTrue(done.await(5, TimeUnit.SECONDS), "Ping-pong should complete within timeout");

        assertEquals(rounds, pingLog.size());
        assertEquals(rounds, pongLog.size());

        for (int i = 0; i < rounds; i++) {
            assertEquals("ping-" + i, pongLog.get(i));
            assertEquals("pong-" + i, pingLog.get(i));
        }

        pingActor.join(1000);
        pongActor.join(1000);
    }

    @Test
    @Timeout(10)
    void testRequestReplyWithDirectAsk() throws Exception {
        // Set up a responder that doubles integers
        TypedChannel<DirectAsk.Request<Integer, Integer>> requestChannel = new TypedChannel<>(10);

        Thread responder = Thread.ofVirtual().start(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    DirectAsk.Request<Integer, Integer> request = requestChannel.receive();
                    request.reply(request.message() * 2);
                }
            } catch (Exception e) {
                // Channel closed or interrupted
            }
        });

        // Use DirectAsk.ask to send requests and block for responses
        int result1 = DirectAsk.ask(requestChannel, 5, 5, TimeUnit.SECONDS);
        assertEquals(10, result1);

        int result2 = DirectAsk.ask(requestChannel, 21, 5, TimeUnit.SECONDS);
        assertEquals(42, result2);

        int result3 = DirectAsk.ask(requestChannel, 0, 5, TimeUnit.SECONDS);
        assertEquals(0, result3);

        responder.interrupt();
        responder.join(1000);
    }

    @Test
    @Timeout(10)
    void testScatterGatherWithPar() throws Exception {
        // Define work items - each function computes something
        List<Callable<Integer>> tasks = List.of(
                () -> { Thread.sleep(50); return 10; },
                () -> { Thread.sleep(30); return 20; },
                () -> { Thread.sleep(40); return 30; }
        );

        List<Integer> results = Par.par(tasks, 5, TimeUnit.SECONDS);

        assertNotNull(results);
        assertEquals(3, results.size());
        assertEquals(10, results.get(0));
        assertEquals(20, results.get(1));
        assertEquals(30, results.get(2));

        // Verify sum
        int total = results.stream().mapToInt(Integer::intValue).sum();
        assertEquals(60, total);
    }

    @Test
    @Timeout(10)
    void testSupervisedActorGroupWithErrorRecovery() throws Exception {
        AtomicInteger completedCount = new AtomicInteger(0);
        AtomicReference<Throwable> caughtError = new AtomicReference<>();

        try {
            DirectScope.supervised(scope -> {
                // Task 1: completes successfully
                scope.fork(() -> {
                    Thread.sleep(50);
                    completedCount.incrementAndGet();
                    return "task1-done";
                });

                // Task 2: fails with an exception
                scope.fork(() -> {
                    Thread.sleep(30);
                    throw new RuntimeException("Simulated failure in task 2");
                });

                // Task 3: completes successfully (may or may not run depending on scope behavior)
                scope.fork(() -> {
                    Thread.sleep(100);
                    completedCount.incrementAndGet();
                    return "task3-done";
                });

                return null;
            });
            fail("supervised should propagate the exception from the failing task");
        } catch (Exception e) {
            caughtError.set(e);
        }

        // The error from the failing task should have been propagated
        assertNotNull(caughtError.get());
        assertTrue(caughtError.get().getMessage().contains("Simulated failure") ||
                        caughtError.get().getCause() != null,
                "Error should contain the simulated failure message or have a cause");
    }

    @Test
    @Timeout(10)
    void testPipelinePattern() throws Exception {
        // Pipeline: source -> stage1 (multiply by 2) -> stage2 (add 10) -> sink
        TypedChannel<Integer> sourceToStage1 = new TypedChannel<>(10);
        TypedChannel<Integer> stage1ToStage2 = new TypedChannel<>(10);
        TypedChannel<Integer> stage2ToSink = new TypedChannel<>(10);

        int itemCount = 10;
        List<Integer> results = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);

        // Stage 1: multiply by 2
        Thread stage1 = Thread.ofVirtual().start(() -> {
            try {
                for (int i = 0; i < itemCount; i++) {
                    int value = sourceToStage1.receive();
                    stage1ToStage2.send(value * 2);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Stage 2: add 10
        Thread stage2 = Thread.ofVirtual().start(() -> {
            try {
                for (int i = 0; i < itemCount; i++) {
                    int value = stage1ToStage2.receive();
                    stage2ToSink.send(value + 10);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Sink: collect results
        Thread sink = Thread.ofVirtual().start(() -> {
            try {
                for (int i = 0; i < itemCount; i++) {
                    int value = stage2ToSink.receive();
                    results.add(value);
                }
                done.countDown();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Source: produce values 1..10
        for (int i = 1; i <= itemCount; i++) {
            sourceToStage1.send(i);
        }

        assertTrue(done.await(5, TimeUnit.SECONDS), "Pipeline should complete within timeout");

        assertEquals(itemCount, results.size());

        // Each value i should become (i * 2) + 10
        for (int i = 0; i < itemCount; i++) {
            int expected = ((i + 1) * 2) + 10;
            assertEquals(expected, results.get(i), "Pipeline result for input " + (i + 1));
        }

        stage1.join(1000);
        stage2.join(1000);
        sink.join(1000);
    }

    @Test
    @Timeout(10)
    void testSelectFromMultipleChannels() throws Exception {
        TypedChannel<String> fastChannel = new TypedChannel<>(10);
        TypedChannel<String> slowChannel = new TypedChannel<>(10);

        // Send to the fast channel first after a small delay
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(20);
                fastChannel.send("fast-message");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Send to the slow channel after a longer delay
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(200);
                slowChannel.send("slow-message");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Select should return the first available message
        DirectSelect.ChannelWithIndex<?>[] channels = new DirectSelect.ChannelWithIndex[]{
                new DirectSelect.ChannelWithIndex<>(fastChannel, 0),
                new DirectSelect.ChannelWithIndex<>(slowChannel, 1)
        };

        DirectSelect.SelectResult<?> result = DirectSelect.select(
                List.of(fastChannel, slowChannel),
                5, TimeUnit.SECONDS
        );

        assertNotNull(result, "Select should return a result");
        // The fast channel should win since it sends first
        assertEquals(0, result.channelIndex(), "Fast channel (index 0) should be selected first");
        assertEquals("fast-message", result.value());

        // Now the slow channel should also eventually have a message
        DirectSelect.SelectResult<?> result2 = DirectSelect.select(
                List.of(fastChannel, slowChannel),
                5, TimeUnit.SECONDS
        );

        assertNotNull(result2, "Second select should also return a result");
        assertEquals(1, result2.channelIndex(), "Slow channel (index 1) should be selected");
        assertEquals("slow-message", result2.value());
    }
}