package systems.cajun;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import systems.cajun.persistence.MessageJournal;
import systems.cajun.persistence.SnapshotStore;
import systems.cajun.persistence.MockMessageJournal;
import systems.cajun.persistence.MockSnapshotStore;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Basic tests for StatefulActor functionality.
 * More comprehensive tests with mocks are in StatefulActorMockTest.
 * Integration tests with file-based persistence are in StatefulActorPersistenceTest.
 */
class StatefulActorTest {

    private ActorSystem actorSystem;
    private MockMessageJournal<TestCounterMessage> messageJournal;
    private MockSnapshotStore<Integer> snapshotStore;
    
    @BeforeEach
    void setUp() {
        actorSystem = new ActorSystem();
        // Create fresh mock implementations for each test to prevent state leakage
        messageJournal = new MockMessageJournal<>();
        snapshotStore = new MockSnapshotStore<>();
    }

    @AfterEach
    void tearDown() {
        if (actorSystem != null) {
            actorSystem.shutdown();
        }
    }

    /**
     * Test basic state recovery from a mock store.
     * This test verifies that an actor can recover its state from a snapshot and message journal.
     */
    @Test
    void testStateRecoveryWithMockStore() throws InterruptedException {
        // Create a unique actor ID for this test
        String actorId = "counter-recovery-" + System.currentTimeMillis();
        
        // Create dedicated mock journal and snapshot store
        MockMessageJournal<TestCounterMessage> testJournal = new MockMessageJournal<>();
        MockSnapshotStore<Integer> testSnapshotStore = new MockSnapshotStore<>();
        
        // Create a counter actor with initial state 0
        TestCounterActor actor = new TestCounterActor(actorSystem, actorId, 0,
                                                    testJournal, testSnapshotStore);
        actor.start();
        
        // Wait for state initialization to complete
        assertTrue(actor.waitForStateInitialization(2000), "Timed out waiting for state initialization");

        // Increment the counter and verify
        actor.tell(new TestCounterMessage.Increment(5));
        Thread.sleep(100);
        assertEquals(5, actor.getCountSync(), "State should be updated to 5");
        
        // Force a snapshot
        actor.forceSnapshot().join();
        
        // Increment again
        actor.tell(new TestCounterMessage.Increment(10));
        Thread.sleep(100);
        assertEquals(15, actor.getCountSync(), "State should be updated to 15");
        
        // Stop the actor
        actor.stop();
        Thread.sleep(100);

        // Create a new actor with the same ID and persistence components
        // This should recover the state from the snapshot and journal
        TestCounterActor newActor = new TestCounterActor(actorSystem, actorId, null, // null forces recovery
                                                       testJournal, testSnapshotStore);
        newActor.start();

        // Wait for state initialization to complete
        assertTrue(newActor.waitForStateInitialization(2000), "Timed out waiting for state initialization");

        // Verify the state was recovered correctly
        assertEquals(15, newActor.getCountSync(), "State should be recovered correctly");
        
        // Stop the new actor
        newActor.stop();
    }

    /**
     * Test error handling during message processing.
     * This test verifies that the actor can continue processing messages after an error.
     */
    @Test
    void testErrorHandlingDuringMessageProcessing() throws InterruptedException {
        // Create a counter actor with initial state 0
        TestCounterActor actor = new TestCounterActor(actorSystem, "counter-error", 0,
                                                    messageJournal, snapshotStore);
        actor.start();

        // Wait for state initialization
        assertTrue(actor.waitForStateInitialization(1000), "Timed out waiting for state initialization");
        
        // Verify initial state
        assertEquals(0, actor.getCountSync(), "Initial state should be 0");

        // Send a message that will cause an error
        actor.tell(new TestCounterMessage.CauseError());
        Thread.sleep(100);
        
        // The actor should still be running and able to process messages
        actor.tell(new TestCounterMessage.Increment(5));
        Thread.sleep(100);

        // Verify the actor recovered and processed the message after the error
        assertEquals(5, actor.getCountSync(), "State should be updated after error");
        
        // Clean up
        actor.stop();
    }

    /**
     * Messages for the test counter actor.
     */
    public sealed interface TestCounterMessage permits
            TestCounterMessage.Increment,
            TestCounterMessage.Reset,
            TestCounterMessage.Clear,
            TestCounterMessage.GetCount,
            TestCounterMessage.CauseError {

        /**
         * Message to increment the counter.
         */
        record Increment(int amount) implements TestCounterMessage {
        }

        /**
         * Message to reset the counter to 0.
         */
        record Reset() implements TestCounterMessage {
        }

        /**
         * Message to clear the state (set to null).
         */
        record Clear() implements TestCounterMessage {
        }

        /**
         * Message to get the current count.
         */
        record GetCount(java.util.function.Consumer<Integer> callback) implements TestCounterMessage {
        }

        /**
         * Message to cause an error during processing.
         */
        record CauseError() implements TestCounterMessage {
        }
    }

    /**
     * A stateful actor that maintains a counter for testing.
     */
    public static class TestCounterActor extends StatefulActor<Integer, TestCounterMessage> {

        public TestCounterActor(ActorSystem system, String actorId, Integer initialState) {
            super(system, actorId, initialState);
        }
        
        public TestCounterActor(ActorSystem system, String actorId, Integer initialState,
                                MessageJournal<TestCounterMessage> messageJournal) {
            super(system, actorId, initialState, messageJournal);
        }
        
        public TestCounterActor(ActorSystem system, String actorId, Integer initialState,
                                MessageJournal<TestCounterMessage> messageJournal,
                                SnapshotStore<Integer> snapshotStore) {
            super(system, actorId, initialState, messageJournal, snapshotStore);
        }
        
        /**
         * Force initialization of state and wait for it to complete.
         * This is useful for testing to ensure the state is loaded before checking values.
         * 
         * @return A CompletableFuture that completes when the state has been initialized
         */
        public CompletableFuture<Void> forceInitializeState() {
            System.out.println("Forcing state initialization for " + getActorId());
            return initializeState()
                .thenAccept(result -> {
                    System.out.println("State initialization completed for " + getActorId() + ", state: " + getState());
                })
                .exceptionally(e -> {
                    System.err.println("Error initializing state for " + getActorId() + ": " + e.getMessage());
                    e.printStackTrace();
                    return null;
                });
        }
        
        /**
         * Force a snapshot to be taken immediately and wait for it to complete.
         * This is useful for testing to ensure the state is persisted before checking recovery.
         * 
         * @return A CompletableFuture that completes when the snapshot has been taken
         */
        public CompletableFuture<Void> forceSnapshot() {
            System.out.println("Forcing snapshot for " + getActorId() + ", state: " + getState());
            return super.forceSnapshot();
        }
        
        /**
         * Persists the current state to the state store by updating the state with its current value.
         * This is useful for testing to ensure the state is persisted before checking recovery.
         * 
         * @return A CompletableFuture that completes when the state has been persisted
         */
        public CompletableFuture<Void> persistState() {
            System.out.println("Persisting state for " + getActorId() + ", state: " + getState());
            // We can't call the private persistState() method directly, so we'll use updateState instead
            return updateState(getState());
        }

        @Override
        protected Integer processMessage(Integer state, TestCounterMessage message) {
            if (message instanceof TestCounterMessage.Increment increment) {
                return state + increment.amount();
            } else if (message instanceof TestCounterMessage.Reset) {
                return 0;
            } else if (message instanceof TestCounterMessage.Clear) {
                // Clear the state and then immediately set it to 0 (default value)
                clearState().thenRun(() -> {
                    // Set the state to 0 after clearing using the protected updateState method
                    updateState(0);
                });
                return 0; // Return 0 as the new state
            } else if (message instanceof TestCounterMessage.GetCount getCount) {
                // If state is null (e.g., during clearing), use 0 as default
                getCount.callback().accept(state != null ? state : 0);
            } else if (message instanceof TestCounterMessage.CauseError) {
                throw new RuntimeException("Test error");
            }
            return state;
        }
        
        /**
         * Helper method to get the current count synchronously using mocks.
         * This is more testable than using latches.
         * 
         * @return The current count
         */
        @SuppressWarnings("unchecked")
        public int getCountSync() {
            // Always try to wait for state initialization first
            try {
                waitForStateInitialization(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for state initialization", e);
            }
            
            // Create a mock consumer that will be called with the count
            Consumer<Integer> mockCallback = Mockito.mock(Consumer.class);
            
            // Send the message with the mock callback
            tell(new TestCounterMessage.GetCount(mockCallback));
            
            // Wait a short time for the message to be processed
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            // Capture the value passed to the callback
            ArgumentCaptor<Integer> captor = ArgumentCaptor.forClass(Integer.class);
            Mockito.verify(mockCallback, Mockito.timeout(5000)).accept(captor.capture());
            
            return captor.getValue();
        }
        
        /**
         * Helper method to increment the counter synchronously using mocks.
         * This is more testable than polling with Thread.sleep().
         * 
         * @param amount The amount to increment by
         */
        public void incrementSync(int amount) {
            // Always try to wait for state initialization first
            try {
                waitForStateInitialization(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for state initialization", e);
            }
            
            // Get the current count before incrementing
            int currentCount = getCountSync();
            
            // Create a CompletableFuture that will be completed when the state changes
            CompletableFuture<Void> incrementFuture = new CompletableFuture<>();
            
            // Create a mock consumer that will check the new state
            @SuppressWarnings("unchecked")
            Consumer<Integer> mockCallback = Mockito.mock(Consumer.class);
            
            // Set up the mock to complete the future when called with the expected new value
            Mockito.doAnswer(invocation -> {
                Integer newCount = invocation.getArgument(0);
                if (newCount == currentCount + amount) {
                    incrementFuture.complete(null);
                }
                return null;
            }).when(mockCallback).accept(Mockito.anyInt());
            
            // Send the increment message
            tell(new TestCounterMessage.Increment(amount));
            
            // Wait a short time for the message to be processed
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            // Get the new count using the mock callback
            tell(new TestCounterMessage.GetCount(mockCallback));
            
            // Wait for the callback to be called with the expected value
            try {
                incrementFuture.get(3, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new RuntimeException("Failed to increment counter", e);
            }
            
            // Verify the callback was called with the expected value
            Mockito.verify(mockCallback, Mockito.timeout(1000)).accept(currentCount + amount);
        }
        
        /**
         * Helper method to reset the counter synchronously using mocks.
         * This is more testable than using Thread.sleep().
         */
        public void resetSync() {
            // Always try to wait for state initialization first
            try {
                waitForStateInitialization(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for state initialization", e);
            }
            
            // Create a mock consumer that will be called with the count
            @SuppressWarnings("unchecked")
            Consumer<Integer> mockCallback = Mockito.mock(Consumer.class);
            
            // Send the reset message
            tell(new TestCounterMessage.Reset());
            
            // Wait a short time for the message to be processed
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            // Get the new count using the mock callback
            tell(new TestCounterMessage.GetCount(mockCallback));
            
            // Verify the callback was called with 0
            Mockito.verify(mockCallback, Mockito.timeout(1000)).accept(0);
        }
        
        /**
         * Helper method to clear the state synchronously using mocks.
         * This is more testable than using Thread.sleep().
         */
        public void clearSync() {
            // Always try to wait for state initialization first
            try {
                waitForStateInitialization(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for state initialization", e);
            }
            
            // Create a mock consumer that will be called with the count
            @SuppressWarnings("unchecked")
            Consumer<Integer> mockCallback = Mockito.mock(Consumer.class);
            
            // Send the clear message
            tell(new TestCounterMessage.Clear());
            
            // Wait a short time for the message to be processed
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            // Get the new count using the mock callback
            tell(new TestCounterMessage.GetCount(mockCallback));
            
            // Verify the callback was called with 0 (default value after clearing)
            Mockito.verify(mockCallback, Mockito.timeout(1000)).accept(0);
        }

        @Override
        protected boolean onError(TestCounterMessage message, Throwable exception) {
            // Log the error but don't reprocess the message
            return false;
        }
    }
}
