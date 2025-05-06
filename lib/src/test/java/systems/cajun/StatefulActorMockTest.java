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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for StatefulActor using mocks for persistence components.
 * This test class focuses on the core functionality of StatefulActor:
 * 1. State initialization and recovery
 * 2. Message processing and state updates
 * 3. Snapshot creation and verification
 */
class StatefulActorMockTest {

    private ActorSystem actorSystem;
    private MessageJournal<String> mockMessageJournal;
    private SnapshotStore<Integer> mockSnapshotStore;

    @BeforeEach
    void setUp() {
        actorSystem = new ActorSystem();
        
        // Create mocks
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
     * Test basic message processing functionality.
     */
    @Test
    void testBasicMessageProcessing() throws Exception {
        // Setup required mocks
        when(mockSnapshotStore.getLatestSnapshot(anyString()))
            .thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        when(mockMessageJournal.getHighestSequenceNumber(anyString()))
            .thenReturn(CompletableFuture.completedFuture(-1L));
        when(mockMessageJournal.append(anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(1L));
        when(mockSnapshotStore.saveSnapshot(anyString(), any(), anyLong()))
            .thenReturn(CompletableFuture.completedFuture(null));
            
        // Create actor with initial state 10
        TestCounterActor actor = new TestCounterActor(
                actorSystem, "test-actor", 10,
                mockMessageJournal, mockSnapshotStore);
        actor.start();
        
        // Wait for initialization and force it to complete
        actor.forceInitializeState().join();
        actor.forceInitializeState().join();
        assertTrue(actor.waitForStateInitialization(1000), "Actor should initialize state");
        
        // Send increment message
        actor.tell("increment:5");
        
        // Wait a bit for processing
        Thread.sleep(200);
        
        // Verify state via a synchronous get
        assertEquals(15, actor.getCountSync());
        
        // Verify message was journaled
        verify(mockMessageJournal, atLeastOnce()).append(anyString(), anyString());
        // Verify snapshot was saved (since we're no longer using StateStore)
        verify(mockSnapshotStore, atLeastOnce()).saveSnapshot(anyString(), any(), anyLong());
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
        when(mockMessageJournal.append(anyString(), anyString()))
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
    
    /**
     * Test message replay during recovery.
     */
    @Test
    void testMessageReplayDuringRecovery() throws Exception {
        // Setup required mocks
        SnapshotEntry<Integer> snapshotEntry = new SnapshotEntry<>("test-actor", 15, 5);
        when(mockSnapshotStore.getLatestSnapshot(anyString()))
            .thenReturn(CompletableFuture.completedFuture(Optional.of(snapshotEntry)));
        
        // Setup mock for message replay
        List<JournalEntry<String>> journalEntries = new ArrayList<>();
        journalEntries.add(new JournalEntry<>(6L, "test-actor", "increment:5"));
        journalEntries.add(new JournalEntry<>(7L, "test-actor", "increment:10"));
        
        when(mockMessageJournal.readFrom(anyString(), anyLong()))
            .thenReturn(CompletableFuture.completedFuture(journalEntries));
        when(mockMessageJournal.append(anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(8L));
        when(mockMessageJournal.getHighestSequenceNumber(anyString()))
            .thenReturn(CompletableFuture.completedFuture(7L));
        
        // Create actor with initial state 0
        TestCounterActor actor = new TestCounterActor(
                actorSystem, "test-actor", 0,
                mockMessageJournal, mockSnapshotStore);
        actor.start();
        
        // Wait for initialization and force it to complete
        actor.forceInitializeState().join();
        assertTrue(actor.waitForStateInitialization(1000), "Actor should initialize state");
        
        // Verify state was loaded from snapshot and messages were replayed
        assertEquals(30, actor.getCountSync(), "State should be 30 after snapshot (15) and replayed messages (5+10)");
        
        // Verify interactions
        verify(mockSnapshotStore).getLatestSnapshot(anyString());
        verify(mockMessageJournal).readFrom(anyString(), anyLong());
    }
    
    /**
     * Test snapshot creation.
     */
    @Test
    void testSnapshotCreation() throws Exception {
        // Setup required mocks
        when(mockSnapshotStore.getLatestSnapshot(anyString()))
            .thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        when(mockMessageJournal.getHighestSequenceNumber(anyString()))
            .thenReturn(CompletableFuture.completedFuture(-1L));
        when(mockSnapshotStore.saveSnapshot(anyString(), any(), anyLong()))
            .thenReturn(CompletableFuture.completedFuture(null));
        when(mockMessageJournal.append(anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(1L));
            
        // Create a custom actor that allows us to directly access the state
        TestCounterActor actor = new TestCounterActor(
                actorSystem, "test-actor", 0,
                mockMessageJournal, mockSnapshotStore);
        actor.start();
        
        // Wait for initialization and force it to complete
        actor.forceInitializeState().join();
        actor.forceInitializeState().join();
        assertTrue(actor.waitForStateInitialization(1000), "Actor should initialize state");
        
        // Reset verification to ignore initialization-related calls
        Mockito.reset(mockSnapshotStore);
        when(mockSnapshotStore.saveSnapshot(anyString(), any(), anyLong()))
            .thenReturn(CompletableFuture.completedFuture(null));
        
        // Increment the state a few times
        actor.incrementSynchronously(); // 1
        actor.incrementSynchronously(); // 2
        actor.incrementSynchronously(); // 3
        
        // Verify the state was updated
        assertEquals(3, actor.getCountSync());
        
        // Verify a snapshot was created (at least one, since we're now using snapshots for state persistence)
        verify(mockSnapshotStore, atLeastOnce()).saveSnapshot(eq("test-actor"), any(), anyLong());
    }
    
    /**
     * Test error handling during message processing.
     */
    @Test
    void testErrorHandlingDuringMessageProcessing() throws Exception {
        // Setup basic mocks for initialization
        when(mockSnapshotStore.getLatestSnapshot(anyString()))
            .thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        when(mockMessageJournal.getHighestSequenceNumber(anyString()))
            .thenReturn(CompletableFuture.completedFuture(-1L));
        when(mockMessageJournal.append(anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(1L));
        when(mockSnapshotStore.saveSnapshot(anyString(), any(), anyLong()))
            .thenReturn(CompletableFuture.completedFuture(null));
        
        // Create a CountDownLatch to wait for error handling
        CountDownLatch errorLatch = new CountDownLatch(1);
        
        // Create actor that will throw an exception
        ExceptionThrowingActor actor = new ExceptionThrowingActor(
                actorSystem, "test-actor", 0,
                mockMessageJournal, mockSnapshotStore, errorLatch);
        actor.start();
        
        // Wait for initialization and force it to complete
        actor.forceInitializeState().join();
        assertTrue(actor.waitForStateInitialization(1000), "Actor should initialize state");
        
        // Send a message that will trigger an exception
        actor.tell("throw-exception");
        
        // Wait for error to be processed (with timeout)
        assertTrue(errorLatch.await(2, TimeUnit.SECONDS), "Error should be handled within timeout");
        
        // Verify error was handled
        assertNotNull(actor.getLastError(), "Exception should be caught");
        assertEquals("Test exception during message processing", actor.getLastError().getMessage());
    }
    
    /**
     * Test actor implementation for testing StatefulActor behavior.
     */
    static class TestCounterActor extends StatefulActor<Integer, String> {
        
        public TestCounterActor(ActorSystem system, String actorId, Integer initialState,
                               MessageJournal<String> messageJournal,
                               SnapshotStore<Integer> snapshotStore) {
            super(system, actorId, initialState, messageJournal, snapshotStore);
        }

        @Override
        protected Integer processMessage(Integer state, String message) {
            if (message.startsWith("increment:")) {
                int amount = Integer.parseInt(message.substring(10));
                return state + amount;
            } else if (message.equals("reset")) {
                return 0;
            } else if (message.startsWith("get:")) {
                String callbackId = message.substring(4);
                getCountCallbacks.get(callbackId).accept(state != null ? state : 0);
            }
            return state;
        }

        // Map to store callbacks for get operations
        private final java.util.Map<String, Consumer<Integer>> getCountCallbacks = new java.util.concurrent.ConcurrentHashMap<>();

        /**
         * Helper method to get the current count synchronously.
         */
        public int getCountSync() {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicInteger result = new AtomicInteger();
            String callbackId = java.util.UUID.randomUUID().toString();
            
            getCountCallbacks.put(callbackId, count -> {
                result.set(count);
                latch.countDown();
            });
            
            tell("get:" + callbackId);
            
            try {
                if (!latch.await(1, TimeUnit.SECONDS)) {
                    throw new RuntimeException("Timed out waiting for count");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for count", e);
            } finally {
                getCountCallbacks.remove(callbackId);
            }
            
            return result.get();
        }
        
        /**
         * Helper method to increment the counter synchronously.
         */
        public void incrementSynchronously() {
            CountDownLatch latch = new CountDownLatch(1);
            String callbackId = java.util.UUID.randomUUID().toString();
            
            // Add a callback to be notified when the increment is processed
            getCountCallbacks.put(callbackId, count -> latch.countDown());
            
            // Send the increment message
            tell("increment:1");
            
            // Send a get message to ensure the increment is processed
            tell("get:" + callbackId);
            
            try {
                if (!latch.await(1, TimeUnit.SECONDS)) {
                    throw new RuntimeException("Timed out waiting for increment");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for increment", e);
            } finally {
                getCountCallbacks.remove(callbackId);
            }
        }
    }
    
    /**
     * Actor implementation that captures errors for testing error handling.
     */
    static class ErrorCapturingActor extends StatefulActor<Integer, String> {
        private Throwable lastError;
        private final CountDownLatch errorLatch;
        
        public ErrorCapturingActor(ActorSystem system, String actorId, Integer initialState,
                                  MessageJournal<String> messageJournal,
                                  SnapshotStore<Integer> snapshotStore,
                                  CountDownLatch errorLatch) {
            super(system, actorId, initialState, messageJournal, snapshotStore);
            this.errorLatch = errorLatch;
        }

        @Override
        protected Integer processMessage(Integer state, String message) {
            return state;
        }
        
        @Override
        protected void handleException(String message, Throwable exception) {
            super.handleException(message, exception);
            lastError = exception;
            errorLatch.countDown(); // Signal that an error has been handled
        }
        
        public Throwable getLastError() {
            return lastError;
        }
    }
    
    /**
     * Actor implementation that throws exceptions during message processing for testing error handling.
     */
    static class ExceptionThrowingActor extends StatefulActor<Integer, String> {
        private Throwable lastError;
        private final CountDownLatch errorLatch;
        
        public ExceptionThrowingActor(ActorSystem system, String actorId, Integer initialState,
                                     MessageJournal<String> messageJournal,
                                     SnapshotStore<Integer> snapshotStore,
                                     CountDownLatch errorLatch) {
            super(system, actorId, initialState, messageJournal, snapshotStore);
            this.errorLatch = errorLatch;
        }

        @Override
        protected Integer processMessage(Integer state, String message) {
            if ("throw-exception".equals(message)) {
                throw new RuntimeException("Test exception during message processing");
            }
            return state;
        }
        
        @Override
        protected void handleException(String message, Throwable exception) {
            super.handleException(message, exception);
            lastError = exception;
            errorLatch.countDown(); // Signal that an error has been handled
        }
        
        public Throwable getLastError() {
            return lastError;
        }
    }
}
