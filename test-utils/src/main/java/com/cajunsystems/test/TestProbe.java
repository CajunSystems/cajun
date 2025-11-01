package com.cajunsystems.test;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.handler.Handler;
import com.cajunsystems.ActorContext;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * A test probe for capturing and asserting on messages sent to an actor.
 * 
 * <p>Usage:
 * <pre>{@code
 * TestProbe<Response> probe = testKit.createProbe();
 * actor.tell(new Request("data", probe.ref()));
 * 
 * Response response = probe.expectMessage(Duration.ofSeconds(1));
 * assertEquals("processed", response.value());
 * }</pre>
 * 
 * @param <T> the message type
 */
public class TestProbe<T> {
    
    private final Pid pid;
    private final BlockingQueue<T> receivedMessages;
    
    private TestProbe(Pid pid, BlockingQueue<T> receivedMessages) {
        this.pid = pid;
        this.receivedMessages = receivedMessages;
    }
    
    /**
     * Creates a new test probe.
     * 
     * @param system the actor system
     * @param <T> the message type
     * @return a new TestProbe
     */
    public static <T> TestProbe<T> create(ActorSystem system) {
        BlockingQueue<T> queue = new LinkedBlockingQueue<>();
        
        Pid pid = system.actorOf(new Handler<T>() {
            @Override
            public void receive(T message, ActorContext context) {
                queue.offer(message);
            }
        }).spawn();
        
        return new TestProbe<>(pid, queue);
    }
    
    /**
     * Creates a new test probe with a specific name.
     * 
     * @param system the actor system
     * @param name the name for the probe actor
     * @param <T> the message type
     * @return a new TestProbe
     */
    public static <T> TestProbe<T> create(ActorSystem system, String name) {
        BlockingQueue<T> queue = new LinkedBlockingQueue<>();
        
        Pid pid = system.actorOf(new Handler<T>() {
            @Override
            public void receive(T message, ActorContext context) {
                queue.offer(message);
            }
        }).withId(name).spawn();
        
        return new TestProbe<>(pid, queue);
    }
    
    /**
     * Gets the Pid of this probe to use as a replyTo address.
     * 
     * @return the probe's Pid
     */
    public Pid ref() {
        return pid;
    }
    
    /**
     * Expects a message within the given timeout.
     * 
     * @param timeout the maximum time to wait
     * @return the received message
     * @throws AssertionError if no message is received within the timeout
     */
    public T expectMessage(Duration timeout) {
        try {
            T message = receivedMessages.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (message == null) {
                throw new AssertionError("Expected message within " + timeout + " but none received");
            }
            return message;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for message", e);
        }
    }
    
    /**
     * Expects a message of a specific type within the given timeout.
     * 
     * @param messageType the expected message type
     * @param timeout the maximum time to wait
     * @param <M> the specific message type
     * @return the received message cast to the expected type
     * @throws AssertionError if no message is received or the type doesn't match
     */
    @SuppressWarnings("unchecked")
    public <M extends T> M expectMessage(Class<M> messageType, Duration timeout) {
        T message = expectMessage(timeout);
        if (!messageType.isInstance(message)) {
            throw new AssertionError(
                "Expected message of type " + messageType.getName() + 
                " but got " + message.getClass().getName());
        }
        return (M) message;
    }
    
    /**
     * Expects a message matching the given predicate within the timeout.
     * 
     * @param predicate the condition the message must satisfy
     * @param timeout the maximum time to wait
     * @return the received message
     * @throws AssertionError if no message is received or doesn't match the predicate
     */
    public T expectMessage(Predicate<T> predicate, Duration timeout) {
        T message = expectMessage(timeout);
        if (!predicate.test(message)) {
            throw new AssertionError("Received message does not match predicate: " + message);
        }
        return message;
    }
    
    /**
     * Expects no message to be received within the given duration.
     * 
     * @param duration the time to wait
     * @throws AssertionError if a message is received
     */
    public void expectNoMessage(Duration duration) {
        try {
            T message = receivedMessages.poll(duration.toMillis(), TimeUnit.MILLISECONDS);
            if (message != null) {
                throw new AssertionError("Expected no message but received: " + message);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while checking for no message", e);
        }
    }
    
    /**
     * Expects a specific number of messages within the timeout.
     * 
     * @param count the number of messages to expect
     * @param timeout the maximum time to wait for all messages
     * @return the list of received messages
     * @throws AssertionError if the expected number of messages is not received
     */
    public List<T> expectMessages(int count, Duration timeout) {
        List<T> messages = new ArrayList<>(count);
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        
        for (int i = 0; i < count; i++) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new AssertionError(
                    "Expected " + count + " messages but only received " + messages.size() + 
                    " within " + timeout);
            }
            
            try {
                T message = receivedMessages.poll(remainingNanos, TimeUnit.NANOSECONDS);
                if (message == null) {
                    throw new AssertionError(
                        "Expected " + count + " messages but only received " + messages.size() + 
                        " within " + timeout);
                }
                messages.add(message);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(
                    "Interrupted while waiting for messages (received " + messages.size() + 
                    " of " + count + ")", e);
            }
        }
        
        return messages;
    }
    
    /**
     * Receives a message without throwing an exception if none is available.
     * 
     * @param timeout the maximum time to wait
     * @return an Optional containing the message, or empty if no message was received
     */
    public Optional<T> receiveMessage(Duration timeout) {
        try {
            T message = receivedMessages.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return Optional.ofNullable(message);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
    
    /**
     * Gets all messages that have been received so far.
     * This does not wait for new messages.
     * 
     * @return a list of all received messages
     */
    public List<T> receivedMessages() {
        List<T> messages = new ArrayList<>();
        receivedMessages.drainTo(messages);
        return messages;
    }
}
