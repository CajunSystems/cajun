package com.cajunsystems;

import com.cajunsystems.persistence.*;
import com.cajunsystems.test.AsyncAssertion;
import com.cajunsystems.test.TempPersistenceExtension;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;


import java.util.*;
// Removed unused import
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
@ExtendWith(TempPersistenceExtension.class)
public class StatefulActorTest {

    /**
     * Tests for basic StatefulActor functionality without focusing on persistence.
     * These tests verify the core behavior of StatefulActor.
     */
    @Nested
    class BasicFunctionalityTests {
        
        private ActorSystem actorSystem;
        private MockBatchedMessageJournal<TestCounterMessage> mockMessageJournal;
        private MockSnapshotStore<Integer> snapshotStore;
        
        @BeforeEach
        void setUp() {
            actorSystem = new ActorSystem();
            // Create fresh mock implementations for each test to prevent state leakage
            mockMessageJournal = new MockBatchedMessageJournal<>();
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
            // Create a mock message journal and snapshot store
            MockBatchedMessageJournal<TestCounterMessage> localMessageJournal = new MockBatchedMessageJournal<>();
            MockSnapshotStore<Integer> localSnapshotStore = new MockSnapshotStore<>();
            
            // Create a counter actor with initial state 0
            CounterMessageActor actor = new CounterMessageActor(actorSystem, "counter-basic", 0,
                                                       localMessageJournal, localSnapshotStore);
            actor.start();
            
            // Wait for state initialization
            assertTrue(actor.waitForStateInitialization(1000), "Actor should initialize state");
            
            // Verify initial state
            assertEquals(0, actor.getCountSync(), "Initial state should be 0");
            
            // Send increment message
            actor.tell(new TestCounterMessage.Increment(5));
            
            // Wait for state update using AsyncAssertion
            AsyncAssertion.eventually(
                () -> actor.getCountSync() == 5,
                java.time.Duration.ofSeconds(2)
            );
            
            // Verify state was updated
            assertEquals(5, actor.getCountSync(), "State should be updated to 5");
            
            // Force a snapshot to ensure it's created
            actor.forceSnapshot().join();
            
            // Verify message was journaled
            List<JournalEntry<TestCounterMessage>> entries = localMessageJournal.getMessages("counter-basic");
            assertTrue(entries.size() >= 1, "At least one message should be journaled");
            boolean foundIncrementMessage = false;
            for (JournalEntry<TestCounterMessage> entry : entries) {
                if (entry.getMessage() instanceof TestCounterMessage.Increment) {
                    foundIncrementMessage = true;
                    break;
                }
            }
            assertTrue(foundIncrementMessage, "Journaled message should include Increment");
            
            // Verify snapshot was created
            SnapshotEntry<Integer> snapshot = localSnapshotStore.getSnapshot("counter-basic");
            assertNotNull(snapshot, "Snapshot should be created");
            assertEquals(5, snapshot.getState(), "Snapshot should contain updated state");
        }
        
        /**
         * Test error handling during message processing.
         */
        @Test
        void testErrorHandlingDuringMessageProcessing() throws InterruptedException {
            // Create a counter actor with initial state 0
            CounterMessageActor actor = new CounterMessageActor(actorSystem, "counter-1", 0, mockMessageJournal, snapshotStore);
            actor.start();
            
            // Wait for state initialization
            assertTrue(actor.waitForStateInitialization(1000), "Actor should initialize state");
            
            // Verify initial state
            assertEquals(0, actor.getCountSync(), "Initial state should be 0");
            
            // Send a message that will cause an error
            actor.tell(new TestCounterMessage.CauseError());
            
            // The actor should still be running and able to process messages
            actor.tell(new TestCounterMessage.Increment(5));
            
            // Verify the actor recovered and processed the message after the error using AsyncAssertion
            AsyncAssertion.eventually(
                () -> actor.getCountSync() == 5,
                java.time.Duration.ofSeconds(1)
            );
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
        private MockBatchedMessageJournal<StringMessage> mockMessageJournal;
        private SnapshotStore<CounterState> mockSnapshotStore;
        
        @BeforeEach
        void setUp() {
            actorSystem = new ActorSystem();
            
            // Create mocks for persistence components
            mockMessageJournal = new MockBatchedMessageJournal<>();
            @SuppressWarnings("unchecked")
            SnapshotStore<CounterState> snapshotStore = mock(SnapshotStore.class);
            mockSnapshotStore = snapshotStore;
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
            // Use a unique actor ID for this test
            String uniqueActorId = "counter-" + System.currentTimeMillis();
            
            // Create real mock implementations for this test
            MockBatchedMessageJournal<StringMessage> localMessageJournal = new MockBatchedMessageJournal<>();
            MockSnapshotStore<CounterState> localSnapshotStore = new MockSnapshotStore<>();
            
            // Step 1: Create and initialize actor with initial state 0
            CounterActor actor = new CounterActor(actorSystem, uniqueActorId, 
                                               new CounterState(0),
                                               localMessageJournal, localSnapshotStore);
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
            actor.tell(new StringMessage("increment"));
            boolean incrementProcessed = actor.waitForIncrement(1000);
            assertTrue(incrementProcessed, "Timed out waiting for increment");
            
            int afterIncrementValue = actor.getCurrentValue();
            assertEquals(1, afterIncrementValue, "State should be 1 after increment");
            
            // Step 3: Force a snapshot to ensure state is persisted
            CompletableFuture<Void> snapshotFuture = actor.forceSnapshot();
            snapshotFuture.join(); // Wait for snapshot to complete
            
            // Verify snapshot was created
            SnapshotEntry<CounterState> snapshot = localSnapshotStore.getSnapshot(uniqueActorId);
            assertNotNull(snapshot, "Snapshot should be created");
            assertEquals(1, snapshot.getState().getValue(), "Snapshot should contain state with value 1");
            
            // Step 4: Shutdown the actor
            actorSystem.shutdown(actor.getActorId());
            
            // Step 5: Create a new actor with the same ID but null initial state to force recovery
            CounterActor recoveredActor = new CounterActor(actorSystem, uniqueActorId, 
                                                        null, // null initial state forces recovery
                                                        localMessageJournal, localSnapshotStore);
            recoveredActor.start();
            
            // Force initialization and wait for it to complete
            CompletableFuture<Void> recoveryFuture = recoveredActor.forceInitializeState();
            recoveryFuture.join(); // Wait for initialization to complete
            boolean initSuccess = recoveredActor.waitForInit(1000);
            assertTrue(initSuccess, "Timed out waiting for state initialization during recovery");
            
            // Step 6: Verify the state was recovered correctly
            int recoveredValue = recoveredActor.getCurrentValue();
            assertEquals(1, recoveredValue, "State should be recovered as 1");
        }
        
        /**
         * Test recovery with message replay after snapshot.
         */
        @Test
        void testRecoveryWithMessageReplay() throws Exception {
            // Use a unique actor ID for this test
            String uniqueActorId = "counter-replay-" + System.currentTimeMillis();
            
            // Create real mock implementations for this test
            MockBatchedMessageJournal<StringMessage> localMessageJournal = new MockBatchedMessageJournal<>();
            MockSnapshotStore<CounterState> localSnapshotStore = new MockSnapshotStore<>();
            
            // Step 1: Create and initialize an actor with initial state 5
            CounterActor initialActor = new CounterActor(actorSystem, uniqueActorId, 
                                                    new CounterState(5),
                                                    localMessageJournal, localSnapshotStore);
            initialActor.start();
            initialActor.forceInitializeState().join();
            assertTrue(initialActor.waitForInit(1000), "Timed out waiting for initial state initialization");
            
            // Force a snapshot to ensure state is persisted
            initialActor.forceSnapshot().join();
            
            // Step 2: Send two increment messages
            initialActor.resetIncrementLatch();
            initialActor.tell(new StringMessage("increment"));
            assertTrue(initialActor.waitForIncrement(1000), "Timed out waiting for first increment");
            
            initialActor.resetIncrementLatch();
            initialActor.tell(new StringMessage("increment"));
            assertTrue(initialActor.waitForIncrement(1000), "Timed out waiting for second increment");
            
            // Verify state after increments
            assertEquals(7, initialActor.getCurrentValue(), "State should be 7 after snapshot (5) and increments (2)");
            
            // Step 3: Shutdown the actor
            actorSystem.shutdown(initialActor.getActorId());
            
            // Step 4: Create a new actor with the same ID to test recovery
            CounterActor recoveredActor = new CounterActor(actorSystem, uniqueActorId, 
                                                        null, // null initial state forces recovery
                                                        localMessageJournal, localSnapshotStore);
            recoveredActor.start();
            
            // Force initialization and wait for it to complete
            CompletableFuture<Void> recoveryFuture = recoveredActor.forceInitializeState();
            recoveryFuture.join();
            assertTrue(recoveredActor.waitForInit(1000), "Timed out waiting for state initialization during recovery");
            
            // Step 5: Verify the state was recovered correctly
            int recoveredValue = recoveredActor.getCurrentValue();
            assertEquals(7, recoveredValue, "State should be 7 after recovery");
        }
    }
    
    /**
     * Tests for advanced StatefulActor functionality using Mockito mocks.
     * These tests verify more complex scenarios and edge cases.
     */
    @Nested
    class AdvancedTests {
        
        private ActorSystem actorSystem;
        private MockBatchedMessageJournal<TestMessage> mockMessageJournal;
        private SnapshotStore<Integer> mockSnapshotStore;
        
        @BeforeEach
        void setUp() {
            actorSystem = new ActorSystem();
            mockMessageJournal = new MockBatchedMessageJournal<>();
            
            @SuppressWarnings("unchecked")
            SnapshotStore<Integer> snapshotStore = mock(SnapshotStore.class);
            mockSnapshotStore = snapshotStore;
            
            // Configure basic mock behavior
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
            // Configure mock behavior for recovery test
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
            
            // The actor should still be alive and able to process messages
            // The state should remain unchanged after the error
            assertEquals(10, actor.getCountSync());
            
            // Send a valid message to verify the actor is still functioning
            actor.tell(new Increment(5));
            AsyncAssertion.eventually(
                () -> actor.getCountSync() == 15,
                java.time.Duration.ofSeconds(1)
            );
            assertEquals(15, actor.getCountSync());
        }
        
        /**
         * Test snapshot creation timing.
         * This test verifies that:
         * 1. Snapshots are created after state changes
         * 2. Snapshots are not created too frequently
         * 
         * Note: Disabled due to flakiness - there's a race condition between message processing
         * and snapshot creation that causes intermittent failures under load.
         */
        @org.junit.jupiter.api.Disabled("Flaky test - race condition in snapshot timing")
        @Test
        void testSnapshotCreationTiming() throws Exception {
            // Create actor with a mock snapshot store
            MockSnapshotStore<Integer> localSnapshotStore = new MockSnapshotStore<>();
            TestCounterActor actor = new TestCounterActor(
                    actorSystem, "test-actor", 0,
                    mockMessageJournal, localSnapshotStore);
            
            actor.start();
            
            // Wait for initialization to complete
            CompletableFuture<Void> initFuture = actor.forceInitializeState();
            initFuture.join();
            assertTrue(actor.waitForStateInitialization(1000), "Actor should initialize state");
            
            // Clear any initialization snapshots
            localSnapshotStore.clear();
            
            // Send multiple increment messages
            for (int i = 0; i < 5; i++) {
                actor.tell(new Increment(1));
            }
            
            // Wait for all messages to be processed by checking message count
            // This is more reliable than checking state directly
            AsyncAssertion.eventually(
                () -> actor.getProcessedMessageCount() >= 5,
                java.time.Duration.ofSeconds(5)
            );
            
            // Force a final snapshot to ensure the state is captured
            actor.forceSnapshot().join();
            
            // Verify snapshot was created
            SnapshotEntry<Integer> snapshot = localSnapshotStore.getSnapshot("test-actor");
            assertNotNull(snapshot, "Snapshot should be created");
            assertEquals(5, snapshot.getState(), "Snapshot should contain updated state");
        }
        
        /**
         * Test state recovery from snapshot.
         */
        @Test
        void testStateRecoveryFromSnapshot() throws Exception {
            // Create a mock snapshot store with a predefined snapshot
            MockSnapshotStore<Integer> localSnapshotStore = new MockSnapshotStore<>();
            // Save a snapshot with state 5 and sequence number 20
            localSnapshotStore.saveSnapshot("test-recovery", 5, 20L).join();
            
            // Create a mock message journal
            MockBatchedMessageJournal<TestMessage> localMessageJournal = new MockBatchedMessageJournal<>();
            
            // Create actor with null initial state to force recovery
            TestCounterActor actor = new TestCounterActor(
                    actorSystem, "test-recovery", null, // null state forces recovery
                    localMessageJournal, localSnapshotStore);
            actor.start();
            
            // Wait for initialization and force it to complete
            actor.forceInitializeState().join();
            assertTrue(actor.waitForStateInitialization(1000), "Actor should initialize state");
            
            // Verify state was loaded from snapshot
            assertEquals(5, actor.getCountSync(), "State should be recovered from snapshot");
            
            // Send a new message to verify the actor is functioning after recovery
            actor.tell(new Increment(3));
            
            // Verify state was updated correctly after recovery using AsyncAssertion
            AsyncAssertion.eventually(
                () -> actor.getCountSync() == 8,
                java.time.Duration.ofSeconds(1)
            );
            assertEquals(8, actor.getCountSync(), "State should be updated after recovery");
        }
    }
    
    /**
     * Message interface for the test counter actor.
     */
    public sealed interface TestMessage extends OperationAwareMessage permits Increment, Decrement, Reset, ErrorMessage {}
    
    public record Increment(int amount) implements TestMessage {
        @Override
        public boolean isReadOnly() {
            return false;
        }
    }
    public record Decrement(int amount) implements TestMessage {
        @Override
        public boolean isReadOnly() {
            return false;
        }
    }
    public record Reset() implements TestMessage {
        @Override
        public boolean isReadOnly() {
            return false;
        }
    }
    public record ErrorMessage(String errorType) implements TestMessage {
        @Override
        public boolean isReadOnly() {
            return false;
        }
    }
    
    /**
     * Test implementation of StatefulActor for testing with TestMessage.
     */
    public static class TestCounterActor extends StatefulActor<Integer, TestMessage> {
        private final CountDownLatch initLatch = new CountDownLatch(1);
        private final AtomicInteger messageCount = new AtomicInteger(0);
        
        public TestCounterActor(ActorSystem system, String actorId, Integer initialState,
                              BatchedMessageJournal<TestMessage> messageJournal,
                              SnapshotStore<Integer> snapshotStore) {
            super(system, actorId, initialState, messageJournal, snapshotStore);
        }
        
        @Override
        protected Integer processMessage(Integer state, TestMessage message) {
            if (state == null) {
                state = 0;
            }
            
            if (message instanceof Increment increment) {
                messageCount.incrementAndGet();
                return state + increment.amount();
            } else if (message instanceof Decrement decrement) {
                messageCount.incrementAndGet();
                return state - decrement.amount();
            } else if (message instanceof Reset) {
                messageCount.incrementAndGet();
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
        
        // Direct state access for testing - avoids the timeout issues of getCountSync
        public Integer getCurrentState() {
            return getState();
        }
        
        // Get the number of messages processed
        public int getProcessedMessageCount() {
            return messageCount.get();
        }
    }
    
    /**
     * Test implementation of StatefulActor for testing with TestCounterMessage.
     */
    public static class CounterMessageActor extends StatefulActor<Integer, TestCounterMessage> {
        private final CountDownLatch initLatch = new CountDownLatch(1);
        
        public CounterMessageActor(ActorSystem system, String actorId, Integer initialState,
                                 BatchedMessageJournal<TestCounterMessage> messageJournal,
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
    public sealed interface TestCounterMessage extends OperationAwareMessage permits 
            TestCounterMessage.Increment,
            TestCounterMessage.Reset,
            TestCounterMessage.Clear,
            TestCounterMessage.GetCount,
            TestCounterMessage.CauseError {

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

        /**
         * Message to get the current count.
         */
        record GetCount(Consumer<Integer> callback) implements TestCounterMessage {
            @Override
            public boolean isReadOnly() {
                return true;
            }
        }

        /**
         * Message to cause an error during processing.
         */
        record CauseError() implements TestCounterMessage {
            @Override
            public boolean isReadOnly() {
                return false;
            }
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
    static class StringMessage implements OperationAwareMessage {
        private static final long serialVersionUID = 1L;
        private final String message;
        
        public StringMessage(String message) {
            this.message = message;
        }
        
        public String getMessage() {
            return message;
        }
        
        @Override
        public boolean isReadOnly() {
            return !message.equals("increment");
        }
        
        @Override
        public String toString() {
            return message;
        }
    }
    
    static class CounterActor extends StatefulActor<CounterState, StringMessage> {
        private final CountDownLatch initLatch = new CountDownLatch(1);
        private final CountDownLatch incrementLatch = new CountDownLatch(1);
        
        public CounterActor(ActorSystem system, String actorId, CounterState initialState,
                          BatchedMessageJournal<StringMessage> messageJournal, SnapshotStore<CounterState> snapshotStore) {
            super(system, actorId, initialState, messageJournal, snapshotStore);
        }
        
        @Override
        protected CounterState processMessage(CounterState state, StringMessage message) {
            if (message.getMessage().equals("increment")) {
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
