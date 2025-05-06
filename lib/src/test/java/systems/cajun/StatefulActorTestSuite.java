package systems.cajun;

import org.junit.jupiter.api.*;
import org.mockito.Mockito;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

import systems.cajun.persistence.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Comprehensive test suite for StatefulActor functionality.
 * This class combines all tests for StatefulActor into a single, well-organized suite
 * that covers all aspects of StatefulActor behavior:
 * 
 * 1. Basic functionality (message processing, state updates)
 * 2. State persistence (snapshots, message journaling)
 * 3. State recovery (from snapshots, message replay)
 * 4. Error handling (during initialization, message processing)
 * 5. Advanced scenarios (concurrent operations, timing-dependent behavior)
 */
public class StatefulActorTestSuite {

    /**
     * Tests for basic StatefulActor functionality without focusing on persistence.
     * These tests verify the core behavior of StatefulActor.
     */
    @Nested
    class BasicFunctionalityTests {
        
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
         * Test basic message processing and state updates.
         */
        @Test
        void testBasicMessageProcessing() throws InterruptedException {
            // Create a counter actor with initial state 0
            CounterMessageActor actor = new CounterMessageActor(actorSystem, "counter-basic", 0,
                                                       messageJournal, snapshotStore);
            actor.start();
            
            // Wait for state initialization
            assertTrue(actor.waitForStateInitialization(1000), "Actor should initialize state");
            
            // Verify initial state
            assertEquals(0, actor.getCountSync(), "Initial state should be 0");
            
            // Send increment message
            actor.tell(new TestCounterMessage.Increment(5));
            Thread.sleep(100);
            
            // Verify state was updated
            assertEquals(5, actor.getCountSync(), "State should be updated to 5");
            
            // Verify message was journaled
            List<JournalEntry<TestCounterMessage>> entries = messageJournal.getEntriesForActor("counter-basic");
            assertEquals(1, entries.size(), "One message should be journaled");
            assertTrue(entries.get(0).getMessage() instanceof TestCounterMessage.Increment, 
                    "Journaled message should be Increment");
            
            // Verify snapshot was created
            SnapshotEntry<Integer> snapshot = snapshotStore.getSnapshot("counter-basic");
            assertNotNull(snapshot, "Snapshot should be created");
            assertEquals(5, snapshot.getState(), "Snapshot should contain updated state");
        }
        
        /**
         * Test error handling during message processing.
         */
        @Test
        void testErrorHandlingDuringMessageProcessing() throws InterruptedException {
            // Create a counter actor with initial state 0
            CounterMessageActor actor = new CounterMessageActor(actorSystem, "counter-error", 0,
                                                       messageJournal, snapshotStore);
            actor.start();
            
            // Wait for state initialization
            assertTrue(actor.waitForStateInitialization(1000), "Actor should initialize state");
            
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
        }
    }
    
    /**
     * Tests for StatefulActor persistence functionality using Mockito mocks.
     * These tests verify the persistence and recovery mechanisms.
     */
    @Nested
    class PersistenceTests {
        
        private ActorSystem actorSystem;
        private MessageJournal<String> mockMessageJournal;
        private SnapshotStore<CounterState> mockSnapshotStore;
        
        @BeforeEach
        void setUp() {
            actorSystem = new ActorSystem();
            
            // Create mocks for persistence components
            mockMessageJournal = Mockito.mock(MessageJournal.class);
            mockSnapshotStore = Mockito.mock(SnapshotStore.class);
        }
        
        @AfterEach
        void tearDown() {
            if (actorSystem != null) {
                actorSystem.shutdown();
            }
        }
        
        /**
         * Test that verifies a simple state can be persisted and recovered.
         */
        @Test
        void testPersistenceAndRecovery() throws Exception {
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
        void testRecoveryWithMessageReplay() throws Exception {
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
    }
    
    /**
     * Tests for advanced StatefulActor functionality using Mockito mocks.
     * These tests verify more complex scenarios and edge cases.
     */
    @Nested
    class AdvancedTests {
        
        private ActorSystem actorSystem;
        private MessageJournal<TestMessage> mockMessageJournal;
        private SnapshotStore<Integer> mockSnapshotStore;
        
        @BeforeEach
        void setUp() {
            actorSystem = new ActorSystem();
            
            // Create clean mocks for each test
            @SuppressWarnings("unchecked")
            MessageJournal<TestMessage> journal = Mockito.mock(MessageJournal.class);
            mockMessageJournal = journal;
            
            @SuppressWarnings("unchecked")
            SnapshotStore<Integer> snapshotStore = Mockito.mock(SnapshotStore.class);
            mockSnapshotStore = snapshotStore;
            
            // Configure basic mock behavior
            when(mockMessageJournal.getHighestSequenceNumber(anyString()))
                .thenReturn(CompletableFuture.completedFuture(-1L));
            
            when(mockMessageJournal.append(anyString(), any(TestMessage.class)))
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
            if (actorSystem != null) {
                actorSystem.shutdown();
            }
        }
        
        /**
         * Test error handling during message processing.
         * This test verifies that the actor can properly handle errors that occur
         * during message processing without crashing.
         */
        @Test
        void testErrorHandlingDuringMessageProcessing() throws Exception {
            // Configure basic behavior for mocks
            when(mockMessageJournal.getHighestSequenceNumber(anyString()))
                .thenReturn(CompletableFuture.completedFuture(-1L));
            when(mockMessageJournal.readFrom(anyString(), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(new ArrayList<>()));
            when(mockMessageJournal.append(anyString(), any(TestMessage.class)))
                .thenReturn(CompletableFuture.completedFuture(1L));
            when(mockSnapshotStore.getLatestSnapshot(anyString()))
                .thenReturn(CompletableFuture.completedFuture(Optional.empty()));
            
            // Create actor with initial state 10
            TestCounterActor actor = new TestCounterActor(
                    actorSystem, "test-actor", 10,
                    mockMessageJournal, mockSnapshotStore);
            
            actor.start();
            actor.forceInitializeState().join();
            
            // Verify initial state
            assertEquals(10, actor.getCountSync());
            
            // Send a message that will cause an error
            actor.tell(new ErrorMessage("test-error"));
            
            // Wait a bit for processing
            Thread.sleep(100);
            
            // The actor should still be alive and able to process messages
            // The state should remain unchanged after the error
            assertEquals(10, actor.getCountSync());
            
            // Send a valid message to verify the actor is still functioning
            actor.tell(new Increment(5));
            Thread.sleep(100);
            assertEquals(15, actor.getCountSync());
        }
        
        /**
         * Test snapshot creation timing.
         * This test verifies that:
         * 1. Snapshots are created after state changes
         * 2. Snapshots are not created too frequently
         */
        @Test
        void testSnapshotCreationTiming() throws Exception {
            // Create actor
            TestCounterActor actor = new TestCounterActor(
                    actorSystem, "test-actor", 0,
                    mockMessageJournal, mockSnapshotStore);
            
            actor.start();
            
            // Wait for initialization to complete
            CompletableFuture<Void> initFuture = actor.forceInitializeState();
            initFuture.join();
            
            // Reset mock to clear initialization calls
            reset(mockSnapshotStore);
            when(mockSnapshotStore.saveSnapshot(anyString(), any(Integer.class), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(null));
            
            // Reset message journal mock
            reset(mockMessageJournal);
            when(mockMessageJournal.append(anyString(), any(TestMessage.class)))
                .thenReturn(CompletableFuture.completedFuture(1L));
            
            // Force a snapshot update to ensure we have a clean state
            actor.updateState(0).join();
            
            // Send multiple increment messages
            for (int i = 0; i < 5; i++) {
                actor.tell(new Increment(1));
                Thread.sleep(50);
            }
            
            // Wait a bit for processing and snapshot creation
            Thread.sleep(200);
            
            // Force a final snapshot to ensure the state is captured
            actor.updateState(actor.getCountSync()).join();
            
            // Verify snapshot was created
            verify(mockSnapshotStore, atLeastOnce()).saveSnapshot(eq("test-actor"), any(Integer.class), anyLong());
        }
        
        /**
         * Test state recovery from snapshot.
         */
        @Test
        void testStateRecoveryFromSnapshot() throws Exception {
            // Setup required mocks
            SnapshotEntry<Integer> snapshotEntry = new SnapshotEntry<>("test-actor", 20, 5);
            when(mockSnapshotStore.getLatestSnapshot(anyString()))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(snapshotEntry)));
            when(mockMessageJournal.readFrom(anyString(), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(new ArrayList<>()));
            when(mockMessageJournal.append(anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(6L));
            when(mockMessageJournal.getHighestSequenceNumber(anyString()))
                .thenReturn(CompletableFuture.completedFuture(5L));
            
            // Create actor with initial state 0
            TestCounterActor actor = new TestCounterActor(
                    actorSystem, "test-actor", 0,
                    mockMessageJournal, mockSnapshotStore);
            actor.start();
            
            // Wait for initialization and force it to complete
            actor.forceInitializeState().join();
            assertTrue(actor.waitForStateInitialization(1000), "Actor should initialize state");
            
            // Verify state was loaded from snapshot
            assertEquals(20, actor.getCountSync());
            
            // Verify snapshot was requested
            verify(mockSnapshotStore).getLatestSnapshot(anyString());
            verify(mockMessageJournal).readFrom(anyString(), anyLong());
        }
    }
    
    /**
     * Message interface for the test counter actor.
     */
    public sealed interface TestMessage permits Increment, Decrement, Reset, ErrorMessage {}
    
    public record Increment(int amount) implements TestMessage {}
    public record Decrement(int amount) implements TestMessage {}
    public record Reset() implements TestMessage {}
    public record ErrorMessage(String errorType) implements TestMessage {}
    
    /**
     * Test implementation of StatefulActor for testing with TestMessage.
     */
    public static class TestCounterActor extends StatefulActor<Integer, TestMessage> {
        private final CountDownLatch initLatch = new CountDownLatch(1);
        
        public TestCounterActor(ActorSystem system, String actorId, Integer initialState,
                              MessageJournal<TestMessage> messageJournal,
                              SnapshotStore<Integer> snapshotStore) {
            super(system, actorId, initialState, messageJournal, snapshotStore);
        }
        
        @Override
        protected Integer processMessage(Integer state, TestMessage message) {
            if (state == null) {
                state = 0;
            }
            
            if (message instanceof Increment increment) {
                return state + increment.amount();
            } else if (message instanceof Decrement decrement) {
                return state - decrement.amount();
            } else if (message instanceof Reset) {
                return 0;
            } else if (message instanceof ErrorMessage) {
                throw new RuntimeException("Error processing message: " + message);
            }
            
            return state;
        }
        
        @Override
        protected CompletableFuture<Void> initializeState() {
            return super.initializeState().thenRun(() -> initLatch.countDown());
        }
        
        public boolean waitForStateInitialization(long timeoutMs) throws InterruptedException {
            return initLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        }
        
        public int getCountSync() {
            AtomicInteger result = new AtomicInteger();
            CountDownLatch latch = new CountDownLatch(1);
            
            // Use a special method to get the state synchronously
            CompletableFuture.runAsync(() -> {
                result.set(getState());
                latch.countDown();
            }).join();
            
            try {
                latch.await(1000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            return result.get();
        }
    }
    
    /**
     * Test implementation of StatefulActor for testing with TestCounterMessage.
     */
    public static class CounterMessageActor extends StatefulActor<Integer, TestCounterMessage> {
        private final CountDownLatch initLatch = new CountDownLatch(1);
        
        public CounterMessageActor(ActorSystem system, String actorId, Integer initialState,
                                 MessageJournal<TestCounterMessage> messageJournal,
                                 SnapshotStore<Integer> snapshotStore) {
            super(system, actorId, initialState, messageJournal, snapshotStore);
        }
        
        @Override
        protected Integer processMessage(Integer state, TestCounterMessage message) {
            if (state == null) {
                state = 0;
            }
            
            if (message instanceof TestCounterMessage.Increment increment) {
                return state + increment.amount();
            } else if (message instanceof TestCounterMessage.Reset) {
                return 0;
            } else if (message instanceof TestCounterMessage.Clear) {
                return null;
            } else if (message instanceof TestCounterMessage.GetCount getCount) {
                getCount.callback().accept(state);
                return state;
            } else if (message instanceof TestCounterMessage.CauseError) {
                throw new RuntimeException("Error processing message: " + message);
            }
            
            return state;
        }
        
        @Override
        protected CompletableFuture<Void> initializeState() {
            return super.initializeState().thenRun(() -> initLatch.countDown());
        }
        
        public boolean waitForStateInitialization(long timeoutMs) throws InterruptedException {
            return initLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        }
        
        public int getCountSync() {
            AtomicInteger result = new AtomicInteger();
            CountDownLatch latch = new CountDownLatch(1);
            
            // Use a special method to get the state synchronously
            CompletableFuture.runAsync(() -> {
                result.set(getState());
                latch.countDown();
            }).join();
            
            try {
                latch.await(1000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            return result.get();
        }
    }
    
    /**
     * Messages for the test counter actor with string-based protocol.
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
        record GetCount(Consumer<Integer> callback) implements TestCounterMessage {
        }

        /**
         * Message to cause an error during processing.
         */
        record CauseError() implements TestCounterMessage {
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
     * Actor implementation for testing with CounterState.
     */
    static class CounterActor extends StatefulActor<CounterState, String> {
        private final CountDownLatch initLatch = new CountDownLatch(1);
        private final CountDownLatch incrementLatch = new CountDownLatch(1);
        
        public CounterActor(ActorSystem system, String actorId, CounterState initialState,
                          MessageJournal<String> messageJournal, SnapshotStore<CounterState> snapshotStore) {
            super(system, actorId, initialState, messageJournal, snapshotStore);
        }
        
        @Override
        protected CounterState processMessage(CounterState state, String message) {
            if (message.equals("increment")) {
                CounterState newState = state.increment();
                incrementLatch.countDown();
                return newState;
            }
            return state;
        }
        
        @Override
        protected CompletableFuture<Void> initializeState() {
            return super.initializeState().thenRun(() -> initLatch.countDown());
        }
        
        public boolean waitForInit(long timeoutMs) throws InterruptedException {
            return initLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        }
        
        public void resetIncrementLatch() {
            // This is a bit of a hack, but it works for testing
            try {
                while (incrementLatch.getCount() == 0) {
                    // Create a new latch if the current one is already counted down
                    java.lang.reflect.Field field = CounterActor.class.getDeclaredField("incrementLatch");
                    field.setAccessible(true);
                    field.set(this, new CountDownLatch(1));
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to reset increment latch", e);
            }
        }
        
        public boolean waitForIncrement(long timeoutMs) throws InterruptedException {
            return incrementLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        }
        
        public int getCurrentValue() {
            CounterState state = getState();
            return state != null ? state.getValue() : 0;
        }
    }
}
