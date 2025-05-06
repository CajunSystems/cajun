package systems.cajun;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import systems.cajun.persistence.MessageJournal;
import systems.cajun.persistence.SnapshotStore;
import systems.cajun.persistence.FileMessageJournal;
import systems.cajun.persistence.FileSnapshotStore;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for StatefulActor with real file-based persistence.
 * This test focuses specifically on testing the integration with FileMessageJournal and FileSnapshotStore.
 * Unit tests with mocks are in StatefulActorMockTest.
 */
public class StatefulActorPersistenceTest {

    @TempDir
    Path tempDir;
    
    private ActorSystem actorSystem;
    private MessageJournal<String> messageJournal;
    private SnapshotStore<CounterState> snapshotStore;
    
    @BeforeEach
    public void setUp() {
        actorSystem = new ActorSystem();
        messageJournal = new FileMessageJournal<>(tempDir.toString());
        snapshotStore = new FileSnapshotStore<>(tempDir.toString());
    }
    
    @AfterEach
    public void tearDown() {
        actorSystem.shutdown();
    }
    
    /**
     * Integration test for file-based persistence and recovery.
     * This test verifies that state can be persisted to disk and recovered after actor restart.
     */
    /**
     * Test that verifies a simple state can be persisted and recovered.
     * This test uses a clean approach to ensure proper state recovery.
     */
    @Test
    public void testFileBasedPersistenceAndRecovery() throws Exception {
        // Use a unique actor ID for each test run
        String uniqueActorId = "counter-" + System.currentTimeMillis();
        System.out.println("Using unique actor ID: " + uniqueActorId);
        
        // Create a new message journal and snapshot store for this test
        MessageJournal<String> testMessageJournal = new FileMessageJournal<>(tempDir.toString() + "/" + uniqueActorId + "-journal");
        SnapshotStore<CounterState> testSnapshotStore = new FileSnapshotStore<>(tempDir.toString() + "/" + uniqueActorId + "-snapshots");
        
        // Step 1: Create and initialize actor with initial state 0
        CounterActor actor = new CounterActor(actorSystem, uniqueActorId, 
                                              new CounterState(0),
                                              testMessageJournal, testSnapshotStore);
        actor.start();
        actor.forceInitializeState().join();
        
        // Verify initial state
        assertEquals(0, actor.getCurrentValue(), "Initial state should be 0");
        
        // Step 2: Send a single increment message and verify state
        actor.resetIncrementLatch();
        actor.tell("increment");
        assertTrue(actor.waitForIncrement(1000), "Timed out waiting for increment");
        assertEquals(1, actor.getCurrentValue(), "State should be 1 after increment");
        
        // Step 3: Force a snapshot to ensure state is persisted
        actor.forceSnapshot().join();
        System.out.println("Snapshot taken with state: " + actor.getCurrentValue());
        
        // Step 4: Shutdown the actor
        actorSystem.shutdown(actor.getActorId());
        Thread.sleep(500); // Wait for shutdown to complete
        
        // Step 5: Create a new actor with the same ID but null initial state to force recovery
        CounterActor recoveredActor = new CounterActor(actorSystem, uniqueActorId, 
                                                     null, // null initial state forces recovery
                                                     testMessageJournal, testSnapshotStore);
        recoveredActor.start();
        
        // Step 6: Force initialization and wait for it to complete
        recoveredActor.forceInitializeState().join();
        assertTrue(recoveredActor.waitForInit(1000), "Timed out waiting for state initialization");
        
        // Step 7: Verify the state was recovered correctly
        int recoveredValue = recoveredActor.getCurrentValue();
        System.out.println("Recovered value: " + recoveredValue);
        assertEquals(1, recoveredValue, "State should be recovered as 1");
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
     */
    static class CounterActor extends StatefulActor<CounterState, String> {
        private CountDownLatch initLatch = new CountDownLatch(1);
        private CountDownLatch incrementLatch = new CountDownLatch(1);
        
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
            
            // Initialize state and then count down the latch when done
            initializeState().thenRun(() -> {
                initLatch.countDown();
            });
        }
        
        public CompletableFuture<Void> forceInitializeState() {
            try {
                initializeState().join();
            } catch (Exception e) {
                e.printStackTrace();
            }
            return CompletableFuture.completedFuture(null);
        }
        
        @Override
        protected CounterState processMessage(CounterState state, String message) {
            if ("increment".equals(message)) {
                CounterState newState = state.increment();
                incrementLatch.countDown();
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
            return initLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        }
        
        public boolean waitForIncrement(long timeoutMs) throws InterruptedException {
            return incrementLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        }
        
        public void resetIncrementLatch() {
            incrementLatch = new CountDownLatch(1);
        }
        
        public CompletableFuture<Void> forceSnapshot() {
            // Force a snapshot by updating the state
            return updateState(getState());
        }
    }
}
