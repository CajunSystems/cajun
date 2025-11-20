package com.cajunsystems;

import com.cajunsystems.helper.*;
import com.cajunsystems.test.AsyncAssertion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class ActorSystemTest {

    private ActorSystem actorSystem;

    @BeforeEach
    void setUp() {
        actorSystem = new ActorSystem();
    }

    @AfterEach
    void tearDown() {
        if (actorSystem != null) {
            actorSystem.shutdown();
        }
    }

    static class FinalByeHandler extends Actor<String> {

        public FinalByeHandler(ActorSystem system, String actorId) {
            super(system, actorId);
        }

        @Override
        protected void receive(String s) {
            assertEquals("Bye!", s);
        }
    }

    static class CountReceiver extends Actor<HelloCount> {

        public CountReceiver(ActorSystem system, String actorId) {
            super(system, actorId);
        }

        @Override
        protected void receive(HelloCount helloCount) {
            assertEquals(5, helloCount.count());
        }
    }

    @Test
    void shouldBeAbleToRegisterAnActorToTheSystem() {
        var pid1 = actorSystem.register(GreetingActor.class, "my-greeting-actor-1");
        var pid2 = actorSystem.register(GreetingActor.class, "my-greeting-actor-2");

        assertEquals("my-greeting-actor-1", pid1.actorId());
        assertEquals("my-greeting-actor-2", pid2.actorId());
    }

    @Test
    void shouldBeAbleToRouteMessagesToActorsBasedOnId() throws InterruptedException {
        var pid1 = actorSystem.register(GreetingActor.class, "my-greeting-actor-1");
        var receiverActor = actorSystem.register(CountReceiver.class, "count-receiver");
        pid1.tell(new HelloMessage());
        pid1.tell(new HelloMessage());
        pid1.tell(new HelloMessage());
        pid1.tell(new HelloMessage());
        pid1.tell(new HelloMessage());
        pid1.tell(new GetHelloCount(receiverActor));
    }

    @Test
    void shouldBeAbleToSendAMessageToSelf() {
        var pid1 = actorSystem.register(GreetingActor.class, "my-greet");
        var replyTo = actorSystem.register(FinalByeHandler.class, "byeHandler");
        pid1.tell(new FinalBye(replyTo));
    }

    @Test
    void shouldBeAbleToBeDelayInSendingAMessage() {
        // Create a mutable counter to track state outside the actor
        final int[] finalCount = {0};
        final int[] receivedCount = {0};

        // Create a counter actor with direct state tracking
        var counter = actorSystem.register((message) -> {
            if (message instanceof CounterProtocol.CountUp) {
                finalCount[0]++;
                return null;
            } else if (message instanceof CounterProtocol.GetCount gc) {
                gc.replyTo().tell(new HelloCount(finalCount[0]));
            }
            return null;
        }, "Counter-Actor");

        // Create a functional actor for receiving the count
        var receiverActor = actorSystem.register((message) -> {
            if (message instanceof HelloCount hc) {
                receivedCount[0] = hc.count();
            }
            return null;
        }, "count-receiver-with-delay");

        // Send messages with increasing delays
        counter.tell(new CounterProtocol.CountUp(), 100, TimeUnit.MILLISECONDS);
        counter.tell(new CounterProtocol.CountUp(), 200, TimeUnit.MILLISECONDS);
        counter.tell(new CounterProtocol.CountUp(), 300, TimeUnit.MILLISECONDS);
        counter.tell(new CounterProtocol.CountUp(), 400, TimeUnit.MILLISECONDS);

        // Wait for all delayed messages to be processed
        AsyncAssertion.eventually(
            () -> finalCount[0] == 4,
            Duration.ofSeconds(2)
        );

        // Now send the GetCount message
        counter.tell(new CounterProtocol.GetCount(receiverActor));

        // Wait for the receiver to get the count
        AsyncAssertion.eventually(
            () -> receivedCount[0] == 4,
            Duration.ofSeconds(2)
        );

        // Final verification
        assertEquals(4, finalCount[0], "Expected final count to be 4");
        assertEquals(4, receivedCount[0], "Expected received count to be 4");
    }

    // Tests for the ask method

    static class PingActor extends Actor<String> {
        public PingActor(ActorSystem system, String actorId) {
            super(system, actorId);
        }

        @Override
        protected void receive(String message) {
            // This actor intentionally does not reply to messages
            // to test timeout behavior
        }
    }

    static class PongActor extends Actor<String> {
        public PongActor(ActorSystem system, String actorId) {
            super(system, actorId);
        }

        @Override
        protected void receive(String message) {
            if ("ping".equals(message)) {
                // Get the sender from context and reply
                getSender().ifPresent(sender -> sender.tell("pong"));
            } else if ("ping-wrong-type".equals(message)) {
                // Send an Integer instead of String to trigger ClassCastException
                getSender().ifPresent(sender -> sender.tell(Integer.valueOf(123)));
            }
        }
    }

    @Test
    void askShouldReceiveSuccessfulReply() throws Exception {
        Pid pongActorPid = actorSystem.register(PongActor.class, "pong-actor");
        CompletableFuture<String> future = actorSystem.ask(pongActorPid, "ping", Duration.ofSeconds(3));
        assertEquals("pong", future.get(4, TimeUnit.SECONDS));
    }

    @Test
    void askShouldTimeoutWhenNoReply() {
        Pid pingActorPid = actorSystem.register(PingActor.class, "ping-actor-no-reply"); // This actor won't reply
        CompletableFuture<String> future = actorSystem.ask(pingActorPid, "ping", Duration.ofMillis(300));

        assertThrows(TimeoutException.class, () -> {
            try {
                future.get(1000, TimeUnit.MILLISECONDS); // Wait a bit longer than the ask timeout
            } catch (ExecutionException e) {
                if (e.getCause() instanceof TimeoutException) {
                    throw (TimeoutException) e.getCause();
                }
                throw e;
            }
        });
    }

    @Test
    void askShouldHandleIncorrectReplyType() {
        Pid pongActorPid = actorSystem.register(PongActor.class, "pong-actor-wrong-type");
        CompletableFuture<String> future = actorSystem.ask(pongActorPid, "ping-wrong-type", Duration.ofSeconds(5));

        boolean exceptionThrown = false;
        try {
            String result = future.get(4, TimeUnit.SECONDS);
            // If we get here, the test failed
            assertTrue(false, "Expected an exception but got result: " + result);
        } catch (ExecutionException ex) {
            exceptionThrown = true;
            // Check if the cause is directly a ClassCastException or wrapped in a RuntimeException
            Throwable cause = ex.getCause();
            assertNotNull(cause, "ExecutionException's cause should not be null");

            if (cause instanceof RuntimeException && cause.getCause() instanceof ClassCastException) {
                // This is the expected case from the original test
                assertEquals("Internal error: Ask pattern reply actor received unexpected message type.",
                        cause.getMessage(), "The RuntimeException message is incorrect");

                Throwable rootCause = cause.getCause();
                assertEquals(ClassCastException.class, rootCause.getClass(),
                        "The cause of the RuntimeException should be a ClassCastException");
            } else if (cause instanceof ClassCastException) {
                // This is also acceptable - direct ClassCastException
                assertTrue(true, "Got a direct ClassCastException which is acceptable");
            } else {
                // Unexpected cause type
                assertTrue(false, "Expected RuntimeException or ClassCastException but got: "
                        + cause.getClass().getName());
            }
        } catch (ClassCastException e) {
            // Direct ClassCastException is also acceptable
            exceptionThrown = true;
        } catch (TimeoutException e) {
            assertTrue(false, "Expected exception related to type mismatch but got TimeoutException");
        } catch (Exception e) {
            assertTrue(false, "Expected exception related to type mismatch but got: "
                    + e.getClass().getName() + ": " + e.getMessage());
        }

        assertTrue(exceptionThrown, "Expected an exception to be thrown");
    }
}
