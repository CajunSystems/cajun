package systems.cajun;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import systems.cajun.persistence.MessageJournal;
import systems.cajun.persistence.SnapshotStore;
import systems.cajun.persistence.FileMessageJournal;
import systems.cajun.persistence.FileSnapshotStore;

import java.io.IOException;
import java.nio.file.Files;
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
    public void setUp() throws IOException {
        actorSystem = new ActorSystem();
        // Create separate directories for the general message journal and snapshot store
        Path journalPath = tempDir.resolve("journal");
        Path snapshotPath = tempDir.resolve("snapshots");
        Files.createDirectories(journalPath);
        Files.createDirectories(snapshotPath);
        
        messageJournal = new FileMessageJournal<>(journalPath);
        snapshotStore = new FileSnapshotStore<>(snapshotPath);
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
        // Use a unique actor ID for each test run with additional timestamp to ensure uniqueness
        String uniqueActorId = "counter-" + System.currentTimeMillis() + "-" + System.nanoTime();
        System.out.println("Using unique actor ID: " + uniqueActorId);
        
        // Create subdirectories within the temp directory for this specific test
        Path journalPath = tempDir.resolve(uniqueActorId + "-journal");
        Path snapshotPath = tempDir.resolve(uniqueActorId + "-snapshots");
        Files.createDirectories(journalPath);
        Files.createDirectories(snapshotPath);
        System.out.println("Created journal directory: " + journalPath.toAbsolutePath());
        System.out.println("Created snapshot directory: " + snapshotPath.toAbsolutePath());
        
        // Ensure directories are empty and exist
        if (Files.exists(journalPath)) {
            Files.list(journalPath).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    System.err.println("Failed to delete file: " + p);
                }
            });
        }
        if (Files.exists(snapshotPath)) {
            Files.list(snapshotPath).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    System.err.println("Failed to delete file: " + p);
                }
            });
        }
        
        // Create a new message journal and snapshot store using the temp directory
        MessageJournal<String> testMessageJournal = new FileMessageJournal<>(journalPath);
        SnapshotStore<CounterState> testSnapshotStore = new FileSnapshotStore<>(snapshotPath);
        
        try {
            // Step 1: Create and initialize actor with initial state 0
            System.out.println("Step 1: Creating actor with initial state 0");
            CounterActor actor = new CounterActor(actorSystem, uniqueActorId, 
                                                new CounterState(0),
                                                testMessageJournal, testSnapshotStore);
            actor.start();
            
            // Use explicit synchronization to ensure state is initialized
            CompletableFuture<Void> initFuture = actor.forceInitializeState();
            initFuture.join(); // Wait for initialization to complete
            assertTrue(actor.waitForInit(2000), "Timed out waiting for initial state initialization");
            
            // Verify initial state
            int initialValue = actor.getCurrentValue();
            System.out.println("Initial state value: " + initialValue);
            assertEquals(0, initialValue, "Initial state should be 0");
            
            // Step 2: Send a single increment message and verify state
            System.out.println("Step 2: Sending increment message");
            actor.resetIncrementLatch();
            actor.tell("increment");
            boolean incrementProcessed = actor.waitForIncrement(2000);
            assertTrue(incrementProcessed, "Timed out waiting for increment");
            
            int afterIncrementValue = actor.getCurrentValue();
            System.out.println("State after increment: " + afterIncrementValue);
            assertEquals(1, afterIncrementValue, "State should be 1 after increment");
            
            // Step 3: Force a snapshot to ensure state is persisted
            System.out.println("Step 3: Taking snapshot");
            CompletableFuture<Void> snapshotFuture = actor.forceSnapshot();
            snapshotFuture.join(); // Wait for snapshot to complete
            
            // Verify files were created
            boolean journalFilesExist = Files.list(journalPath).count() > 0;
            boolean snapshotFilesExist = Files.list(snapshotPath).count() > 0;
            System.out.println("Journal files exist: " + journalFilesExist);
            System.out.println("Snapshot files exist: " + snapshotFilesExist);
            assertTrue(snapshotFilesExist, "Snapshot files should exist");
            
            // Step 4: Shutdown the actor
            System.out.println("Step 4: Shutting down actor");
            actorSystem.shutdown(actor.getActorId());
            Thread.sleep(1000); // Wait for shutdown to complete
            
            // Step 5: Create a new actor with the same ID but null initial state to force recovery
            System.out.println("Step 5: Creating new actor for recovery");
            CounterActor recoveredActor = new CounterActor(actorSystem, uniqueActorId, 
                                                        null, // null initial state forces recovery
                                                        testMessageJournal, testSnapshotStore);
            recoveredActor.start();
            
            // Step 6: Force initialization and wait for it to complete
            System.out.println("Step 6: Initializing recovered actor");
            CompletableFuture<Void> recoveryFuture = recoveredActor.forceInitializeState();
            recoveryFuture.join(); // Wait for initialization to complete
            boolean initSuccess = recoveredActor.waitForInit(2000);
            assertTrue(initSuccess, "Timed out waiting for state initialization during recovery");
            
            // Step 7: Verify the state was recovered correctly
            System.out.println("Step 7: Verifying recovered state");
            int recoveredValue = recoveredActor.getCurrentValue();
            System.out.println("Recovered value: " + recoveredValue);
            assertEquals(1, recoveredValue, "State should be recovered as 1");
        } catch (Exception e) {
            System.err.println("Test failed with exception: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
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
                // Ensure state is initialized and wait for completion
                CompletableFuture<Void> future = initializeState();
                future.join();
                // Signal that initialization is complete via the latch
                initLatch.countDown();
                return future;
            } catch (Exception e) {
                System.err.println("Error in forceInitializeState: " + e.getMessage());
                e.printStackTrace();
                return CompletableFuture.failedFuture(e);
            }
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
