package com.cajunsystems.test;

import com.cajunsystems.StatefulActor;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Inspector for examining the internal state of stateful actors during testing.
 * Provides access to the current state without requiring explicit query messages.
 * 
 * <p>Usage:
 * <pre>{@code
 * TestPid<Message> actor = testKit.spawnStateful(CounterHandler.class, 0);
 * StateInspector<Integer> inspector = StateInspector.create(actor, testKit.system());
 * 
 * // Perform operations
 * actor.tell(new Increment(5));
 * 
 * // Inspect state directly
 * assertEquals(5, inspector.current());
 * }</pre>
 * 
 * @param <State> the state type of the stateful actor
 */
public class StateInspector<State> {
    
    private final StatefulActor<State, ?> actor;
    
    private StateInspector(StatefulActor<State, ?> actor) {
        this.actor = actor;
    }
    
    /**
     * Creates a StateInspector for the given actor.
     * 
     * @param <S> the state type
     * @param testPid the test pid wrapping the actor
     * @param system the actor system (used to look up the actor)
     * @return a StateInspector instance
     * @throws IllegalArgumentException if the actor is not a StatefulActor
     */
    public static <S> StateInspector<S> create(TestPid<?> testPid, com.cajunsystems.ActorSystem system) {
        // Get the actual actor instance from the system
        var actor = getActorFromSystem(testPid.actorId(), system);
        
        if (!(actor instanceof StatefulActor)) {
            throw new IllegalArgumentException(
                "Actor " + testPid.actorId() + " is not a StatefulActor"
            );
        }
        
        @SuppressWarnings("unchecked")
        StatefulActor<S, ?> statefulActor = (StatefulActor<S, ?>) actor;
        
        return new StateInspector<>(statefulActor);
    }
    
    /**
     * Gets the current state of the actor.
     * 
     * @return the current state
     * @throws IllegalStateException if state cannot be accessed
     */
    public State current() {
        try {
            // Access the currentState field via reflection
            Field currentStateField = StatefulActor.class.getDeclaredField("currentState");
            currentStateField.setAccessible(true);
            
            @SuppressWarnings("unchecked")
            AtomicReference<State> stateRef = (AtomicReference<State>) currentStateField.get(actor);
            
            return stateRef.get();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to access actor state", e);
        }
    }
    
    /**
     * Gets the current state as an Optional.
     * Returns empty if state is null.
     * 
     * @return Optional containing the current state, or empty if null
     */
    public Optional<State> currentOptional() {
        return Optional.ofNullable(current());
    }
    
    /**
     * Checks if the state matches the expected value.
     * 
     * @param expected the expected state
     * @return true if state matches, false otherwise
     */
    public boolean stateEquals(State expected) {
        State current = current();
        return current != null && current.equals(expected);
    }
    
    /**
     * Gets the last processed sequence number.
     * Useful for verifying message processing progress.
     * 
     * @return the last processed sequence number
     */
    public long lastProcessedSequence() {
        try {
            Field field = StatefulActor.class.getDeclaredField("lastProcessedSequence");
            field.setAccessible(true);
            
            var atomicLong = (java.util.concurrent.atomic.AtomicLong) field.get(actor);
            return atomicLong.get();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to access sequence number", e);
        }
    }
    
    // Helper method to get actor from system
    private static Object getActorFromSystem(String actorId, com.cajunsystems.ActorSystem system) {
        try {
            // Access the actors map from ActorSystem
            Field actorsField = com.cajunsystems.ActorSystem.class.getDeclaredField("actors");
            actorsField.setAccessible(true);
            
            @SuppressWarnings("unchecked")
            var actors = (java.util.concurrent.ConcurrentHashMap<String, ?>) actorsField.get(system);
            
            Object actor = actors.get(actorId);
            if (actor == null) {
                throw new IllegalArgumentException("Actor not found: " + actorId);
            }
            
            return actor;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalStateException("Failed to access actor from system", e);
        }
    }
}
