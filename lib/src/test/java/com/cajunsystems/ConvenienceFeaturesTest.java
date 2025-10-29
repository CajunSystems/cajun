package com.cajunsystems;

import com.cajunsystems.handler.Handler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ActorContext convenience features:
 * - tellSelf() for sending messages to self
 * - getLogger() for consistent logging
 * - ReplyingMessage interface for standardized reply patterns
 */
public class ConvenienceFeaturesTest {

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
    }

    // Test messages
    public sealed interface TestMessage permits Start, Tick, Stop {}
    public record Start() implements TestMessage {}
    public record Tick(int count) implements TestMessage {}
    public record Stop() implements TestMessage {}

    // ReplyingMessage test messages
    public record GetDataRequest(String key, Pid replyTo) implements ReplyingMessage {}
    public record DataResponse(String key, String value) {}

    static class SelfMessageHandler implements Handler<TestMessage> {
        private static CountDownLatch latch;
        private int tickCount = 0;

        public static void setLatch(CountDownLatch l) {
            latch = l;
        }

        @Override
        public void receive(TestMessage message, ActorContext context) {
            switch (message) {
                case Start ignored -> {
                    // Send first tick to self
                    context.tellSelf(new Tick(1));
                }
                case Tick tick -> {
                    tickCount = tick.count();
                    latch.countDown();
                    
                    if (tickCount < 3) {
                        // Send next tick to self
                        context.tellSelf(new Tick(tickCount + 1));
                    } else {
                        context.tellSelf(new Stop());
                    }
                }
                case Stop ignored -> {
                    context.stop();
                }
            }
        }
    }

    @Test
    void testTellSelfFeature() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(3);
        SelfMessageHandler.setLatch(latch);

        Pid actor = system.actorOf(SelfMessageHandler.class).spawn();
        actor.tell(new Start());

        assertTrue(latch.await(2, TimeUnit.SECONDS), "Should receive 3 ticks");
    }

    static class DelayedSelfMessageHandler implements Handler<String> {
        private static CountDownLatch latch;
        private static long[] receiveTime;

        public static void setLatch(CountDownLatch l, long[] rt) {
            latch = l;
            receiveTime = rt;
        }

        @Override
        public void receive(String message, ActorContext context) {
            if (message.equals("start")) {
                // Send delayed message to self
                context.tellSelf("delayed", 500, TimeUnit.MILLISECONDS);
            } else if (message.equals("delayed")) {
                receiveTime[0] = System.currentTimeMillis();
                latch.countDown();
            }
        }
    }

    @Test
    void testTellSelfWithDelay() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        long[] receiveTime = new long[1];
        long startTime = System.currentTimeMillis();
        DelayedSelfMessageHandler.setLatch(latch, receiveTime);

        Pid actor = system.actorOf(DelayedSelfMessageHandler.class).spawn();
        actor.tell("start");

        assertTrue(latch.await(2, TimeUnit.SECONDS), "Should receive delayed message");
        long elapsed = receiveTime[0] - startTime;
        assertTrue(elapsed >= 500, "Message should be delayed by at least 500ms, was: " + elapsed);
    }

    static class LoggingHandler implements Handler<String> {
        private static CountDownLatch latch;

        public static void setLatch(CountDownLatch l) {
            latch = l;
        }

        @Override
        public void receive(String message, ActorContext context) {
            // Test that logger is available and doesn't throw
            assertNotNull(context.getLogger(), "Logger should not be null");
            
            context.getLogger().info("Processing message: {}", message);
            context.getLogger().debug("Actor ID: {}", context.getActorId());
            
            latch.countDown();
        }
    }

    @Test
    void testLoggerFeature() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        LoggingHandler.setLatch(latch);

        Pid actor = system.actorOf(LoggingHandler.class)
            .withId("test-logger-actor")
            .spawn();
        
        actor.tell("test message");

        assertTrue(latch.await(2, TimeUnit.SECONDS), "Should process message");
    }

    static class DataHandler implements Handler<Object> {
        @Override
        public void receive(Object message, ActorContext context) {
            if (message instanceof GetDataRequest req) {
                // Simulate fetching data
                String value = "value-for-" + req.key();
                
                // Use the reply convenience method
                context.reply(req, new DataResponse(req.key(), value));
            }
        }
    }

    static class ResponseHandler implements Handler<DataResponse> {
        private static CountDownLatch latch;
        private static String[] receivedValue;

        public static void setLatch(CountDownLatch l, String[] rv) {
            latch = l;
            receivedValue = rv;
        }

        @Override
        public void receive(DataResponse response, ActorContext context) {
            receivedValue[0] = response.value();
            latch.countDown();
        }
    }

    @Test
    void testReplyingMessageInterface() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        String[] receivedValue = new String[1];
        ResponseHandler.setLatch(latch, receivedValue);

        Pid dataActor = system.actorOf(DataHandler.class).spawn();
        Pid responseActor = system.actorOf(ResponseHandler.class).spawn();

        // Send request with replyTo
        dataActor.tell(new GetDataRequest("test-key", responseActor));

        assertTrue(latch.await(2, TimeUnit.SECONDS), "Should receive response");
        assertEquals("value-for-test-key", receivedValue[0], "Should receive correct value");
    }

    static class MultiRequestHandler implements Handler<Object> {
        @Override
        public void receive(Object message, ActorContext context) {
            if (message instanceof GetDataRequest req) {
                // Use reply for clean code
                context.reply(req, new DataResponse(req.key(), "data-" + req.key()));
            }
        }
    }

    static class CountingResponseHandler implements Handler<DataResponse> {
        private static CountDownLatch latch;
        private static int[] responseCount;

        public static void setLatch(CountDownLatch l, int[] rc) {
            latch = l;
            responseCount = rc;
        }

        @Override
        public void receive(DataResponse response, ActorContext context) {
            responseCount[0]++;
            latch.countDown();
        }
    }

    @Test
    void testReplyingMessageWithMultipleRequests() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(3);
        int[] responseCount = new int[1];
        CountingResponseHandler.setLatch(latch, responseCount);

        Pid requestHandler = system.actorOf(MultiRequestHandler.class).spawn();
        Pid responseHandler = system.actorOf(CountingResponseHandler.class).spawn();

        // Send multiple requests
        requestHandler.tell(new GetDataRequest("key1", responseHandler));
        requestHandler.tell(new GetDataRequest("key2", responseHandler));
        requestHandler.tell(new GetDataRequest("key3", responseHandler));

        assertTrue(latch.await(2, TimeUnit.SECONDS), "Should receive all responses");
        assertEquals(3, responseCount[0], "Should receive exactly 3 responses");
    }

    static class CombinedHandler implements Handler<Object> {
        @Override
        public void receive(Object message, ActorContext context) {
            if (message instanceof GetDataRequest req) {
                // Use logger
                context.getLogger().info("Processing request for key: {}", req.key());
                
                // Use tellSelf and reply directly (simplified for demo)
                context.tellSelf("process-" + req.key());
                
                // Reply immediately to demonstrate the reply convenience method
                context.reply(req, new DataResponse(req.key(), "processed"));
            } else if (message instanceof String str && str.startsWith("process-")) {
                context.getLogger().debug("Processing: {}", str);
            }
        }
    }

    static class FinalResponseHandler implements Handler<DataResponse> {
        private static CountDownLatch latch;

        public static void setLatch(CountDownLatch l) {
            latch = l;
        }

        @Override
        public void receive(DataResponse response, ActorContext context) {
            context.getLogger().info("Received response: {}", response.value());
            latch.countDown();
        }
    }

    @Test
    void testCombinedFeatures() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        FinalResponseHandler.setLatch(latch);

        Pid handler = system.actorOf(CombinedHandler.class).spawn();
        Pid responseHandler = system.actorOf(FinalResponseHandler.class).spawn();

        handler.tell(new GetDataRequest("combined-test", responseHandler));

        assertTrue(latch.await(2, TimeUnit.SECONDS), "Should complete combined workflow");
    }
}
