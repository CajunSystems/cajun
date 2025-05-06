package systems.cajun;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import systems.cajun.persistence.MessageJournal;
import systems.cajun.persistence.SnapshotStore;
import systems.cajun.persistence.SnapshotEntry;
import systems.cajun.persistence.JournalEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit test for StatefulActor persistence functionality using mocks.
 * This test focuses on testing the state persistence and recovery mechanisms
 * with mocked MessageJournal and SnapshotStore implementations.
 * 
 * For integration tests with actual file-based persistence, see StatefulActorIntegrationTest.
 */
public class StatefulActorPersistenceTest {
    
    private ActorSystem actorSystem;
    private MessageJournal<String> mockMessageJournal;
    private SnapshotStore<CounterState> mockSnapshotStore;
    
    @BeforeEach
    public void setUp() {
        actorSystem = new ActorSystem();
        
        // Create mocks for persistence components
        mockMessageJournal = Mockito.mock(MessageJournal.class);
        mockSnapshotStore = Mockito.mock(SnapshotStore.class);
    }
    
    @AfterEach
    public void tearDown() {
        actorSystem.shutdown();
    }
    
    /**
     * Test that verifies a simple state can be persisted and recovered using mocked persistence.
     */
    @Test
    public void testPersistenceAndRecovery() throws Exception {
        // Setup required mocks for initialization
        when(mockSnapshotStore.getLatestSnapshot(anyString()))
            .thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        when(mockMessageJournal.getHighestSequenceNumber(anyString()))
            .thenReturn(CompletableFuture.completedFuture(-1L));
        
        // Setup mock for message journaling
        when(mockMessageJournal.append(anyString(), eq("increment")))
            .thenReturn(CompletableFuture.completedFuture(1L));
        
        // Setup mock for snapshot creation
        when(mockSnapshotStore.saveSnapshot(anyString(), any(CounterState.class), anyLong()))
            .thenReturn(CompletableFuture.completedFuture(null));
        
        // Use a unique actor ID for this test
        String uniqueActorId = "counter-" + System.currentTimeMillis();
        
        // Step 1: Create and initialize actor with initial state 0
        CounterActor actor = new CounterActor(actorSystem, uniqueActorId, 
                                           new CounterState(0),
                                           mockMessageJournal, mockSnapshotStore);
        actor.start();
        
        // Use explicit synchronization to ensure state is initialized
        CompletableFuture<Void> initFuture = actor.forceInitializeState();
        initFuture.join(); // Wait for initialization to complete
        assertTrue(actor.waitForInit(1000), "Timed out waiting for initial state initialization");
        
        // Verify initial state
        int initialValue = actor.getCurrentValue();
        assertEquals(0, initialValue, "Initial state should be 0");
        
        // Step 2: Send a single increment message and verify state
        actor.resetIncrementLatch();
        actor.tell("increment");
        boolean incrementProcessed = actor.waitForIncrement(1000);
        assertTrue(incrementProcessed, "Timed out waiting for increment");
        
        int afterIncrementValue = actor.getCurrentValue();
        assertEquals(1, afterIncrementValue, "State should be 1 after increment");
        
        // Step 3: Force a snapshot to ensure state is persisted
        CompletableFuture<Void> snapshotFuture = actor.forceSnapshot();
        snapshotFuture.join(); // Wait for snapshot to complete
        
        // Verify snapshot was created
        verify(mockSnapshotStore, atLeastOnce()).saveSnapshot(eq(uniqueActorId), any(CounterState.class), anyLong());
        
        // Step 4: Shutdown the actor
        actorSystem.shutdown(actor.getActorId());
        Thread.sleep(100); // Brief wait for shutdown
        
        // Step 5: Setup mocks for recovery
        // Create a snapshot entry with state value 1 and sequence number 1
        SnapshotEntry<CounterState> snapshotEntry = new SnapshotEntry<>(uniqueActorId, new CounterState(1), 1);
        
        // Reset and reconfigure mocks for recovery
        reset(mockSnapshotStore, mockMessageJournal);
        
        // Mock snapshot retrieval to return our snapshot
        when(mockSnapshotStore.getLatestSnapshot(eq(uniqueActorId)))
            .thenReturn(CompletableFuture.completedFuture(Optional.of(snapshotEntry)));
        
        // Mock message journal to return empty list (no additional messages after snapshot)
        when(mockMessageJournal.readFrom(eq(uniqueActorId), anyLong()))
            .thenReturn(CompletableFuture.completedFuture(new ArrayList<>()));
        
        // Step 6: Create a new actor with the same ID but null initial state to force recovery
        CounterActor recoveredActor = new CounterActor(actorSystem, uniqueActorId, 
                                                    null, // null initial state forces recovery
                                                    mockMessageJournal, mockSnapshotStore);
        recoveredActor.start();
        
        // Force initialization and wait for it to complete
        CompletableFuture<Void> recoveryFuture = recoveredActor.forceInitializeState();
        recoveryFuture.join(); // Wait for initialization to complete
        boolean initSuccess = recoveredActor.waitForInit(1000);
        assertTrue(initSuccess, "Timed out waiting for state initialization during recovery");
        
        // Step 7: Verify the state was recovered correctly
        int recoveredValue = recoveredActor.getCurrentValue();
        assertEquals(1, recoveredValue, "State should be recovered as 1");
        
        // Verify interactions with mocks during recovery
        verify(mockSnapshotStore).getLatestSnapshot(eq(uniqueActorId));
        verify(mockMessageJournal).readFrom(eq(uniqueActorId), eq(2L)); // Should read from sequence after snapshot
    }
    
    /**
     * Test recovery with message replay after snapshot.
     */
    @Test
    public void testRecoveryWithMessageReplay() throws Exception {
        // Use a unique actor ID for this test
        String uniqueActorId = "counter-replay-" + System.currentTimeMillis();
        
        // Setup mocks for recovery scenario
        // Create a snapshot entry with state value 5 and sequence number 5
        SnapshotEntry<CounterState> snapshotEntry = new SnapshotEntry<>(uniqueActorId, new CounterState(5), 5);
        
        // Setup mock for snapshot retrieval
        when(mockSnapshotStore.getLatestSnapshot(eq(uniqueActorId)))
            .thenReturn(CompletableFuture.completedFuture(Optional.of(snapshotEntry)));
        
        // Setup mock for message journal with messages to replay
        List<JournalEntry<String>> journalEntries = new ArrayList<>();
        journalEntries.add(new JournalEntry<>(6L, uniqueActorId, "increment")); // +1 = 6
        journalEntries.add(new JournalEntry<>(7L, uniqueActorId, "increment")); // +1 = 7
        
        when(mockMessageJournal.readFrom(eq(uniqueActorId), eq(6L)))
            .thenReturn(CompletableFuture.completedFuture(journalEntries));
        
        // Create actor with null initial state to force recovery
        CounterActor actor = new CounterActor(actorSystem, uniqueActorId, 
                                           null, // null initial state forces recovery
                                           mockMessageJournal, mockSnapshotStore);
        actor.start();
        
        // Force initialization and wait for it to complete
        CompletableFuture<Void> initFuture = actor.forceInitializeState();
        initFuture.join();
        assertTrue(actor.waitForInit(1000), "Timed out waiting for state initialization");
        
        // Verify the state was recovered correctly with replayed messages
        int recoveredValue = actor.getCurrentValue();
        assertEquals(7, recoveredValue, "State should be 7 after snapshot (5) and replayed messages (2)");
        
        // Verify interactions with mocks
        verify(mockSnapshotStore).getLatestSnapshot(eq(uniqueActorId));
        verify(mockMessageJournal).readFrom(eq(uniqueActorId), eq(6L));
    }
    
    /**
     * Simple state class for testing.
     */
    static class CounterState implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        private final int value;
        
        public CounterState(int value) {
            this.value = value;
        }
        
        public int getValue() {
            return value;
        }
        
        public CounterState increment() {
            return new CounterState(value + 1);
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            CounterState that = (CounterState) o;
            return value == that.value;
        }
        
        @Override
        public int hashCode() {
            return value;
        }
        
        @Override
        public String toString() {
            return "CounterState{value=" + value + '}';
        }
    }
    
    /**
     * Actor implementation for testing.
     * This implementation uses CompletableFuture for better testability.
     */
    static class CounterActor extends StatefulActor<CounterState, String> {
        private CompletableFuture<Void> initializationFuture = new CompletableFuture<>();
        private CompletableFuture<Void> incrementFuture = new CompletableFuture<>();
        
        public CounterActor(ActorSystem system, String actorId, CounterState initialState,
                           MessageJournal<String> messageJournal,
                           SnapshotStore<CounterState> snapshotStore) {
            super(system, actorId, initialState, messageJournal, snapshotStore);
        }
        
        @Override
        protected void preStart() {
            super.preStart();
            
            // Make sure we start with the correct initial state
            if (getState() == null) {
                updateState(new CounterState(0));
            }
            
            // Initialize state and complete the future when done
            initializeState().thenRun(() -> {
                initializationFuture.complete(null);
            }).exceptionally(ex -> {
                initializationFuture.completeExceptionally(ex);
                return null;
            });
        }
        
        public CompletableFuture<Void> forceInitializeState() {
            try {
                // Ensure state is initialized and wait for completion
                CompletableFuture<Void> future = initializeState();
                future.join();
                // Signal that initialization is complete
                initializationFuture.complete(null);
                return future;
            } catch (Exception e) {
                System.err.println("Error in forceInitializeState: " + e.getMessage());
                e.printStackTrace();
                initializationFuture.completeExceptionally(e);
                return CompletableFuture.failedFuture(e);
            }
        }
        
        @Override
        protected CounterState processMessage(CounterState state, String message) {
            if ("increment".equals(message)) {
                CounterState newState = state.increment();
                incrementFuture.complete(null);
                // Create a new future for the next increment
                incrementFuture = new CompletableFuture<>();
                return newState;
            } else if ("reset".equals(message)) {
                return new CounterState(0);
            }
            return state;
        }
        
        public int getCurrentValue() {
            return getState().getValue();
        }
        
        public boolean waitForInit(long timeoutMs) throws InterruptedException {
            try {
                initializationFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
                return true;
            } catch (java.util.concurrent.TimeoutException e) {
                return false;
            } catch (java.util.concurrent.ExecutionException e) {
                throw new RuntimeException("Error during initialization", e);
            }
        }
        
        public boolean waitForIncrement(long timeoutMs) throws InterruptedException {
            try {
                incrementFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
                return true;
            } catch (java.util.concurrent.TimeoutException e) {
                return false;
            } catch (java.util.concurrent.ExecutionException e) {
                throw new RuntimeException("Error during increment", e);
            }
        }
        
        public void resetIncrementLatch() {
            incrementFuture = new CompletableFuture<>();
        }
        
        public CompletableFuture<Void> forceSnapshot() {
            // Force a snapshot by updating the state
            return updateState(getState());
        }
    }
    
    /**
     * NOTE: For integration testing with actual file-based stores, create a new test class:
     * StatefulActorIntegrationTest.java that uses real FileMessageJournal and FileSnapshotStore
     * implementations to verify the full persistence pipeline works correctly.
     */
}
