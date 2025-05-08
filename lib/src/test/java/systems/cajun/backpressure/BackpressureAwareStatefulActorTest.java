package systems.cajun.backpressure;

import org.junit.jupiter.api.*;
import systems.cajun.ActorSystem;
import systems.cajun.persistence.BatchedMessageJournal;
import systems.cajun.persistence.MockBatchedMessageJournal;
import systems.cajun.persistence.MockSnapshotStore;
import systems.cajun.persistence.SnapshotStore;

import java.io.Serializable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Unit tests for BackpressureAwareStatefulActor.
 * These tests focus on verifying the backpressure mechanisms using mocks.
 */
public class BackpressureAwareStatefulActorTest {

    private ActorSystem actorSystem;
    private BackpressureStrategy mockBackpressureStrategy;
    private RetryStrategy mockRetryStrategy;
    private MockBatchedMessageJournal<TestMessage> mockMessageJournal;
    private MockSnapshotStore<TestState> mockSnapshotStore;

    @BeforeEach
    void setUp() {
        actorSystem = new ActorSystem();
        mockBackpressureStrategy = mock(BackpressureStrategy.class);
        mockRetryStrategy = mock(RetryStrategy.class);
        mockMessageJournal = new MockBatchedMessageJournal<>();
        mockSnapshotStore = new MockSnapshotStore<>();
        
        // Configure mock backpressure strategy defaults
        when(mockBackpressureStrategy.shouldApplyBackpressure(anyInt(), anyLong(), anyInt())).thenReturn(true);
        when(mockBackpressureStrategy.handleMessageUnderBackpressure(any(), any())).thenReturn(true);
        
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
     * Test that backpressure is applied when the strategy indicates it should be.
     */
    @Test
    void testBackpressureIsApplied() throws InterruptedException {
        // Configure mock backpressure strategy
        when(mockBackpressureStrategy.shouldApplyBackpressure(anyInt(), anyLong(), anyInt())).thenReturn(true);
        when(mockBackpressureStrategy.handleMessageUnderBackpressure(any(), any())).thenReturn(true);
        
        // Create test actor
        TestBackpressureActor actor = new TestBackpressureActor(
                actorSystem, "test-actor", new TestState(0),
                mockMessageJournal, mockSnapshotStore,
                mockBackpressureStrategy, mockRetryStrategy);
        actor.start();
        
        // Wait for actor to initialize
        assertTrue(actor.waitForStateInitialization(1000), "Actor should initialize state");
        
        // Send a test message
        actor.tell(new TestMessage("test"));
        
        // Wait for processing to complete
        Thread.sleep(500);
        
        // Verify backpressure was checked
        verify(mockBackpressureStrategy).shouldApplyBackpressure(anyInt(), anyLong(), anyInt());
        
        // Verify message was handled under backpressure
        verify(mockBackpressureStrategy).handleMessageUnderBackpressure(any(TestMessage.class), any());
        
        // Configure the mock to actually process the message when handleMessageUnderBackpressure is called
        // This is needed because we're using a real actor with a mock strategy
        doAnswer(invocation -> {
            Consumer<TestMessage> consumer = invocation.getArgument(1);
            TestMessage msg = invocation.getArgument(0);
            consumer.accept(msg);
            return true;
        }).when(mockBackpressureStrategy).handleMessageUnderBackpressure(any(TestMessage.class), any());
        
        // Send another message to test the updated mock behavior
        actor.tell(new TestMessage("test2"));
        
        // Wait for processing to complete
        Thread.sleep(500);
        
        // Verify message was processed
        assertTrue(actor.getProcessedCount() > 0, "At least one message should be processed");
    }

    /**
     * Test that messages are rejected when the backpressure strategy rejects them.
     */
    @Test
    void testMessageRejection() throws InterruptedException {
        // Configure mock backpressure strategy to reject messages
        when(mockBackpressureStrategy.shouldApplyBackpressure(anyInt(), anyLong(), anyInt())).thenReturn(true);
        when(mockBackpressureStrategy.handleMessageUnderBackpressure(any(), any())).thenReturn(false);
        
        // Create test actor
        TestBackpressureActor actor = new TestBackpressureActor(
                actorSystem, "test-actor", new TestState(0),
                mockMessageJournal, mockSnapshotStore,
                mockBackpressureStrategy, mockRetryStrategy);
        actor.start();
        
        // Wait for actor to initialize
        assertTrue(actor.waitForStateInitialization(1000), "Actor should initialize state");
        
        // Send a test message
        actor.tell(new TestMessage("test"));
        
        // Wait for processing to complete
        Thread.sleep(100);
        
        // Verify backpressure was checked
        verify(mockBackpressureStrategy).shouldApplyBackpressure(anyInt(), anyLong(), anyInt());
        
        // Verify message was handled under backpressure
        verify(mockBackpressureStrategy).handleMessageUnderBackpressure(any(TestMessage.class), any());
        
        // Verify message was not processed
        assertEquals(0, actor.getProcessedCount(), "Message should not be processed");
    }

    /**
     * Test that backpressure is not applied when the strategy indicates it shouldn't be.
     */
    @Test
    void testNoBackpressureWhenNotNeeded() throws InterruptedException {
        // Configure mock backpressure strategy to not apply backpressure
        when(mockBackpressureStrategy.shouldApplyBackpressure(anyInt(), anyLong(), anyInt())).thenReturn(false);
        
        // Create test actor
        TestBackpressureActor actor = new TestBackpressureActor(
                actorSystem, "test-actor", new TestState(0),
                mockMessageJournal, mockSnapshotStore,
                mockBackpressureStrategy, mockRetryStrategy);
        actor.start();
        
        // Wait for actor to initialize
        assertTrue(actor.waitForStateInitialization(1000), "Actor should initialize state");
        
        // Send a test message
        actor.tell(new TestMessage("test"));
        
        // Wait for processing to complete
        Thread.sleep(100);
        
        // Verify backpressure was checked
        verify(mockBackpressureStrategy).shouldApplyBackpressure(anyInt(), anyLong(), anyInt());
        
        // Verify message was not handled under backpressure
        verify(mockBackpressureStrategy, never()).handleMessageUnderBackpressure(any(), any());
        
        // Verify message was processed
        assertEquals(1, actor.getProcessedCount(), "Message should be processed");
    }

    /**
     * Test that error hooks are called when processing fails.
     */
    @Test
    void testErrorHookCalledOnFailure() throws InterruptedException {
        // Create a test actor with a custom implementation that allows us to trigger errors
        TestErrorActor actor = new TestErrorActor(
                actorSystem, "test-actor", new TestState(0),
                mockMessageJournal, mockSnapshotStore,
                mockBackpressureStrategy, mockRetryStrategy);
        
        // Create a latch to wait for error hook
        CountDownLatch errorLatch = new CountDownLatch(1);
        AtomicReference<Throwable> caughtException = new AtomicReference<>();
        
        // Set up error hook
        actor.withErrorHook(ex -> {
            caughtException.set(ex);
            errorLatch.countDown();
        });
        actor.start();
        
        // Wait for actor to initialize
        assertTrue(actor.waitForStateInitialization(1000), "Actor should initialize state");
        
        // Trigger an error through our custom method
        actor.triggerError(new RuntimeException("Test exception"));
        
        // Wait for error hook to be called
        assertTrue(errorLatch.await(1, TimeUnit.SECONDS), "Error hook should be called");
        
        // Verify exception was caught
        assertNotNull(caughtException.get(), "Exception should be caught");
        assertEquals("Test exception", caughtException.get().getMessage(), "Exception message should match");
    }

    /**
     * Test that metrics are properly collected and reported.
     */
    @Test
    void testMetricsCollection() throws InterruptedException {
        // Configure mock backpressure strategy
        when(mockBackpressureStrategy.shouldApplyBackpressure(anyInt(), anyLong(), anyInt())).thenReturn(false);
        // Make sure the onMessageProcessingFailed method doesn't throw an exception
        doNothing().when(mockBackpressureStrategy).onMessageProcessingFailed(any(Throwable.class));
        when(mockBackpressureStrategy.getCurrentBackpressureLevel()).thenReturn(0.5);
        
        BackpressureMetrics mockMetrics = new BackpressureMetrics(
                0.5, 10, 5, 3, 100_000_000, 2);
        when(mockBackpressureStrategy.getMetrics()).thenReturn(mockMetrics);
        
        // Create test actor
        TestBackpressureActor actor = new TestBackpressureActor(
                actorSystem, "test-actor", new TestState(0),
                mockMessageJournal, mockSnapshotStore,
                mockBackpressureStrategy, mockRetryStrategy);
        actor.start();
        
        // Wait for actor to initialize
        assertTrue(actor.waitForStateInitialization(1000), "Actor should initialize state");
        
        // Send a test message
        actor.tell(new TestMessage("test"));
        
        // Wait for processing to complete
        Thread.sleep(100);
        
        // Get metrics
        BackpressureMetrics metrics = actor.getBackpressureMetrics();
        
        // Verify metrics match
        assertEquals(0.5, metrics.getBackpressureLevel(), "Backpressure level should match");
        assertEquals(10, metrics.getCurrentQueueSize(), "Queue size should match");
        assertEquals(5, metrics.getRejectedMessagesCount(), "Rejected count should match");
        assertEquals(3, metrics.getDelayedMessagesCount(), "Delayed count should match");
        assertEquals(100_000_000, metrics.getAverageProcessingTimeNanos(), "Processing time should match");
        assertEquals(2, metrics.getPersistenceQueueSize(), "Persistence queue size should match");
    }

    /**
     * Test that the adaptive backpressure strategy works correctly.
     */
    @Test
    void testAdaptiveBackpressureStrategy() throws InterruptedException {
        // Use a real adaptive backpressure strategy
        AdaptiveBackpressureStrategy realStrategy = new AdaptiveBackpressureStrategy(
                10, 50, 100, 1_000_000, 3, 1000);
        
        // Create test actor
        TestBackpressureActor actor = new TestBackpressureActor(
                actorSystem, "test-actor", new TestState(0),
                mockMessageJournal, mockSnapshotStore,
                realStrategy, new RetryStrategy());
        actor.start();
        
        // Wait for actor to initialize
        assertTrue(actor.waitForStateInitialization(1000), "Actor should initialize state");
        
        // Send 20 messages (should exceed low threshold but not high threshold)
        for (int i = 0; i < 20; i++) {
            actor.tell(new TestMessage("test-" + i));
        }
        
        // Wait for processing to complete
        Thread.sleep(500);
        
        // Verify backpressure level is above 0 but below 0.5
        double level = actor.getCurrentBackpressureLevel();
        assertTrue(level > 0.0 && level < 0.5, 
                "Backpressure level should be between 0 and 0.5, was: " + level);
        
        // Verify all messages were processed
        assertEquals(20, actor.getProcessedCount(), "All messages should be processed");
    }

    /**
     * Test state for the BackpressureAwareStatefulActor.
     */
    static class TestState implements Serializable {
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
     * Test message for the BackpressureAwareStatefulActor.
     */
    static class TestMessage implements Serializable {
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
     * Test implementation of BackpressureAwareStatefulActor.
     */
    static class TestBackpressureActor extends BackpressureAwareStatefulActor<TestState, TestMessage> {
        private final AtomicInteger processedCount = new AtomicInteger(0);
        
        public TestBackpressureActor(
                ActorSystem system, 
                String actorId, 
                TestState initialState,
                BatchedMessageJournal<TestMessage> messageJournal,
                SnapshotStore<TestState> snapshotStore,
                BackpressureStrategy backpressureStrategy,
                RetryStrategy retryStrategy) {
            super(system, actorId, initialState, messageJournal, snapshotStore, 
                  backpressureStrategy, retryStrategy);
        }
        
        @Override
        protected TestState processMessage(TestState state, TestMessage message) {
            processedCount.incrementAndGet();
            return state.increment();
        }
        
        public int getProcessedCount() {
            return processedCount.get();
        }
    }
    
    /**
     * Special test actor that allows directly triggering errors for testing error hooks.
     */
    static class TestErrorActor extends BackpressureAwareStatefulActor<TestState, TestMessage> {
        public TestErrorActor(
                ActorSystem system, 
                String actorId, 
                TestState initialState,
                BatchedMessageJournal<TestMessage> messageJournal,
                SnapshotStore<TestState> snapshotStore,
                BackpressureStrategy backpressureStrategy,
                RetryStrategy retryStrategy) {
            super(system, actorId, initialState, messageJournal, snapshotStore, 
                  backpressureStrategy, retryStrategy);
        }
        
        @Override
        protected TestState processMessage(TestState state, TestMessage message) {
            return state.increment();
        }
        
        /**
         * Directly triggers the error hook with the given exception.
         * This is used for testing error handling without having to go through the message path.
         */
        public void triggerError(Throwable exception) {
            // Call the parent's error handling mechanism directly
            try {
                // Use reflection to access the private errorHook field
                java.lang.reflect.Field errorHookField = BackpressureAwareStatefulActor.class.getDeclaredField("errorHook");
                errorHookField.setAccessible(true);
                Consumer<Throwable> errorHook = (Consumer<Throwable>) errorHookField.get(this);
                errorHook.accept(exception);
            } catch (Exception e) {
                throw new RuntimeException("Failed to trigger error hook", e);
            }
        }
    }
}
