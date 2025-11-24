package com.cajunsystems.test;

import com.cajunsystems.ActorContext;
import com.cajunsystems.Pid;
import com.cajunsystems.handler.Handler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Captures all messages sent to an actor for inspection and assertion.
 * Useful for verifying message sequences, filtering specific messages, and debugging.
 * 
 * <p>Usage:
 * <pre>{@code
 * MessageCapture<String> capture = MessageCapture.create();
 * TestPid<String> actor = testKit.spawn(capture.handler());
 * 
 * actor.tell("msg1");
 * actor.tell("msg2");
 * 
 * assertEquals(2, capture.size());
 * assertEquals("msg1", capture.get(0));
 * }</pre>
 * 
 * @param <T> the message type to capture
 */
public class MessageCapture<T> {
    
    private final List<T> messages = new CopyOnWriteArrayList<>();
    private final Handler<T> capturingHandler;
    
    private MessageCapture(Handler<T> delegateHandler) {
        this.capturingHandler = (message, context) -> {
            messages.add(message);
            if (delegateHandler != null) {
                delegateHandler.receive(message, context);
            }
        };
    }
    
    /**
     * Creates a MessageCapture that only captures messages (no processing).
     * 
     * @param <T> the message type
     * @return a new MessageCapture instance
     */
    public static <T> MessageCapture<T> create() {
        return new MessageCapture<>(null);
    }
    
    /**
     * Creates a MessageCapture that captures and delegates to another handler.
     * 
     * @param <T> the message type
     * @param delegateHandler the handler to delegate to after capturing
     * @return a new MessageCapture instance
     */
    public static <T> MessageCapture<T> create(Handler<T> delegateHandler) {
        return new MessageCapture<>(delegateHandler);
    }
    
    /**
     * Gets the capturing handler to use when spawning the actor.
     * 
     * @return the handler that captures messages
     */
    public Handler<T> handler() {
        return capturingHandler;
    }
    
    /**
     * Gets the number of captured messages.
     * 
     * @return the message count
     */
    public int size() {
        return messages.size();
    }
    
    /**
     * Checks if any messages have been captured.
     * 
     * @return true if no messages captured
     */
    public boolean isEmpty() {
        return messages.isEmpty();
    }
    
    /**
     * Gets a specific captured message by index.
     * 
     * @param index the message index (0-based)
     * @return the message at that index
     * @throws IndexOutOfBoundsException if index is invalid
     */
    public T get(int index) {
        return messages.get(index);
    }
    
    /**
     * Gets all captured messages as a list.
     * 
     * @return a copy of all captured messages
     */
    public List<T> all() {
        return new ArrayList<>(messages);
    }
    
    /**
     * Gets messages matching the predicate.
     * 
     * @param predicate the filter predicate
     * @return list of matching messages
     */
    public List<T> filter(Predicate<T> predicate) {
        return messages.stream()
            .filter(predicate)
            .collect(Collectors.toList());
    }
    
    /**
     * Gets the first captured message.
     * 
     * @return the first message
     * @throws IllegalStateException if no messages captured
     */
    public T first() {
        if (messages.isEmpty()) {
            throw new IllegalStateException("No messages captured");
        }
        return messages.get(0);
    }
    
    /**
     * Gets the last captured message.
     * 
     * @return the last message
     * @throws IllegalStateException if no messages captured
     */
    public T last() {
        if (messages.isEmpty()) {
            throw new IllegalStateException("No messages captured");
        }
        return messages.get(messages.size() - 1);
    }
    
    /**
     * Clears all captured messages.
     */
    public void clear() {
        messages.clear();
    }
    
    /**
     * Waits until the specified number of messages are captured.
     * 
     * @param count the expected message count
     * @param timeout the maximum time to wait
     * @return true if count reached, false if timeout
     * @throws InterruptedException if interrupted while waiting
     */
    public boolean awaitCount(int count, Duration timeout) throws InterruptedException {
        long endTime = System.currentTimeMillis() + timeout.toMillis();
        
        while (System.currentTimeMillis() < endTime) {
            if (messages.size() >= count) {
                return true;
            }
            Thread.sleep(10);
        }
        
        return messages.size() >= count;
    }
    
    /**
     * Waits until a message matching the predicate is captured.
     * 
     * @param predicate the message predicate
     * @param timeout the maximum time to wait
     * @return the matching message, or null if timeout
     * @throws InterruptedException if interrupted while waiting
     */
    public T awaitMessage(Predicate<T> predicate, Duration timeout) throws InterruptedException {
        long endTime = System.currentTimeMillis() + timeout.toMillis();
        
        while (System.currentTimeMillis() < endTime) {
            for (T message : messages) {
                if (predicate.test(message)) {
                    return message;
                }
            }
            Thread.sleep(10);
        }
        
        return null;
    }
    
    /**
     * Checks if a message matching the predicate was captured.
     * 
     * @param predicate the message predicate
     * @return true if matching message found
     */
    public boolean contains(Predicate<T> predicate) {
        return messages.stream().anyMatch(predicate);
    }
    
    /**
     * Counts messages matching the predicate.
     * 
     * @param predicate the message predicate
     * @return the count of matching messages
     */
    public long count(Predicate<T> predicate) {
        return messages.stream().filter(predicate).count();
    }
    
    /**
     * Gets a snapshot of current capture state.
     * 
     * @return a CaptureSnapshot
     */
    public CaptureSnapshot<T> snapshot() {
        return new CaptureSnapshot<>(new ArrayList<>(messages));
    }
    
    /**
     * Immutable snapshot of captured messages at a point in time.
     *
     * @param <T> the type of messages in the snapshot
     * @param messages the list of captured messages at snapshot time
     */
    public record CaptureSnapshot<T>(List<T> messages) {
        /**
         * Gets the number of messages in this snapshot.
         *
         * @return the message count
         */
        public int size() {
            return messages.size();
        }
        
        /**
         * Checks if this snapshot contains no messages.
         *
         * @return true if snapshot is empty
         */
        public boolean isEmpty() {
            return messages.isEmpty();
        }
        
        /**
         * Gets a message at the specified index.
         *
         * @param index the message index
         * @return the message at that index
         */
        public T get(int index) {
            return messages.get(index);
        }
        
        @Override
        public String toString() {
            return "CaptureSnapshot{messages=" + messages.size() + "}";
        }
    }
}
