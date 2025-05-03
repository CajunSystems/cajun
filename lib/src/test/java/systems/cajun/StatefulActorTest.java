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

class StatefulActorTest {

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
    
    @Test
    void testInitialState() throws InterruptedException {
        // Create a counter actor with initial state 10
        TestCounterActor actor = new TestCounterActor(actorSystem, "counter-1", 10);
        actor.start();
        
        // Get the current count
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger result = new AtomicInteger();
        actor.tell(new TestCounterMessage.GetCount(count -> {
            result.set(count);
            latch.countDown();
        }));
        
        // Wait for the result
        assertTrue(latch.await(1, TimeUnit.SECONDS), "Timed out waiting for response");
        assertEquals(10, result.get(), "Initial state should be 10");
    }
    
    @Test
    void testStateUpdate() throws InterruptedException {
        // Create a counter actor with initial state 0
        TestCounterActor actor = new TestCounterActor(actorSystem, "counter-2", 0);
        actor.start();
        
        // Send increment messages
        actor.tell(new TestCounterMessage.Increment(5));
        actor.tell(new TestCounterMessage.Increment(10));
        
        // Wait for the messages to be processed
        Thread.sleep(100);
        
        // Get the current count
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger result = new AtomicInteger();
        actor.tell(new TestCounterMessage.GetCount(count -> {
            result.set(count);
            latch.countDown();
        }));
        
        // Wait for the result
        assertTrue(latch.await(1, TimeUnit.SECONDS), "Timed out waiting for response");
        assertEquals(15, result.get(), "State should be updated to 15");
    }
    
    @Test
    void testResetState() throws InterruptedException {
        // Create a counter actor with initial state 0
        TestCounterActor actor = new TestCounterActor(actorSystem, "counter-3", 0);
        actor.start();
        
        // Send increment messages
        actor.tell(new TestCounterMessage.Increment(5));
        actor.tell(new TestCounterMessage.Increment(10));
        
        // Wait for the messages to be processed
        Thread.sleep(100);
        
        // Reset the state
        actor.tell(new TestCounterMessage.Reset());
        
        // Wait for the reset to be processed
        Thread.sleep(100);
        
        // Get the current count
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger result = new AtomicInteger();
        actor.tell(new TestCounterMessage.GetCount(count -> {
            result.set(count);
            latch.countDown();
        }));
        
        // Wait for the result
        assertTrue(latch.await(1, TimeUnit.SECONDS), "Timed out waiting for response");
        assertEquals(0, result.get(), "State should be reset to 0");
    }
    
    @Test
    void testClearState() throws InterruptedException {
        // Create a counter actor with initial state 5
        TestCounterActor actor = new TestCounterActor(actorSystem, "counter-4", 5);
        actor.start();
        
        // Clear the state
        actor.tell(new TestCounterMessage.Clear());
        
        // Wait for the clear to be processed
        Thread.sleep(100);
        
        // Get the current count
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Integer> result = new AtomicReference<>();
        actor.tell(new TestCounterMessage.GetCount(count -> {
            result.set(count);
            latch.countDown();
        }));
        
        // Wait for the result
        assertTrue(latch.await(1, TimeUnit.SECONDS), "Timed out waiting for response");
        assertNull(result.get(), "State should be null after clearing");
    }
    
    @Test
    void testStateRecoveryWithInMemoryStore() throws InterruptedException {
        String actorId = "counter-recovery-1";
        StateStore<String, Integer> stateStore = StateStoreFactory.createInMemoryStore();
        
        // Create a counter actor with initial state 0
        TestCounterActor actor = new TestCounterActor(actorSystem, actorId, 0, stateStore);
        actor.start();
        
        // Send increment messages
        actor.tell(new TestCounterMessage.Increment(5));
        actor.tell(new TestCounterMessage.Increment(10));
        
        // Wait for the messages to be processed using polling
        int expectedState = 15;
        int maxWaitTimeMs = 200; // Increased from 100ms but still faster than 1 second
        int pollIntervalMs = 10;
        int elapsedTime = 0;
        AtomicInteger currentState = new AtomicInteger(0);
        CountDownLatch pollLatch = new CountDownLatch(1);
        
        while (currentState.get() != expectedState && elapsedTime < maxWaitTimeMs) {
            actor.tell(new TestCounterMessage.GetCount(count -> {
                currentState.set(count);
                if (count == expectedState) {
                    pollLatch.countDown();
                }
            }));
            
            if (pollLatch.await(pollIntervalMs, TimeUnit.MILLISECONDS)) {
                break; // State reached expected value
            }
            
            elapsedTime += pollIntervalMs;
        }
        
        // Stop the actor
        actor.stop();
        
        // Create a new actor with the same ID and state store
        TestCounterActor newActor = new TestCounterActor(actorSystem, actorId, 0, stateStore);
        newActor.start();
        
        // Get the recovered count
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger result = new AtomicInteger();
        newActor.tell(new TestCounterMessage.GetCount(count -> {
            result.set(count);
            latch.countDown();
        }));
        
        // Wait for the result
        assertTrue(latch.await(1, TimeUnit.SECONDS), "Timed out waiting for response");
        assertEquals(15, result.get(), "State should be recovered from the store");
    }
    
    @Test
    void testStateRecoveryWithFileStore(@TempDir Path tempDir) throws InterruptedException, IOException {
        String actorId = "counter-recovery-2";
        StateStore<String, Integer> stateStore = StateStoreFactory.createFileStore(tempDir.toString());
        
        // Create a counter actor with initial state 0
        TestCounterActor actor = new TestCounterActor(actorSystem, actorId, 0, stateStore);
        actor.start();
        
        // Send increment messages
        actor.tell(new TestCounterMessage.Increment(5));
        actor.tell(new TestCounterMessage.Increment(10));
        
        // Wait for the messages to be processed using polling
        int expectedState = 15;
        int maxWaitTimeMs = 200; // Increased from 100ms but still faster than 1 second
        int pollIntervalMs = 10;
        int elapsedTime = 0;
        AtomicInteger currentState = new AtomicInteger(0);
        CountDownLatch pollLatch = new CountDownLatch(1);
        
        while (currentState.get() != expectedState && elapsedTime < maxWaitTimeMs) {
            actor.tell(new TestCounterMessage.GetCount(count -> {
                currentState.set(count);
                if (count == expectedState) {
                    pollLatch.countDown();
                }
            }));
            
            if (pollLatch.await(pollIntervalMs, TimeUnit.MILLISECONDS)) {
                break; // State reached expected value
            }
            
            elapsedTime += pollIntervalMs;
        }
        
        // Stop the actor
        actor.stop();
        
        // Create a new actor with the same ID and state store
        TestCounterActor newActor = new TestCounterActor(actorSystem, actorId, 0, stateStore);
        newActor.start();
        
        // Get the recovered count
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger result = new AtomicInteger();
        newActor.tell(new TestCounterMessage.GetCount(count -> {
            result.set(count);
            latch.countDown();
        }));
        
        // Wait for the result
        assertTrue(latch.await(1, TimeUnit.SECONDS), "Timed out waiting for response");
        assertEquals(15, result.get(), "State should be recovered from the file store");
    }
    
    @Test
    void testErrorHandlingDuringStateOperation() throws InterruptedException {
        // Create a counter actor with initial state 0
        TestCounterActor actor = new TestCounterActor(actorSystem, "counter-error", 0);
        actor.start();
        
        // Send a message that will cause an error
        actor.tell(new TestCounterMessage.CauseError());
        
        // Wait for the error to be processed
        Thread.sleep(100);
        
        // The actor should still be running and able to process messages
        actor.tell(new TestCounterMessage.Increment(5));
        
        // Wait for the increment to be processed
        Thread.sleep(100);
        
        // Get the current count
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger result = new AtomicInteger();
        actor.tell(new TestCounterMessage.GetCount(count -> {
            result.set(count);
            latch.countDown();
        }));
        
        // Wait for the result
        assertTrue(latch.await(1, TimeUnit.SECONDS), "Timed out waiting for response");
        assertEquals(5, result.get(), "State should be updated after error");
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
        record Increment(int amount) implements TestCounterMessage {}
        
        /**
         * Message to reset the counter to 0.
         */
        record Reset() implements TestCounterMessage {}
        
        /**
         * Message to clear the state (set to null).
         */
        record Clear() implements TestCounterMessage {}
        
        /**
         * Message to get the current count.
         */
        record GetCount(java.util.function.Consumer<Integer> callback) implements TestCounterMessage {}
        
        /**
         * Message to cause an error during processing.
         */
        record CauseError() implements TestCounterMessage {}
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
            if (message instanceof TestCounterMessage.Increment increment) {
                return state + increment.amount();
            } else if (message instanceof TestCounterMessage.Reset) {
                return 0;
            } else if (message instanceof TestCounterMessage.Clear) {
                return null; // Setting state to null will cause it to be deleted from the store
            } else if (message instanceof TestCounterMessage.GetCount getCount) {
                getCount.callback().accept(state);
            } else if (message instanceof TestCounterMessage.CauseError) {
                throw new RuntimeException("Test error");
            }
            return state;
        }
        
        @Override
        protected boolean onError(TestCounterMessage message, Throwable exception) {
            // Log the error but don't reprocess the message
            return false;
        }
    }
}
