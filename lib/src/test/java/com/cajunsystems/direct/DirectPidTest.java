package com.cajunsystems.direct;

import com.cajunsystems.Pid;
import com.cajunsystems.ActorSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

class DirectPidTest {

    private ActorSystem actorSystem;

    @BeforeEach
    void setUp() {
        actorSystem = new ActorSystem();
    }

    @AfterEach
    void tearDown() {
        actorSystem.shutdown();
    }

    // Helper: a simple echo actor that wraps responses in an envelope
    // Message types for ask pattern
    sealed interface Request permits EchoRequest, SlowRequest, AddRequest, FailRequest {}
    record EchoRequest(String value, CompletableFuture<String> replyTo) implements Request {}
    record SlowRequest(long delayMs, String value, CompletableFuture<String> replyTo) implements Request {}
    record AddRequest(int amount, CompletableFuture<Integer> replyTo) implements Request {}
    record FailRequest(CompletableFuture<String> replyTo) implements Request {}

    // Simple fire-and-forget message
    record FireAndForget(String value) {}

    @Test
    @Timeout(5)
    void tellSendsMessageToUnderlyingActor() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean received = new AtomicBoolean(false);

        Pid<String> pid = actorSystem.actorOf("tell-test", (state, msg) -> {
            received.set(true);
            latch.countDown();
            return state;
        }, "initial");

        DirectPid<String> directPid = new DirectPid<>(pid);
        directPid.tell("hello");

        assertTrue(latch.await(2, TimeUnit.SECONDS), "Message should be received");
        assertTrue(received.get(), "Actor should have processed the message");
    }

    @Test
    @Timeout(5)
    void askSendsMessageAndBlocksUntilResponseReceived() throws Exception {
        Pid<Request> pid = actorSystem.actorOf("ask-echo", (state, msg) -> {
            if (msg instanceof EchoRequest echoRequest) {
                echoRequest.replyTo().complete("echo:" + echoRequest.value());
            }
            return state;
        }, "idle");

        DirectPid<Request> directPid = new DirectPid<>(pid);

        CompletableFuture<String> future = new CompletableFuture<>();
        directPid.tell(new EchoRequest("hello", future));
        String result = future.get(2, TimeUnit.SECONDS);

        assertEquals("echo:hello", result);
    }

    @Test
    @Timeout(5)
    void askWithTimeoutThrowsTimeoutExceptionWhenNoResponse() {
        // Actor that never completes the future
        Pid<Request> pid = actorSystem.actorOf("ask-timeout", (state, msg) -> {
            // Intentionally do NOT complete the future
            return state;
        }, "idle");

        DirectPid<Request> directPid = new DirectPid<>(pid);

        CompletableFuture<String> future = new CompletableFuture<>();
        directPid.tell(new EchoRequest("hello", future));

        assertThrows(TimeoutException.class, () -> future.get(100, TimeUnit.MILLISECONDS));
    }

    @Test
    @Timeout(5)
    void askReturnsCorrectResponseFromActorBehavior() throws Exception {
        AtomicInteger counter = new AtomicInteger(0);

        Pid<Request> pid = actorSystem.actorOf("ask-correct", (state, msg) -> {
            if (msg instanceof AddRequest addRequest) {
                int newVal = counter.addAndGet(addRequest.amount());
                addRequest.replyTo().complete(newVal);
            }
            return state;
        }, "counting");

        DirectPid<Request> directPid = new DirectPid<>(pid);

        CompletableFuture<Integer> future1 = new CompletableFuture<>();
        directPid.tell(new AddRequest(5, future1));
        assertEquals(5, future1.get(2, TimeUnit.SECONDS));

        CompletableFuture<Integer> future2 = new CompletableFuture<>();
        directPid.tell(new AddRequest(3, future2));
        assertEquals(8, future2.get(2, TimeUnit.SECONDS));
    }

    @Test
    @Timeout(10)
    void multipleSequentialAskCallsWorkCorrectly() throws Exception {
        Pid<Request> pid = actorSystem.actorOf("ask-sequential", (state, msg) -> {
            if (msg instanceof EchoRequest echoRequest) {
                echoRequest.replyTo().complete("reply:" + echoRequest.value());
            }
            return state;
        }, "idle");

        DirectPid<Request> directPid = new DirectPid<>(pid);

        for (int i = 0; i < 20; i++) {
            CompletableFuture<String> future = new CompletableFuture<>();
            directPid.tell(new EchoRequest("msg-" + i, future));
            String result = future.get(2, TimeUnit.SECONDS);
            assertEquals("reply:msg-" + i, result);
        }
    }

    @Test
    @Timeout(5)
    void tellDoesNotBlockTheCaller() throws Exception {
        CountDownLatch processingLatch = new CountDownLatch(1);
        CountDownLatch messageLatch = new CountDownLatch(1);

        Pid<String> pid = actorSystem.actorOf("tell-nonblock", (state, msg) -> {
            try {
                // Block the actor for a while
                processingLatch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            messageLatch.countDown();
            return state;
        }, "initial");

        DirectPid<String> directPid = new DirectPid<>(pid);

        long start = System.nanoTime();
        directPid.tell("hello");
        long elapsed = System.nanoTime() - start;

        // tell() should return almost immediately (well under 1 second)
        assertTrue(elapsed < 1_000_000_000L, "tell() should not block the caller; took " + elapsed + "ns");

        // Now unblock the actor
        processingLatch.countDown();
        assertTrue(messageLatch.await(5, TimeUnit.SECONDS), "Actor should eventually process the message");
    }

    @Test
    @Timeout(5)
    void directPidWrapsUnderlyingPidCorrectly() {
        Pid<String> pid = actorSystem.actorOf("wrap-test", (state, msg) -> state, "initial");

        DirectPid<String> directPid = new DirectPid<>(pid);

        assertNotNull(directPid);
        assertEquals(pid, directPid.underlying());
    }

    @Test
    @Timeout(5)
    void askWorksWithDifferentMessageAndResponseTypes() throws Exception {
        // Integer request -> String response
        sealed interface TypedRequest permits IntToStringRequest, StringToIntRequest {}
        record IntToStringRequest(int value, CompletableFuture<String> replyTo) implements TypedRequest {}
        record StringToIntRequest(String value, CompletableFuture<Integer> replyTo) implements TypedRequest {}

        Pid<TypedRequest> pid = actorSystem.actorOf("ask-types", (state, msg) -> {
            switch (msg) {
                case IntToStringRequest req -> req.replyTo().complete("number:" + req.value());
                case StringToIntRequest req -> req.replyTo().complete(req.value().length());
            }
            return state;
        }, "idle");

        DirectPid<TypedRequest> directPid = new DirectPid<>(pid);

        // Int -> String
        CompletableFuture<String> stringFuture = new CompletableFuture<>();
        directPid.tell(new IntToStringRequest(42, stringFuture));
        assertEquals("number:42", stringFuture.get(2, TimeUnit.SECONDS));

        // String -> Int
        CompletableFuture<Integer> intFuture = new CompletableFuture<>();
        directPid.tell(new StringToIntRequest("hello", intFuture));
        assertEquals(5, intFuture.get(2, TimeUnit.SECONDS));
    }

    @Test
    @Timeout(10)
    void concurrentAskCallsFromMultipleThreadsWorkCorrectly() throws Exception {
        Pid<Request> pid = actorSystem.actorOf("ask-concurrent", (state, msg) -> {
            if (msg instanceof EchoRequest echoRequest) {
                echoRequest.replyTo().complete("echo:" + echoRequest.value());
            }
            return state;
        }, "idle");

        DirectPid<Request> directPid = new DirectPid<>(pid);

        int threadCount = 10;
        int messagesPerThread = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<List<String>>> futures = new ArrayList<>();

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            futures.add(executor.submit(() -> {
                startLatch.await();
                List<String> results = new ArrayList<>();
                for (int m = 0; m < messagesPerThread; m++) {
                    CompletableFuture<String> future = new CompletableFuture<>();
                    directPid.tell(new EchoRequest("t" + threadId + "-m" + m, future));
                    results.add(future.get(5, TimeUnit.SECONDS));
                }
                return results;
            }));
        }

        // Start all threads simultaneously
        startLatch.countDown();

        List<String> allResults = new ArrayList<>();
        for (Future<List<String>> f : futures) {
            allResults.addAll(f.get(10, TimeUnit.SECONDS));
        }

        assertEquals(threadCount * messagesPerThread, allResults.size());

        // Verify all expected responses are present
        for (int t = 0; t < threadCount; t++) {
            for (int m = 0; m < messagesPerThread; m++) {
                assertTrue(allResults.contains("echo:t" + t + "-m" + m),
                        "Missing response for t" + t + "-m" + m);
            }
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    @Timeout(10)
    void askWithAdequateTimeoutSucceedsForSlowActors() throws Exception {
        Pid<Request> pid = actorSystem.actorOf("ask-slow", (state, msg) -> {
            if (msg instanceof SlowRequest slowRequest) {
                try {
                    Thread.sleep(slowRequest.delayMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                slowRequest.replyTo().complete("slow:" + slowRequest.value());
            }
            return state;
        }, "idle");

        DirectPid<Request> directPid = new DirectPid<>(pid);

        // Actor takes 200ms, but we give 5 seconds — should succeed
        CompletableFuture<String> future = new CompletableFuture<>();
        directPid.tell(new SlowRequest(200, "patience", future));
        String result = future.get(5, TimeUnit.SECONDS);
        assertEquals("slow:patience", result);
    }

    @Test
    @Timeout(5)
    void askWithInsufficientTimeoutFailsForSlowActors() {
        Pid<Request> pid = actorSystem.actorOf("ask-slow-fail", (state, msg) -> {
            if (msg instanceof SlowRequest slowRequest) {
                try {
                    Thread.sleep(slowRequest.delayMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                slowRequest.replyTo().complete("slow:" + slowRequest.value());
            }
            return state;
        }, "idle");

        DirectPid<Request> directPid = new DirectPid<>(pid);

        // Actor takes 2 seconds, but we only give 50ms
        CompletableFuture<String> future = new CompletableFuture<>();
        directPid.tell(new SlowRequest(2000, "impatient", future));
        assertThrows(TimeoutException.class, () -> future.get(50, TimeUnit.MILLISECONDS));
    }
}