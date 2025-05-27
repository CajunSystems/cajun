package com.cajunsystems;

import com.cajunsystems.metrics.ActorMetrics;
import com.cajunsystems.metrics.MetricsRegistry;
import com.cajunsystems.persistence.BatchedMessageJournal;
import com.cajunsystems.persistence.JournalEntry;
import com.cajunsystems.persistence.SnapshotEntry;
import com.cajunsystems.persistence.SnapshotStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Performance tests for the StatefulActor system.
 * These tests are tagged with "performance" and are not run during normal test execution.
 * To run these tests specifically, use: ./gradlew test --tests "com.cajunsystems.StatefulActorPerformanceTest" or
 * ./gradlew test -PincludeTags="performance"
 */
@Tag("performance")
public class StatefulActorPerformanceTest {

    private static final Logger logger = LoggerFactory.getLogger(StatefulActorPerformanceTest.class);
    private ActorSystem actorSystem;

    @BeforeEach
    void setUp() {
        actorSystem = new ActorSystem();
        // Reset metrics registry before each test
        MetricsRegistry.resetAllMetrics();
    }

    @AfterEach
    void tearDown() {
        if (actorSystem != null) {
            actorSystem.shutdown();
        }
    }

    /**
     * Tests the throughput of message processing in a single StatefulActor.
     * This test measures how quickly a single actor can process messages while maintaining state.
     */
    @Test
    void testSingleActorThroughput() throws InterruptedException {
        final int MESSAGE_COUNT = 100_000;
        
        // Create in-memory persistence components for maximum performance
        InMemoryBatchedMessageJournal<CounterMessage> messageJournal = new InMemoryBatchedMessageJournal<>();
        InMemorySnapshotStore<CounterState> snapshotStore = new InMemorySnapshotStore<>();
        
        // Configure for optimal performance
        messageJournal.setMaxBatchSize(1000);
        messageJournal.setMaxBatchDelayMs(50);
        
        // Create a counter actor with initial state
        CounterActor actor = new CounterActor(actorSystem, "counter-perf", new CounterState(0), 
                                             messageJournal, snapshotStore);
        actor.configureSnapshotStrategy(5000, 10000); // Take snapshots less frequently
        actor.start();
        
        // Wait for actor to initialize
        CompletableFuture<Void> initFuture = actor.forceInitializeState();
        initFuture.join();
        
        // Create a latch to track completion
        CountDownLatch completionLatch = new CountDownLatch(MESSAGE_COUNT);
        
        // Measure throughput
        long startTime = System.nanoTime();
        
        // Send messages to the actor
        for (int i = 0; i < MESSAGE_COUNT; i++) {
            actor.tell(new CounterMessage.Increment(1, () -> completionLatch.countDown()));
        }
        
        // Wait for all messages to be processed
        boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
        long endTime = System.nanoTime();
        
        if (!completed) {
            logger.warn("Not all messages were processed within the timeout period");
        }
        
        double elapsedSeconds = (endTime - startTime) / 1_000_000_000.0;
        double messagesPerSecond = MESSAGE_COUNT / elapsedSeconds;
        
        // Get metrics for the actor
        ActorMetrics metrics = actor.getMetrics();
        
        logger.info("StatefulActor Single Actor Performance Test Results:");
        logger.info("Messages Sent: {}", MESSAGE_COUNT);
        logger.info("Elapsed Time: {} seconds", elapsedSeconds);
        logger.info("Throughput: {} messages/second", String.format("%.2f", messagesPerSecond));
        logger.info("Final Counter Value: {}", actor.getCurrentValue());
        
        // Log metrics information
        logger.info("Metrics - Message Count: {}", metrics.getMessageCount());
        logger.info("Metrics - State Changes: {}", metrics.getStateChangeCount());
        logger.info("Metrics - Snapshots Taken: {}", metrics.getSnapshotCount());
        logger.info("Metrics - Errors: {}", metrics.getErrorCount());
        logger.info("Metrics - Avg Processing Time: {} ms", String.format("%.3f", metrics.getAverageProcessingTimeMs()));
        logger.info("Metrics - Min Processing Time: {} ns", metrics.getMinProcessingTimeNs());
        logger.info("Metrics - Max Processing Time: {} ns", metrics.getMaxProcessingTimeNs());
    }
    
    /**
     * Tests the throughput of message processing in multiple StatefulActors.
     * This test measures how quickly multiple actors can process messages in parallel.
     */
    @Test
    void testMultipleActorThroughput() throws InterruptedException {
        final int ACTOR_COUNT = 10;
        final int MESSAGES_PER_ACTOR = 10_000;
        final int TOTAL_MESSAGES = ACTOR_COUNT * MESSAGES_PER_ACTOR;
        
        // Create a list to hold all actors
        List<CounterActor> actors = new ArrayList<>(ACTOR_COUNT);
        
        // Create a latch to track completion
        CountDownLatch completionLatch = new CountDownLatch(TOTAL_MESSAGES);
        
        // Create actors
        for (int i = 0; i < ACTOR_COUNT; i++) {
            // Create in-memory persistence components for maximum performance
            InMemoryBatchedMessageJournal<CounterMessage> messageJournal = new InMemoryBatchedMessageJournal<>();
            InMemorySnapshotStore<CounterState> snapshotStore = new InMemorySnapshotStore<>();
            
            // Configure for optimal performance
            messageJournal.setMaxBatchSize(1000);
            messageJournal.setMaxBatchDelayMs(50);
            
            // Create a counter actor with initial state
            CounterActor actor = new CounterActor(actorSystem, "counter-" + i, new CounterState(0), 
                                                messageJournal, snapshotStore);
            actor.configureSnapshotStrategy(5000, 10000); // Take snapshots less frequently
            actor.start();
            
            // Wait for actor to initialize
            CompletableFuture<Void> initFuture = actor.forceInitializeState();
            initFuture.join();
            
            actors.add(actor);
        }
        
        // Measure throughput
        long startTime = System.nanoTime();
        
        // Send messages to all actors
        for (int a = 0; a < ACTOR_COUNT; a++) {
            CounterActor actor = actors.get(a);
            for (int i = 0; i < MESSAGES_PER_ACTOR; i++) {
                actor.tell(new CounterMessage.Increment(1, () -> completionLatch.countDown()));
            }
        }
        
        // Wait for all messages to be processed
        boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
        long endTime = System.nanoTime();
        
        if (!completed) {
            logger.warn("Not all messages were processed within the timeout period");
        }
        
        double elapsedSeconds = (endTime - startTime) / 1_000_000_000.0;
        double messagesPerSecond = TOTAL_MESSAGES / elapsedSeconds;
        
        logger.info("StatefulActor Multiple Actor Performance Test Results:");
        logger.info("Number of Actors: {}", ACTOR_COUNT);
        logger.info("Messages Per Actor: {}", MESSAGES_PER_ACTOR);
        logger.info("Total Messages: {}", TOTAL_MESSAGES);
        logger.info("Elapsed Time: {} seconds", elapsedSeconds);
        logger.info("Throughput: {} messages/second", String.format("%.2f", messagesPerSecond));
        
        // Log metrics from MetricsRegistry
        logger.info("Total Message Count (from registry): {}", MetricsRegistry.getTotalMessageCount());
        logger.info("Total State Changes (from registry): {}", MetricsRegistry.getTotalStateChangeCount());
        logger.info("Total Snapshots (from registry): {}", MetricsRegistry.getTotalSnapshotCount());
        logger.info("Total Errors (from registry): {}", MetricsRegistry.getTotalErrorCount());
        logger.info("Average Processing Time (from registry): {} ms", String.format("%.3f", MetricsRegistry.getAverageProcessingTimeMs()));
        
        // Log actor with highest message count
        ActorMetrics highestMsgCountActor = MetricsRegistry.getActorWithHighestMessageCount();
        if (highestMsgCountActor != null) {
            logger.info("Actor with highest message count: {} (count: {})", 
                      highestMsgCountActor.getActorId(), highestMsgCountActor.getMessageCount());
        }
        
        // Log actor with highest processing time
        ActorMetrics slowestActor = MetricsRegistry.getActorWithHighestAverageProcessingTime();
        if (slowestActor != null) {
            logger.info("Actor with highest avg processing time: {} ({} ms)", 
                      slowestActor.getActorId(), String.format("%.3f", slowestActor.getAverageProcessingTimeMs()));
        }
        
        // Log individual actor results
        for (int i = 0; i < ACTOR_COUNT; i++) {
            CounterActor actor = actors.get(i);
            ActorMetrics metrics = actor.getMetrics();
            
            logger.info("Actor {}: Final Counter Value: {}, Avg Processing Time: {} ms", 
                      i, actor.getCurrentValue(), String.format("%.3f", metrics.getAverageProcessingTimeMs()));
        }
    }
    
    /**
     * Tests the performance impact of different snapshot strategies.
     * This test compares throughput with different snapshot frequencies.
     */
    @Test
    void testSnapshotStrategyImpact() throws InterruptedException {
        final int MESSAGE_COUNT = 50_000;
        final int[] SNAPSHOT_INTERVALS = {100, 1000, 10000}; // Different snapshot intervals to test
        
        for (int snapshotInterval : SNAPSHOT_INTERVALS) {
            // Reset metrics registry before each snapshot interval test
            MetricsRegistry.resetAllMetrics();
            
            // Create in-memory persistence components
            InMemoryBatchedMessageJournal<CounterMessage> messageJournal = new InMemoryBatchedMessageJournal<>();
            InMemorySnapshotStore<CounterState> snapshotStore = new InMemorySnapshotStore<>();
            
            // Configure for optimal performance
            messageJournal.setMaxBatchSize(1000);
            messageJournal.setMaxBatchDelayMs(50);
            
            // Create a counter actor with initial state
            CounterActor actor = new CounterActor(actorSystem, "counter-snapshot-" + snapshotInterval, 
                                                new CounterState(0), messageJournal, snapshotStore);
            actor.configureSnapshotStrategy(1000, snapshotInterval); // Set snapshot interval
            actor.start();
            
            // Wait for actor to initialize
            CompletableFuture<Void> initFuture = actor.forceInitializeState();
            initFuture.join();
            
            // Create a latch to track completion
            CountDownLatch completionLatch = new CountDownLatch(MESSAGE_COUNT);
            
            // Measure throughput
            long startTime = System.nanoTime();
            
            // Send messages to the actor
            for (int i = 0; i < MESSAGE_COUNT; i++) {
                actor.tell(new CounterMessage.Increment(1, () -> completionLatch.countDown()));
            }
            
            // Wait for all messages to be processed
            boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
            long endTime = System.nanoTime();
            
            if (!completed) {
                logger.warn("Not all messages were processed within the timeout period");
            }
            
            double elapsedSeconds = (endTime - startTime) / 1_000_000_000.0;
            double messagesPerSecond = MESSAGE_COUNT / elapsedSeconds;
            
            // Get metrics for the actor
            ActorMetrics metrics = actor.getMetrics();
            
            logger.info("StatefulActor Snapshot Strategy Performance Test Results:");
            logger.info("Snapshot Interval: {} messages", snapshotInterval);
            logger.info("Messages Sent: {}", MESSAGE_COUNT);
            logger.info("Elapsed Time: {} seconds", elapsedSeconds);
            logger.info("Throughput: {} messages/second", String.format("%.2f", messagesPerSecond));
            logger.info("Final Counter Value: {}", actor.getCurrentValue());
            logger.info("Number of Snapshots Taken (store): {}", snapshotStore.getSnapshotCount("counter-snapshot-" + snapshotInterval));
            logger.info("Number of Snapshots Taken (metrics): {}", metrics.getSnapshotCount());
            logger.info("Average Processing Time: {} ms", String.format("%.3f", metrics.getAverageProcessingTimeMs()));
            logger.info("State Changes: {}", metrics.getStateChangeCount());
            logger.info("-----------------------------------------------------");
        }
    }
    
    /**
     * Tests the recovery performance of StatefulActor.
     * This test measures how quickly an actor can recover state from snapshots and journal.
     */
    @Test
    void testRecoveryPerformance() throws InterruptedException {
        final int MESSAGE_COUNT = 50_000;
        
        // Create in-memory persistence components that will be shared between actors
        InMemoryBatchedMessageJournal<CounterMessage> messageJournal = new InMemoryBatchedMessageJournal<>();
        InMemorySnapshotStore<CounterState> snapshotStore = new InMemorySnapshotStore<>();
        
        // Configure for optimal performance
        messageJournal.setMaxBatchSize(1000);
        messageJournal.setMaxBatchDelayMs(50);
        
        // Step 1: Create first actor and populate state
        ActorMetrics initialActorMetrics;
        {
            CounterActor actor = new CounterActor(actorSystem, "counter-recovery", 
                                                new CounterState(0), messageJournal, snapshotStore);
            actor.configureSnapshotStrategy(1000, 10000);
            actor.start();
            
            // Wait for actor to initialize
            CompletableFuture<Void> initFuture = actor.forceInitializeState();
            initFuture.join();
            
            // Create a latch to track completion
            CountDownLatch completionLatch = new CountDownLatch(MESSAGE_COUNT);
            
            // Send messages to the actor
            for (int i = 0; i < MESSAGE_COUNT; i++) {
                actor.tell(new CounterMessage.Increment(1, () -> completionLatch.countDown()));
            }
            
            // Wait for all messages to be processed
            boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
            
            if (!completed) {
                logger.warn("Not all messages were processed within the timeout period");
            }
            
            // Force a final snapshot
            actor.forceSnapshot().join();
            
            // Save metrics before stopping
            initialActorMetrics = actor.getMetrics();
            
            // Stop the actor
            actor.stop();
            
            logger.info("Initial actor stopped after processing {} messages", MESSAGE_COUNT);
            logger.info("Final Counter Value: {}", actor.getCurrentValue());
            logger.info("Initial Actor Metrics - Messages: {}, State Changes: {}, Snapshots: {}", 
                      initialActorMetrics.getMessageCount(), 
                      initialActorMetrics.getStateChangeCount(),
                      initialActorMetrics.getSnapshotCount());
        }
        
        // Reset metrics registry before recovery
        MetricsRegistry.resetAllMetrics();
        
        // Step 2: Create a new actor with the same ID and measure recovery time
        long startTime = System.nanoTime();
        
        CounterActor recoveredActor = new CounterActor(actorSystem, "counter-recovery", 
                                                     new CounterState(0), messageJournal, snapshotStore);
        recoveredActor.start();
        
        // Wait for actor to initialize and recover state
        CompletableFuture<Void> recoveryFuture = recoveredActor.forceInitializeState();
        recoveryFuture.join();
        
        long endTime = System.nanoTime();
        
        double recoveryTimeSeconds = (endTime - startTime) / 1_000_000_000.0;
        ActorMetrics recoveryMetrics = recoveredActor.getMetrics();
        
        logger.info("StatefulActor Recovery Performance Test Results:");
        logger.info("Messages to Recover: {}", MESSAGE_COUNT);
        logger.info("Recovery Time: {} seconds", recoveryTimeSeconds);
        logger.info("Recovered Counter Value: {}", recoveredActor.getCurrentValue());
        logger.info("Recovery Metrics - Messages Processed: {}", recoveryMetrics.getMessageCount());
        logger.info("Recovery Metrics - State Changes: {}", recoveryMetrics.getStateChangeCount());
        logger.info("Recovery Metrics - Snapshots: {}", recoveryMetrics.getSnapshotCount());
        logger.info("Messages Processed Per Second During Recovery: {} msg/s", 
                  String.format("%.2f", recoveryMetrics.getMessageCount() / recoveryTimeSeconds));
        
        // Verify recovery was successful
        assertEquals(MESSAGE_COUNT, recoveredActor.getCurrentValue(), 
                    "Recovered state should match the number of messages processed");
    }
    
    private void assertEquals(int expected, int actual, String message) {
        if (expected != actual) {
            logger.error("{}: expected {}, got {}", message, expected, actual);
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }
    
    /**
     * A simple stateful actor that maintains a counter.
     */
    static class CounterActor extends StatefulActor<CounterState, CounterMessage> {
        
        public CounterActor(
                ActorSystem system, 
                String actorId, 
                CounterState initialState,
                BatchedMessageJournal<CounterMessage> messageJournal,
                SnapshotStore<CounterState> snapshotStore) {
            super(system, actorId, initialState, messageJournal, snapshotStore);
        }
        
        @Override
        protected CounterState processMessage(CounterState state, CounterMessage message) {
            if (message instanceof CounterMessage.Increment) {
                CounterMessage.Increment increment = (CounterMessage.Increment) message;
                CounterState newState = state.increment(increment.getValue());
                
                // Execute the callback if provided
                if (increment.getCallback() != null) {
                    increment.getCallback().run();
                }
                
                return newState;
            }
            return state;
        }
        
        public int getCurrentValue() {
            CounterState state = getState();
            return state != null ? state.getValue() : 0;
        }
    }
    
    /**
     * State class for the counter actor.
     */
    static class CounterState implements Serializable {
        private static final long serialVersionUID = 1L;
        private final int value;
        
        public CounterState(int value) {
            this.value = value;
        }
        
        public int getValue() {
            return value;
        }
        
        public CounterState increment(int amount) {
            return new CounterState(value + amount);
        }
    }
    
    /**
     * Message interface for the counter actor.
     */
    interface CounterMessage extends Serializable {
        
        /**
         * Message to increment the counter.
         */
        class Increment implements CounterMessage {
            private static final long serialVersionUID = 1L;
            private final int value;
            private final transient Runnable callback;
            
            public Increment(int value) {
                this(value, null);
            }
            
            public Increment(int value, Runnable callback) {
                this.value = value;
                this.callback = callback;
            }
            
            public int getValue() {
                return value;
            }
            
            public Runnable getCallback() {
                return callback;
            }
        }
    }
    
    /**
     * In-memory implementation of BatchedMessageJournal for testing.
     */
    static class InMemoryBatchedMessageJournal<M extends Serializable> implements BatchedMessageJournal<M> {
        private final List<JournalEntry<M>> entries = new CopyOnWriteArrayList<>();
        private int maxBatchSize = 100;
        private long maxBatchDelayMs = 10;
        private final AtomicInteger sequenceCounter = new AtomicInteger(0);
        
        @Override
        public CompletableFuture<Long> append(String actorId, M message) {
            if (actorId == null) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Actor ID cannot be null"));
            }
            long sequence = sequenceCounter.incrementAndGet();
            entries.add(new JournalEntry<>(sequence, actorId, message, Instant.now()));
            return CompletableFuture.completedFuture(sequence);
        }
        
        @Override
        public CompletableFuture<List<Long>> appendBatch(String actorId, List<M> messages) {
            if (actorId == null) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Actor ID cannot be null"));
            }
            if (messages == null || messages.isEmpty()) {
                return CompletableFuture.completedFuture(Collections.emptyList());
            }
            
            List<Long> sequences = new ArrayList<>();
            for (M message : messages) {
                long sequence = sequenceCounter.incrementAndGet();
                entries.add(new JournalEntry<>(sequence, actorId, message, Instant.now()));
                sequences.add(sequence);
            }
            return CompletableFuture.completedFuture(sequences);
        }


    @Override
    public CompletableFuture<List<JournalEntry<M>>> readFrom(String actorId, long fromSequence) {
        if (actorId == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Actor ID cannot be null"));
        }
        
        List<JournalEntry<M>> result = new ArrayList<>();
        for (JournalEntry<M> entry : entries) {
            if (entry != null && entry.getActorId() != null && 
                entry.getActorId().equals(actorId) && 
                entry.getSequenceNumber() >= fromSequence) {
                result.add(entry);
            }
        }
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public CompletableFuture<Void> truncateBefore(String actorId, long beforeSequence) {
        if (actorId == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Actor ID cannot be null"));
        }
        
        // CopyOnWriteArrayList doesn't support removeIf directly, so we need a different approach
        List<JournalEntry<M>> toRemove = new ArrayList<>();
        for (JournalEntry<M> entry : entries) {
            if (entry != null && entry.getActorId() != null && 
                entry.getActorId().equals(actorId) && 
                entry.getSequenceNumber() < beforeSequence) {
                toRemove.add(entry);
            }
        }
        entries.removeAll(toRemove);
        
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Long> getHighestSequenceNumber(String actorId) {
        if (actorId == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Actor ID cannot be null"));
        }
        
        long highest = -1;
        for (JournalEntry<M> entry : entries) {
            if (entry != null && entry.getActorId() != null && 
                entry.getActorId().equals(actorId) && 
                entry.getSequenceNumber() > highest) {
                highest = entry.getSequenceNumber();
            }
        }
        return CompletableFuture.completedFuture(highest);
    }
    
    @Override
    public void setMaxBatchSize(int maxBatchSize) {
        this.maxBatchSize = maxBatchSize;
        // In a real implementation, this would affect batching behavior
        logger.debug("Setting max batch size to {} for in-memory journal", maxBatchSize);
    }
    
    @Override
    public void setMaxBatchDelayMs(long maxBatchDelayMs) {
        this.maxBatchDelayMs = maxBatchDelayMs;
        // In a real implementation, this would affect batching behavior
        logger.debug("Setting max batch delay to {}ms for in-memory journal", maxBatchDelayMs);
    }
    
    @Override
    public CompletableFuture<Void> flush() {
        return CompletableFuture.completedFuture(null);
    }
    
    @Override
    public void close() {
        // No resources to close in this in-memory implementation
    }
    }
    
    /**
     * In-memory implementation of SnapshotStore for testing.
     */
    static class InMemorySnapshotStore<S> implements SnapshotStore<S> {
        private final List<SnapshotEntry<S>> snapshots = new CopyOnWriteArrayList<>();
        
        @Override
        public CompletableFuture<Void> saveSnapshot(String actorId, S state, long sequenceNumber) {
            if (actorId == null) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Actor ID cannot be null"));
            }
            snapshots.add(new SnapshotEntry<>(actorId, state, sequenceNumber));
            return CompletableFuture.completedFuture(null);
        }
        
        @Override
        public CompletableFuture<java.util.Optional<SnapshotEntry<S>>> getLatestSnapshot(String actorId) {
            if (actorId == null) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Actor ID cannot be null"));
            }
            
            SnapshotEntry<S> latest = null;
            long highestSequence = -1;
            
            for (SnapshotEntry<S> snapshot : snapshots) {
                if (snapshot != null && snapshot.getActorId() != null && 
                    snapshot.getActorId().equals(actorId) && 
                    snapshot.getSequenceNumber() > highestSequence) {
                    latest = snapshot;
                    highestSequence = snapshot.getSequenceNumber();
                }
            }
            
            return CompletableFuture.completedFuture(java.util.Optional.ofNullable(latest));
        }
        
        @Override
        public CompletableFuture<Void> deleteSnapshots(String actorId) {
            if (actorId == null) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Actor ID cannot be null"));
            }
            
            // CopyOnWriteArrayList doesn't support removeIf directly, so we need a different approach
            List<SnapshotEntry<S>> toRemove = new ArrayList<>();
            for (SnapshotEntry<S> snapshot : snapshots) {
                if (snapshot != null && snapshot.getActorId() != null && 
                    snapshot.getActorId().equals(actorId)) {
                    toRemove.add(snapshot);
                }
            }
            snapshots.removeAll(toRemove);
            
            return CompletableFuture.completedFuture(null);
        }
        
        public int getSnapshotCount(String actorId) {
            if (actorId == null) {
                return 0;
            }
            
            int count = 0;
            for (SnapshotEntry<S> snapshot : snapshots) {
                if (snapshot != null && snapshot.getActorId() != null && 
                    snapshot.getActorId().equals(actorId)) {
                    count++;
                }
            }
            return count;
        }
        
        @Override
        public void close() {
            // No resources to close in this in-memory implementation
        }
    }
}
