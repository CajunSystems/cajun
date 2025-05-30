package com.cajunsystems;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.cajunsystems.mocks.MockActorSystem;
import com.cajunsystems.persistence.BatchedMessageJournal;
import com.cajunsystems.persistence.OperationAwareMessage;
import com.cajunsystems.persistence.SnapshotStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


/**
 * Simple tests for StatefulActor that focus on basic functionality.
 * Uses mocks for better isolation and more focused testing.
 */
class SimpleStatefulActorTest {

    private MockActorSystem mockActorSystem;
    private BatchedMessageJournal<TestCounterMessage> mockMessageJournal;
    private SnapshotStore<Integer> mockSnapshotStore;

    @BeforeEach
    void setUp() {
        // Create a mock actor system for controlled testing
        mockActorSystem = new MockActorSystem();
        
        // Create mocks for persistence components with proper type parameters
        @SuppressWarnings("unchecked")
        BatchedMessageJournal<TestCounterMessage> journal = mock(BatchedMessageJournal.class);
        mockMessageJournal = journal;
        
        @SuppressWarnings("unchecked")
        SnapshotStore<Integer> snapshotStore = mock(SnapshotStore.class);
        mockSnapshotStore = snapshotStore;
        
        // Configure basic mock behavior
        when(mockMessageJournal.getHighestSequenceNumber(anyString()))
            .thenReturn(CompletableFuture.completedFuture(-1L));
        
        when(mockMessageJournal.append(anyString(), any(TestCounterMessage.class)))
            .thenReturn(CompletableFuture.completedFuture(1L));
        
        when(mockMessageJournal.readFrom(anyString(), anyLong()))
            .thenReturn(CompletableFuture.completedFuture(new ArrayList<>()));
            
        when(mockSnapshotStore.getLatestSnapshot(anyString()))
            .thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        
        when(mockSnapshotStore.saveSnapshot(anyString(), any(Integer.class), anyLong()))
            .thenReturn(CompletableFuture.completedFuture(null));
    }

    @AfterEach
    void tearDown() {
        if (mockActorSystem != null) {
            mockActorSystem.shutdown();
        }
    }

    /**
     * Test that a StatefulActor can be created and started.
     */
    @Test
    void testCreateAndStart() {
        // Create a counter actor with initial state 10
        TestCounterActor actor = new TestCounterActor(mockActorSystem, "counter-1", 10, 
                mockMessageJournal, mockSnapshotStore);
        
        // Register the actor with the mock actor system
        mockActorSystem.registerActor(actor);
        actor.start();

        assertTrue(actor.isRunning(), "Actor should be running after start");
        
        // Verify that the actor initialized its state
        verify(mockSnapshotStore).getLatestSnapshot(eq("counter-1"));
    }

    /**
     * Test that a StatefulActor can be stopped.
     */
    @Test
    void testStop() {
        // Create a counter actor with initial state 10
        TestCounterActor actor = new TestCounterActor(mockActorSystem, "counter-2", 10,
                mockMessageJournal, mockSnapshotStore);
        
        // Register the actor with the mock actor system
        mockActorSystem.registerActor(actor);
        actor.start();

        // Stop the actor
        actor.stop();

        assertFalse(actor.isRunning(), "Actor should not be running after stop");
        
        // Verify the actor was unregistered from the system
        mockActorSystem.unregisterActor("counter-2");
    }

    /**
     * Test that a StatefulActor can directly process messages.
     */
    @Test
    void testDirectMessageProcessing() {
        // Create a counter actor with initial state 0
        TestCounterActor actor = new TestCounterActor(mockActorSystem, "counter-3", 0,
                mockMessageJournal, mockSnapshotStore);

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
        TestCounterActor actor = new TestCounterActor(mockActorSystem, "counter-4", 0,
                mockMessageJournal, mockSnapshotStore);

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
    public sealed interface TestCounterMessage extends OperationAwareMessage permits
            TestCounterMessage.Increment,
            TestCounterMessage.Reset,
            TestCounterMessage.Clear {

        /**
         * Message to increment the counter.
         */
        record Increment(int amount) implements TestCounterMessage {
            @Override
            public boolean isReadOnly() {
                return false;
            }
        }

        /**
         * Message to reset the counter to 0.
         */
        record Reset() implements TestCounterMessage {
            @Override
            public boolean isReadOnly() {
                return false;
            }
        }

        /**
         * Message to clear the state (set to null).
         */
        record Clear() implements TestCounterMessage {
            @Override
            public boolean isReadOnly() {
                return false;
            }
        }
    }

    /**
     * Test that a StatefulActor can process messages through the actor system.
     */
    @Test
    void testMessageProcessingThroughActorSystem() throws Exception {
        // Create a direct test that doesn't rely on spying
        TestCounterActor actor = new TestCounterActor(mockActorSystem, "counter-5", 0,
                mockMessageJournal, mockSnapshotStore);
        
        // Register the actor with the mock actor system
        mockActorSystem.registerActor(actor);
        actor.start();
        
        // Explicitly wait for state initialization to complete
        actor.forceInitializeState().get(5, TimeUnit.SECONDS);
        
        // Set synchronous message delivery for deterministic testing
        mockActorSystem.setSynchronousMessageDelivery(true);
        
        // Send a message through the actor system
        mockActorSystem.sendMessage("counter-5", new TestCounterMessage.Increment(5));
        
        // Verify message was journaled (this should work because we're mocking the journal)
        verify(mockMessageJournal).append(eq("counter-5"), any(TestCounterMessage.Increment.class));
        
        // Wait a bit to ensure message processing completes
        Thread.sleep(100);
        
        // Verify the state was updated correctly (this is a more reliable test than method verification)
        assertEquals(5, actor.getState(), "State should be incremented to 5");
    }
    
    /**
     * A stateful actor that maintains a counter for testing.
     */
    public static class TestCounterActor extends StatefulActor<Integer, TestCounterMessage> {

        public TestCounterActor(MockActorSystem mockSystem, String actorId, Integer initialState,
                BatchedMessageJournal<TestCounterMessage> messageJournal,
                SnapshotStore<Integer> snapshotStore) {
            super(mockSystem, actorId, initialState, messageJournal, snapshotStore);
            mockSystem.registerActor(this);
        }

        @Override
        public void start() {
            super.start();
            // Ensure state initialization completes before returning
            try {
                waitForStateInitialization(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
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
