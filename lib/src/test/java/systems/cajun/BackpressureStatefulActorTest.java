package systems.cajun;

import org.junit.jupiter.api.*;
import systems.cajun.persistence.BatchedMessageJournal;
import systems.cajun.persistence.MockBatchedMessageJournal;
import systems.cajun.persistence.MockSnapshotStore;
import systems.cajun.persistence.RetryStrategy;
import systems.cajun.persistence.SnapshotStore;

import java.io.Serializable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for StatefulActor with backpressure capabilities.
 * These tests focus on verifying the backpressure mechanisms and retry logic.
 */
public class BackpressureStatefulActorTest {

    private ActorSystem actorSystem;
    private MockBatchedMessageJournal<TestMessage> mockMessageJournal;
    private MockSnapshotStore<TestState> mockSnapshotStore;
    private RetryStrategy mockRetryStrategy;

    @BeforeEach
    void setUp() {
        actorSystem = new ActorSystem();
        mockMessageJournal = new MockBatchedMessageJournal<>();
        mockSnapshotStore = new MockSnapshotStore<>();
        mockRetryStrategy = mock(RetryStrategy.class);
        
        // Configure mock retry strategy to execute operations immediately by default
        when(mockRetryStrategy.executeWithRetry(any(), any())).thenAnswer(invocation -> {
            Supplier<CompletableFuture<Object>> supplier = invocation.getArgument(0);
            try {
                return supplier.get();
            } catch (Exception e) {
                CompletableFuture<Object> future = new CompletableFuture<>();
                future.completeExceptionally(e);
                return future;
            }
        });
    }

    @AfterEach
    void tearDown() {
        if (actorSystem != null) {
            actorSystem.shutdown();
        }
    }

    /**
     * Test that backpressure is applied when the mailbox is full.
     */
    @Test
    void testBackpressureIsApplied() throws InterruptedException {
        // Create test actor with very small mailbox capacity to ensure backpressure
        TestBackpressureActor actor = new TestBackpressureActor(
                actorSystem, "test-actor", new TestState(0),
                mockMessageJournal, mockSnapshotStore, true, 1, 2);
        
        // Configure the actor to process messages very slowly to ensure backpressure
        actor.setProcessingDelay(500); // Increased from 200ms to 500ms processing delay
        
        actor.start();
        
        // Wait for actor to initialize
        assertTrue(actor.waitForStateInitialization(1000), "Actor should initialize state");
        
        // Fill the mailbox to capacity
        int acceptedCount = 0;
        int rejectedCount = 0;
        
        // Send messages rapidly to trigger backpressure
        for (int i = 0; i < 10; i++) { // Increased from 5 to 10 messages
            if (actor.tryTell(new TestMessage("test-" + i))) {
                acceptedCount++;
            } else {
                rejectedCount++;
            }
            // Add a small delay to ensure messages are queued properly
            Thread.sleep(5); // Reduced from 10ms to 5ms to send messages faster
        }
        
        // Give some time for the backpressure to take effect
        Thread.sleep(300);
        
        // Send more messages to ensure some are rejected
        for (int i = 10; i < 20; i++) { // Adjusted range from 5-15 to 10-20
            if (actor.tryTell(new TestMessage("test-" + i))) {
                acceptedCount++;
            } else {
                rejectedCount++;
                // Once we get a rejection, we've confirmed backpressure is working
                break;
            }
            // Reduce delay to increase chance of rejection
            Thread.sleep(5); // Reduced from 10ms to 5ms
        }
        
        // If we still don't have rejections, try one more time with a longer delay
        if (rejectedCount == 0) {
            Thread.sleep(200);
            for (int i = 20; i < 40; i++) { // Increased range from 15-25 to 20-40
                if (!actor.tryTell(new TestMessage("test-" + i))) {
                    rejectedCount++;
                    break;
                }
                Thread.sleep(5); // Reduced from 10ms to 5ms
            }
        }
        
        // If still no rejections, try with a more aggressive approach
        if (rejectedCount == 0) {
            // Send a burst of messages without delay
            for (int i = 40; i < 60; i++) {
                if (!actor.tryTell(new TestMessage("test-" + i))) {
                    rejectedCount++;
                    break;
                }
            }
        }
        
        // Verify that some messages were accepted
        assertTrue(acceptedCount > 0, "Some messages should be accepted");
        
        // Now we should definitely have some rejections
        assertTrue(rejectedCount > 0, "Some messages should be rejected due to backpressure");
    }
    
    /**
     * Test that the retry strategy is used when processing messages.
     */
    @Test
    void testRetryStrategyIsUsed() throws InterruptedException {
        // Create a test actor with a mock retry strategy
        TestBackpressureActor actor = new TestBackpressureActor(
                actorSystem, "test-actor", new TestState(0),
                mockMessageJournal, mockSnapshotStore, true, 100, 200);
        
        // Set the mock retry strategy
        actor.withRetryStrategy(mockRetryStrategy);
        
        actor.start();
        
        // Wait for actor to initialize
        assertTrue(actor.waitForStateInitialization(1000), "Actor should initialize state");
        
        // Send a test message
        actor.tell(new TestMessage("test"));
        
        // Wait for processing to complete
        Thread.sleep(500);
        
        // Verify retry strategy was used
        verify(mockRetryStrategy).executeWithRetry(any(), any());
    }
    
    /**
     * Test that the error hook is called when an exception occurs.
     */
    @Test
    void testErrorHookIsCalled() throws InterruptedException {
        // Create a test actor that throws exceptions
        TestErrorActor actor = new TestErrorActor(
                actorSystem, "test-error-actor", new TestState(0),
                mockMessageJournal, mockSnapshotStore);
        
        // Set up a latch to wait for the error hook to be called
        CountDownLatch errorLatch = new CountDownLatch(1);
        AtomicReference<Throwable> caughtException = new AtomicReference<>();
        
        // Set an error hook
        actor.withErrorHook(ex -> {
            // Store the root cause exception
            Throwable cause = ex;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            caughtException.set(cause);
            errorLatch.countDown();
        });
        
        // Configure a retry strategy with fewer retries for faster test execution
        actor.withRetryStrategy(new RetryStrategy(1, 10, 100, 1.0, ex -> true));
        
        actor.start();
        
        // Wait for actor to initialize
        assertTrue(actor.waitForStateInitialization(1000), "Actor should initialize state");
        
        // Send a message that will cause an error
        actor.tell(new TestMessage("error"));
        
        // Wait for the error hook to be called
        assertTrue(errorLatch.await(2, TimeUnit.SECONDS), "Error hook should be called");
        
        // Verify the exception was caught
        assertNotNull(caughtException.get(), "Exception should be caught");
        assertEquals("Test error", caughtException.get().getMessage(), "Exception message should match");
    }
    
    /**
     * Test state for the StatefulActor with backpressure.
     */
    public static class TestState implements Serializable {
        private static final long serialVersionUID = 1L;
        private final int value;
        
        public TestState(int value) {
            this.value = value;
        }
        
        public int getValue() {
            return value;
        }
        
        public TestState increment() {
            return new TestState(value + 1);
        }
    }
    
    /**
     * Test message for the StatefulActor with backpressure.
     */
    public static class TestMessage implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String content;
        
        public TestMessage(String content) {
            this.content = content;
        }
        
        public String getContent() {
            return content;
        }
    }
    
    /**
     * Test implementation of StatefulActor with backpressure.
     */
    static class TestBackpressureActor extends StatefulActor<TestState, TestMessage> {
        private final AtomicInteger processedCount = new AtomicInteger(0);
        private int processingDelay = 0;
        
        public TestBackpressureActor(
                ActorSystem system, 
                String actorId, 
                TestState initialState,
                BatchedMessageJournal<TestMessage> messageJournal,
                SnapshotStore<TestState> snapshotStore,
                boolean enableBackpressure,
                int initialCapacity,
                int maxCapacity) {
            super(system, actorId, initialState, messageJournal, snapshotStore, 2, 
                  enableBackpressure ? system.getBackpressureConfig() : null, 
                  new systems.cajun.config.ResizableMailboxConfig()
                      .setInitialCapacity(initialCapacity)
                      .setMaxCapacity(maxCapacity));
        }
        
        public void setProcessingDelay(int delayMs) {
            this.processingDelay = delayMs;
        }
        
        @Override
        protected TestState processMessage(TestState state, TestMessage message) {
            // Simulate processing time
            if (processingDelay > 0) {
                try {
                    Thread.sleep(processingDelay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            
            processedCount.incrementAndGet();
            return state.increment();
        }
        
        public int getProcessedCount() {
            return processedCount.get();
        }
        
        /**
         * Try to send a message to this actor with backpressure awareness.
         * Returns immediately with a boolean indicating success or failure.
         *
         * @param message The message to send
         * @return true if the message was accepted, false if rejected due to backpressure
         */
        public boolean tryTell(TestMessage message) {
            // Check if backpressure is active
            if (isBackpressureActive()) {
                // If backpressure is active, reject the message
                return false;
            }
            
            // If the mailbox is at capacity, reject the message
            if (getCurrentSize() >= getCapacity()) {
                return false;
            }
            
            // Otherwise, send the message
            tell(message);
            return true;
        }
    }
    
    /**
     * Test implementation of StatefulActor that throws exceptions.
     */
    static class TestErrorActor extends StatefulActor<TestState, TestMessage> {
        
        public TestErrorActor(
                ActorSystem system, 
                String actorId, 
                TestState initialState,
                BatchedMessageJournal<TestMessage> messageJournal,
                SnapshotStore<TestState> snapshotStore) {
            super(system, actorId, initialState, messageJournal, snapshotStore, 2, 
                  system.getBackpressureConfig(), 
                  new systems.cajun.config.ResizableMailboxConfig());
        }
        
        @Override
        protected TestState processMessage(TestState state, TestMessage message) {
            if (message.getContent().equals("error")) {
                throw new RuntimeException("Test error");
            }
            return state.increment();
        }
        
        // Force synchronous initialization for testing
        @Override
        public CompletableFuture<Void> forceInitializeState() {
            return super.forceInitializeState();
        }
    }
}
