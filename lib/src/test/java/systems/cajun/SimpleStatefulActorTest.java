package systems.cajun;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import systems.cajun.persistence.StateStore;
import systems.cajun.persistence.StateStoreFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple tests for StatefulActor that focus on basic functionality.
 */
class SimpleStatefulActorTest {

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
    
    /**
     * Test that a StatefulActor can be created and started.
     */
    @Test
    void testCreateAndStart() {
        // Create a counter actor with initial state 10
        TestCounterActor actor = new TestCounterActor(actorSystem, "counter-1", 10);
        actor.start();
        
        assertTrue(actor.isRunning(), "Actor should be running after start");
    }
    
    /**
     * Test that a StatefulActor can be stopped.
     */
    @Test
    void testStop() {
        // Create a counter actor with initial state 10
        TestCounterActor actor = new TestCounterActor(actorSystem, "counter-2", 10);
        actor.start();
        
        // Stop the actor
        actor.stop();
        
        assertFalse(actor.isRunning(), "Actor should not be running after stop");
    }
    
    /**
     * Test that a StatefulActor can directly process messages.
     */
    @Test
    void testDirectMessageProcessing() {
        // Create a counter actor with initial state 0
        TestCounterActor actor = new TestCounterActor(actorSystem, "counter-3", 0);
        
        // Process messages directly
        Integer state = actor.processMessageForTest(0, new TestCounterMessage.Increment(5));
        assertEquals(5, state, "State should be incremented by 5");
        
        state = actor.processMessageForTest(state, new TestCounterMessage.Increment(10));
        assertEquals(15, state, "State should be incremented by 10 more");
        
        state = actor.processMessageForTest(state, new TestCounterMessage.Reset());
        assertEquals(0, state, "State should be reset to 0");
    }
    
    /**
     * Test that a StatefulActor can handle null state.
     */
    @Test
    void testNullState() {
        // Create a counter actor with initial state 0
        TestCounterActor actor = new TestCounterActor(actorSystem, "counter-4", 0);
        
        // Process a message that returns null state
        Integer state = actor.processMessageForTest(5, new TestCounterMessage.Clear());
        assertNull(state, "State should be null after clear");
        
        // Process a message with null state
        state = actor.processMessageForTest(null, new TestCounterMessage.Increment(5));
        assertEquals(5, state, "Null state should be treated as 0 when incrementing");
    }
    
    /**
     * Messages for the test counter actor.
     */
    public sealed interface TestCounterMessage permits 
            TestCounterMessage.Increment, 
            TestCounterMessage.Reset,
            TestCounterMessage.Clear {
        
        /**
         * Message to increment the counter.
         */
        record Increment(int amount) implements TestCounterMessage {}
        
        /**
         * Message to reset the counter to 0.
         */
        record Reset() implements TestCounterMessage {}
        
        /**
         * Message to clear the state (set to null).
         */
        record Clear() implements TestCounterMessage {}
    }
    
    /**
     * A stateful actor that maintains a counter for testing.
     */
    public static class TestCounterActor extends StatefulActor<Integer, TestCounterMessage> {
        
        public TestCounterActor(ActorSystem system, String actorId, Integer initialState) {
            super(system, actorId, initialState);
        }
        
        public TestCounterActor(ActorSystem system, String actorId, Integer initialState, 
                               StateStore<String, Integer> stateStore) {
            super(system, actorId, initialState, stateStore);
        }
        
        @Override
        protected Integer processMessage(Integer state, TestCounterMessage message) {
            return processMessageForTest(state, message);
        }
        
        /**
         * Public method to allow direct testing of message processing.
         */
        public Integer processMessageForTest(Integer state, TestCounterMessage message) {
            if (message instanceof TestCounterMessage.Increment increment) {
                return (state == null ? 0 : state) + increment.amount();
            } else if (message instanceof TestCounterMessage.Reset) {
                return 0;
            } else if (message instanceof TestCounterMessage.Clear) {
                return null;
            }
            return state;
        }
    }
}
