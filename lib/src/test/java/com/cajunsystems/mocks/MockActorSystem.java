package com.cajunsystems.mocks;

import com.cajunsystems.Actor;
import com.cajunsystems.ActorSystem;
import org.mockito.Mockito;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A mock implementation of ActorSystem for testing.
 * This implementation allows for controlled message delivery and synchronous testing.
 * 
 * Instead of extending ActorSystem (which has package-private methods we can't override),
 * this class provides a custom implementation that tracks actors and can deliver messages
 * synchronously for deterministic testing.
 */
public class MockActorSystem extends ActorSystem {
    
    private final Map<String, Actor<?>> actors = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
    private boolean synchronousMessageDelivery = true;
    
    /**
     * Creates a new MockActorSystem.
     */
    public MockActorSystem() {
    }
    
    /**
     * Set whether messages should be delivered synchronously.
     * When true, messages are processed immediately on the calling thread.
     * When false, messages are processed asynchronously on a separate thread.
     *
     * @param synchronous Whether to deliver messages synchronously
     */
    public void setSynchronousMessageDelivery(boolean synchronous) {
        this.synchronousMessageDelivery = synchronous;
    }
    
    /**
     * Registers an actor with this system.
     * 
     * @param actor The actor to register
     */
    public void registerActor(Actor<?> actor) {
        actors.put(actor.getActorId(), actor);
    }
    
    /**
     * Unregisters an actor from this system.
     * 
     * @param actorId The ID of the actor to unregister
     */
    public void unregisterActor(String actorId) {
        actors.remove(actorId);
    }
    
    /**
     * Sends a message to an actor.
     * If synchronousMessageDelivery is true, the message is delivered immediately.
     * Otherwise, it is queued for asynchronous delivery.
     * 
     * @param actorId The ID of the actor to send the message to
     * @param message The message to send
     * @param <Message> The type of the message
     */
    public <Message> void sendMessage(String actorId, Message message) {
        if (synchronousMessageDelivery) {
            // Deliver the message synchronously for deterministic testing
            Actor<?> actor = actors.get(actorId);
            if (actor != null) {
                deliverMessage(actor, message);
            } else {
                System.out.println("Actor not found: " + actorId);
            }
        } else {
            // Deliver asynchronously
            Actor<?> actor = actors.get(actorId);
            if (actor != null) {
                // Use reflection to bypass type safety for testing purposes
                deliverMessageViaActorTell(actor, message);
            } else {
                System.out.println("Actor not found: " + actorId);
            }
        }
    }
    
    /**
     * Sends a message to an actor with a delay.
     * If synchronousMessageDelivery is true and delay is 0, the message is delivered immediately.
     * Otherwise, it is queued for asynchronous delivery after the delay.
     * 
     * @param actorId The ID of the actor to send the message to
     * @param message The message to send
     * @param delay The delay before delivering the message
     * @param timeUnit The time unit for the delay
     * @param <Message> The type of the message
     */
    public <Message> void sendMessageWithDelay(String actorId, Message message, long delay, TimeUnit timeUnit) {
        if (synchronousMessageDelivery && delay <= 0) {
            // Deliver the message synchronously for deterministic testing
            Actor<?> actor = actors.get(actorId);
            if (actor != null) {
                deliverMessage(actor, message);
            } else {
                System.out.println("Actor not found: " + actorId);
            }
        } else {
            // Deliver with delay
            Actor<?> actor = actors.get(actorId);
            if (actor != null) {
                if (delay <= 0) {
                    deliverMessageViaActorTell(actor, message);
                } else {
                    executor.schedule(() -> deliverMessageViaActorTell(actor, message), delay, timeUnit);
                }
            } else {
                System.out.println("Actor not found: " + actorId);
            }
        }
    }
    
    /**
     * Helper method to deliver a message via Actor.tell() method.
     * Uses reflection to bypass type safety for testing purposes.
     * 
     * @param actor The actor to deliver the message to
     * @param message The message to deliver
     * @param <Message> The type of the message
     */
    private <Message> void deliverMessageViaActorTell(Actor<?> actor, Message message) {
        try {
            // Use reflection to call tell method with any message type
            java.lang.reflect.Method tellMethod = Actor.class.getDeclaredMethod("tell", Object.class);
            tellMethod.setAccessible(true);
            tellMethod.invoke(actor, message);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deliver message via tell", e);
        }
    }
    
    /**
     * Creates a Pid-like object for an actor in this system.
     * 
     * @param actorId The ID of the actor
     * @return A MockPid object that can be used to send messages to the actor
     */
    public MockPid createPid(String actorId) {
        return new MockPid(actorId, this);
    }
    
    private <T> void deliverMessage(Actor<?> actor, T message) {
        try {
            // Use reflection to access the protected receive method
            java.lang.reflect.Method receiveMethod = Actor.class.getDeclaredMethod("receive", Object.class);
            receiveMethod.setAccessible(true);
            receiveMethod.invoke(actor, message);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deliver message", e);
        }
    }
    
    /**
     * Shuts down this actor system.
     * This stops all actors and releases resources.
     */
    public void shutdown() {
        // Stop all actors
        for (Actor<?> actor : actors.values()) {
            if (actor.isRunning()) {
                actor.stop();
            }
        }
        
        // Clear the actors map
        actors.clear();
        
        // Shutdown the executor
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Shuts down a specific actor in this system.
     * 
     * @param actorId The ID of the actor to shut down
     */
    public void shutdown(String actorId) {
        Actor<?> actor = actors.get(actorId);
        if (actor != null) {
            if (actor.isRunning()) {
                actor.stop();
            }
            actors.remove(actorId);
        }
    }
    
    /**
     * Get all registered actors.
     *
     * @return A map of actor IDs to actors
     */
    public Map<String, Actor<?>> getRegisteredActors() {
        return new ConcurrentHashMap<>(actors);
    }
    
    /**
     * Create a spy on an actor to verify interactions.
     *
     * @param actor The actor to spy on
     * @param <T> The type of messages the actor processes
     * @return A spy on the actor
     */
    public <T> Actor<T> spyOnActor(Actor<T> actor) {
        Actor<T> spy = Mockito.spy(actor);
        actors.put(actor.getActorId(), spy);
        return spy;
    }
    
    /**
     * A mock implementation of a Pid-like object for use with MockActorSystem.
     * This class provides the same interface as Pid but is not a subclass.
     */
    public static class MockPid {
        private final String actorId;
        private final MockActorSystem mockSystem;
        
        public MockPid(String actorId, MockActorSystem system) {
            this.actorId = actorId;
            this.mockSystem = system;
        }
        
        public <Message> void tell(Message message) {
            mockSystem.sendMessage(actorId, message);
        }
        
        public <Message> void tell(Message message, long delay, TimeUnit timeUnit) {
            mockSystem.sendMessageWithDelay(actorId, message, delay, timeUnit);
        }
        
        public String getId() {
            return actorId;
        }
        
        @Override
        public String toString() {
            return actorId + "@mock";
        }
    }
}
