package com.cajunsystems.direct;

import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for stateless direct-style actors via {@link DirectActorSystem}.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DirectActorSystemTest {

    private DirectActorSystem system;

    @BeforeEach
    void setUp() {
        system = new DirectActorSystem();
    }

    @AfterEach
    void tearDown() {
        system.shutdown();
    }

    // ---- Handlers used across tests ----

    static class EchoHandler implements DirectHandler<String, String> {
        @Override
        public String handle(String message, DirectContext context) {
            return "Echo: " + message;
        }
    }

    static class UpperCaseHandler implements DirectHandler<String, String> {
        @Override
        public String handle(String message, DirectContext context) {
            return message.toUpperCase();
        }
    }

    static class AddHandler implements DirectHandler<int[], Integer> {
        @Override
        public Integer handle(int[] args, DirectContext context) {
            return args[0] + args[1];
        }
    }

    static class FailingHandler implements DirectHandler<String, String> {
        @Override
        public String handle(String message, DirectContext context) {
            throw new RuntimeException("Simulated failure for: " + message);
        }
    }

    static class RecoveringHandler implements DirectHandler<String, String> {
        @Override
        public String handle(String message, DirectContext context) {
            throw new RuntimeException("boom");
        }

        @Override
        public String onError(String message, Throwable exception, DirectContext context) {
            return "recovered: " + message;
        }
    }

    static class LifecycleHandler implements DirectHandler<String, String> {
        static boolean started = false;
        static boolean stopped = false;

        @Override
        public void preStart(DirectContext context) {
            started = true;
        }

        @Override
        public void postStop(DirectContext context) {
            stopped = true;
        }

        @Override
        public String handle(String message, DirectContext context) {
            return message;
        }
    }

    // ---- Tests ----

    @Test
    @Order(1)
    void call_returnsHandlerReply() {
        DirectPid<String, String> echo = system.actorOf(new EchoHandler())
                .withId("echo")
                .spawn();

        assertEquals("Echo: hello", echo.call("hello"));
        assertEquals("Echo: world", echo.call("world"));
    }

    @Test
    @Order(2)
    void call_withCustomTimeout_returnsHandlerReply() {
        DirectPid<String, String> upper = system.actorOf(new UpperCaseHandler())
                .withId("upper")
                .spawn();

        assertEquals("HELLO", upper.call("hello", Duration.ofSeconds(5)));
    }

    @Test
    @Order(3)
    void call_withNumericMessage_returnsCorrectResult() {
        DirectPid<int[], Integer> adder = system.actorOf(new AddHandler())
                .withId("adder")
                .spawn();

        assertEquals(7, adder.call(new int[]{3, 4}));
        assertEquals(100, adder.call(new int[]{60, 40}));
    }

    @Test
    @Order(4)
    void tell_doesNotBlock_fireAndForget() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        DirectHandler<String, String> handler = (msg, ctx) -> {
            latch.countDown();
            return msg;
        };

        DirectPid<String, String> ref = system.actorOf(handler)
                .withId("tell-test")
                .spawn();

        ref.tell("go");

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Handler was not called within timeout");
    }

    @Test
    @Order(5)
    void callAsync_returnsCompletableFuture() throws Exception {
        DirectPid<String, String> echo = system.actorOf(new EchoHandler())
                .withId("echo-async")
                .spawn();

        CompletableFuture<String> future = echo.callAsync("async");
        assertEquals("Echo: async", future.get(5, TimeUnit.SECONDS));
    }

    @Test
    @Order(6)
    void failingHandler_throwsDirectActorException() {
        DirectPid<String, String> ref = system.actorOf(new FailingHandler())
                .withId("failing")
                .spawn();

        DirectActorException ex = assertThrows(DirectActorException.class,
                () -> ref.call("test"));
        assertNotNull(ex.getCause());
    }

    @Test
    @Order(7)
    void onError_recoversFallbackReply() {
        DirectPid<String, String> ref = system.actorOf(new RecoveringHandler())
                .withId("recovering")
                .spawn();

        String result = ref.call("input");
        assertEquals("recovered: input", result);
    }

    @Test
    @Order(8)
    void actorRef_pid_returnsCorrectActorId() {
        DirectPid<String, String> ref = system.actorOf(new EchoHandler())
                .withId("my-echo")
                .spawn();

        assertEquals("my-echo", ref.actorId());
        assertNotNull(ref.pid());
    }

    @Test
    @Order(9)
    void actorRef_withTimeout_changeDefaultTimeout() {
        DirectPid<String, String> ref = system.actorOf(new EchoHandler())
                .withId("timeout-test")
                .spawn();

        DirectPid<String, String> shorter = ref.withTimeout(Duration.ofSeconds(1));
        assertEquals(Duration.ofSeconds(1), shorter.defaultTimeout());
        assertNotSame(ref, shorter);
    }

    @Test
    @Order(10)
    void preStart_calledBeforeFirstMessage() throws InterruptedException {
        LifecycleHandler.started = false;
        LifecycleHandler.stopped = false;

        DirectPid<String, String> ref = system.actorOf(new LifecycleHandler())
                .withId("lifecycle")
                .spawn();

        // Small delay to allow preStart to execute
        Thread.sleep(50);
        assertTrue(LifecycleHandler.started, "preStart should have been called");
    }

    @Test
    @Order(11)
    void multipleActors_independentState() {
        DirectPid<String, String> echo1 = system.actorOf(new EchoHandler())
                .withId("echo-1")
                .spawn();

        DirectPid<String, String> echo2 = system.actorOf(new EchoHandler())
                .withId("echo-2")
                .spawn();

        assertEquals("Echo: from-1", echo1.call("from-1"));
        assertEquals("Echo: from-2", echo2.call("from-2"));
    }

    @Test
    @Order(12)
    void defaultTimeout_isThirtySeconds() {
        DirectPid<String, String> ref = system.actorOf(new EchoHandler())
                .withId("default-timeout")
                .spawn();

        assertEquals(Duration.ofSeconds(30), ref.defaultTimeout());
    }

    @Test
    @Order(13)
    void customTimeout_viaBuilder() {
        DirectPid<String, String> ref = system.actorOf(new EchoHandler())
                .withId("custom-timeout")
                .withTimeout(Duration.ofSeconds(5))
                .spawn();

        assertEquals(Duration.ofSeconds(5), ref.defaultTimeout());
        assertEquals("Echo: hi", ref.call("hi"));
    }

    @Test
    @Order(14)
    void callAsync_multipleParallelCalls() throws Exception {
        DirectPid<String, String> echo = system.actorOf(new EchoHandler())
                .withId("echo-parallel")
                .spawn();

        CompletableFuture<String> f1 = echo.callAsync("a");
        CompletableFuture<String> f2 = echo.callAsync("b");
        CompletableFuture<String> f3 = echo.callAsync("c");

        CompletableFuture.allOf(f1, f2, f3).get(10, TimeUnit.SECONDS);

        assertEquals("Echo: a", f1.get());
        assertEquals("Echo: b", f2.get());
        assertEquals("Echo: c", f3.get());
    }

    @Test
    @Order(15)
    void wrapExistingActorSystem_sharesActors() {
        com.cajunsystems.ActorSystem base = new com.cajunsystems.ActorSystem();
        DirectActorSystem direct = new DirectActorSystem(base);

        assertSame(base, direct.underlying());

        DirectPid<String, String> ref = direct.actorOf(new EchoHandler())
                .withId("shared")
                .spawn();

        assertEquals("Echo: shared", ref.call("shared"));
        direct.shutdown();
    }
}
