package com.cajunsystems.test;

import com.cajunsystems.ActorContext;
import com.cajunsystems.Pid;
import com.cajunsystems.handler.Handler;
import com.cajunsystems.handler.StatefulHandler;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Example tests demonstrating TestKit usage patterns.
 * These serve as both tests and documentation.
 */
class TestKitExampleTest {
    
    // Message types (Serializable for stateful actors)
    public record Request(String data, Pid replyTo) implements Serializable {}
    public record Response(String result) implements Serializable {}
    
    // Stateful actor messages
    public record Increment(int amount) implements Serializable {}
    public record Decrement(int amount) implements Serializable {}
    public record GetCount(Pid replyTo) implements Serializable {}
    public record CountResponse(int count) implements Serializable {}
    
    // Request-response handler
    public static class RequestHandler implements Handler<Request> {
        @Override
        public void receive(Request message, ActorContext context) {
            String result = "processed: " + message.data();
            context.tell(message.replyTo(), new Response(result));
        }
    }
    
    // Stateful counter handler
    public static class CounterHandler implements StatefulHandler<Integer, Object> {
        @Override
        public Integer receive(Object message, Integer state, ActorContext context) {
            if (message instanceof Increment inc) {
                return state + inc.amount();
            } else if (message instanceof Decrement dec) {
                return state - dec.amount();
            } else if (message instanceof GetCount gc) {
                context.tell(gc.replyTo(), new CountResponse(state));
                return state;
            }
            return state;
        }
    }
    
    @Test
    void exampleRequestResponse() {
        try (TestKit testKit = TestKit.create()) {
            // Spawn the request handler
            TestPid<Request> handler = testKit.spawn(RequestHandler.class);
            
            // Create a probe to receive the response
            TestProbe<Response> probe = testKit.createProbe();
            
            // Send request with probe as replyTo
            handler.tell(new Request("test data", probe.ref()));
            
            // Assert on the response
            Response response = probe.expectMessage(Duration.ofSeconds(1));
            assertEquals("processed: test data", response.result());
        }
    }
    
    @Test
    void exampleStatefulActor() throws InterruptedException {
        try (TestKit testKit = TestKit.create()) {
            // Spawn a stateful counter starting at 0
            TestPid<Object> counter = testKit.spawnStateful(CounterHandler.class, 0);
            
            // Create probe for responses
            TestProbe<CountResponse> probe = testKit.createProbe();
            
            // Perform operations on the counter
            counter.tell(new Increment(10));
            counter.tell(new Increment(5));
            counter.tell(new Decrement(3));
            
            // Give time for stateful actor to process and persist
            Thread.sleep(300);
            
            // Query the count
            counter.tell(new GetCount(probe.ref()));
            
            // Verify the count
            CountResponse response = probe.expectMessage(Duration.ofSeconds(2));
            assertEquals(12, response.count());
        }
    }
    
    @Test
    void exampleMultipleMessages() {
        try (TestKit testKit = TestKit.create()) {
            TestPid<Request> handler = testKit.spawn(RequestHandler.class);
            TestProbe<Response> probe = testKit.createProbe();
            
            // Send multiple requests
            handler.tell(new Request("msg1", probe.ref()));
            handler.tell(new Request("msg2", probe.ref()));
            handler.tell(new Request("msg3", probe.ref()));
            
            // Expect all responses
            var responses = probe.expectMessages(3, Duration.ofSeconds(2));
            
            assertEquals(3, responses.size());
            assertEquals("processed: msg1", responses.get(0).result());
            assertEquals("processed: msg2", responses.get(1).result());
            assertEquals("processed: msg3", responses.get(2).result());
        }
    }
    
    @Test
    void exampleExpectNoMessage() {
        try (TestKit testKit = TestKit.create()) {
            TestProbe<String> probe = testKit.createProbe();
            
            // Verify no unexpected messages arrive
            probe.expectNoMessage(Duration.ofMillis(100));
        }
    }
    
    @Test
    void exampleMessagePredicate() {
        try (TestKit testKit = TestKit.create()) {
            TestPid<Request> handler = testKit.spawn(RequestHandler.class);
            TestProbe<Response> probe = testKit.createProbe();
            
            handler.tell(new Request("important", probe.ref()));
            
            // Assert message matches a condition
            Response response = probe.expectMessage(
                r -> r.result().contains("important"),
                Duration.ofSeconds(1)
            );
            
            assertTrue(response.result().contains("processed"));
        }
    }
    
    @Test
    void exampleActorCommunication() {
        try (TestKit testKit = TestKit.create()) {
            // Create two actors
            TestPid<Request> actor1 = testKit.spawn(RequestHandler.class);
            TestPid<Request> actor2 = testKit.spawn(RequestHandler.class);
            
            // Create probe
            TestProbe<Response> probe = testKit.createProbe();
            
            // Both actors send to the same probe
            actor1.tell(new Request("from actor1", probe.ref()));
            actor2.tell(new Request("from actor2", probe.ref()));
            
            // Receive both responses (order may vary)
            var responses = probe.expectMessages(2, Duration.ofSeconds(1));
            assertEquals(2, responses.size());
        }
    }
}
