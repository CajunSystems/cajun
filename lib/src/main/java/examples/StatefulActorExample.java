package examples;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import systems.cajun.ActorSystem;
import systems.cajun.FunctionalStatefulActor;
import systems.cajun.Pid;
import systems.cajun.StatefulActor;
import systems.cajun.SupervisionStrategy;
import systems.cajun.persistence.BatchedMessageJournal;
import systems.cajun.persistence.SnapshotStore;
// No unused imports
import systems.cajun.runtime.persistence.PersistenceFactory;

/**
 * Example demonstrating the use of stateful actors.
 * This example shows:
 * 1. Creating and using a stateful actor with in-memory persistence
 * 2. Creating and using a stateful actor with file-based persistence
 * 3. Creating a chain of stateful actors
 * 4. State initialization and recovery
 * 5. Error handling with supervision strategies
 * 
 * IMPORTANT SERIALIZATION REQUIREMENTS:
 * For persistence to work correctly with StatefulActor, both the State and Message types 
 * MUST be serializable:
 * - Both State and Message classes must implement java.io.Serializable
 * - All fields in these classes must also be serializable, or marked as transient
 * - For non-serializable fields (like lambdas or functional interfaces), use transient and
 *   implement custom serialization with readObject/writeObject methods
 * - Add serialVersionUID to all serializable classes to maintain compatibility
 *
 * Failure to meet these requirements will result in NotSerializableException during message
 * journaling or state snapshot operations.
 */
public class StatefulActorExample {

    private static final Logger logger = LoggerFactory.getLogger(StatefulActorExample.class);

    public static void main(String[] args) throws Exception {
        // Create a temporary directory for file-based state storage
        Path tempDir = Files.createTempDirectory("cajun-state");

        // Create an actor system
        ActorSystem system = new ActorSystem();

        // Example 1: Simple counter actor with in-memory persistence
        simpleCounterExample(system);

        // Example 2: Counter actor with file-based persistence
        fileBasedCounterExample(system, tempDir.toString());

        // Example 3: Chain of stateful actors
        statefulActorChainExample(system);
        
        // Example 4: Error handling demonstration
        errorHandlingExample(system);
        
        // Shutdown the actor system
        system.shutdown();

        // Clean up the temporary directory
        Files.walk(tempDir)
                .sorted((a, b) -> -a.compareTo(b))
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        logger.warn("Failed to delete {}", path, e);
                    }
                });
    }

    /**
     * Example of a simple counter actor with in-memory persistence.
     */
    private static void simpleCounterExample(ActorSystem system) throws Exception {
        logger.info("Starting simple counter example with in-memory persistence");

        // Create a counter actor with default persistence
        CounterActor counter = new CounterActor(system, "counter-1", 0, PersistenceFactory.createBatchedFileMessageJournal());
        
        // Start the actor and wait for state initialization to complete
        counter.start();
        counter.forceInitializeState().join();
        logger.info("Counter actor state initialized");

        // Send increment messages
        counter.tell(new CounterMessage.Increment(5));
        counter.tell(new CounterMessage.Increment(10));

        // Wait for the messages to be processed - using a more deterministic approach
        Thread.sleep(100);

        // Get the current count
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger result = new AtomicInteger();
        counter.tell(new CounterMessage.GetCount(count -> {
            result.set(count);
            latch.countDown();
        }));

        // Wait for the response
        latch.await(1, TimeUnit.SECONDS);
        int count = result.get();
        logger.info("Counter value: {}", count);

        // Reset the counter
        counter.tell(new CounterMessage.Reset());

        // Wait for the reset to be processed
        Thread.sleep(100);

        // Get the count again
        CountDownLatch latch2 = new CountDownLatch(1);
        AtomicInteger result2 = new AtomicInteger();
        counter.tell(new CounterMessage.GetCount(count2 -> {
            result2.set(count2);
            latch2.countDown();
        }));

        // Wait for the response
        latch2.await(1, TimeUnit.SECONDS);
        int count2 = result2.get();
        logger.info("Counter value after reset: {}", count2);

        // Shutdown the actor
        system.shutdown(counter.getActorId());
    }

    /**
     * Example of a counter actor with file-based persistence.
     */
    private static void fileBasedCounterExample(ActorSystem system, String stateDir) throws Exception {
        logger.info("Starting counter example with file-based persistence");

        // Create message journal and snapshot store for persistence
        BatchedMessageJournal<CounterMessage> messageJournal = PersistenceFactory.createBatchedFileMessageJournal(stateDir);
        SnapshotStore<Integer> snapshotStore = PersistenceFactory.createFileSnapshotStore(stateDir);

        // Create a counter actor with initial state 0 and file-based persistence
        CounterActor counter = new CounterActor(system, "file-counter", 0, messageJournal, snapshotStore);
        counter.start();
        
        // Wait for state initialization to complete
        counter.forceInitializeState().join();
        logger.info("File-based counter actor state initialized");

        // Send increment messages
        counter.tell(new CounterMessage.Increment(3));
        counter.tell(new CounterMessage.Increment(7));

        // Wait for the messages to be processed
        Thread.sleep(100);
        
        // Force a snapshot to ensure state is persisted
        counter.forceSnapshot().join();

        // Get the current count
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger result = new AtomicInteger();
        counter.tell(new CounterMessage.GetCount(count -> {
            result.set(count);
            latch.countDown();
        }));

        // Wait for the result
        latch.await(1, TimeUnit.SECONDS);
        logger.info("File-based counter value: {}", result.get());

        // Shutdown the actor
        system.shutdown(counter.getActorId());

        // Create a new actor with the same ID to demonstrate state recovery
        // Using null initial state to force recovery from persistence
        logger.info("Creating a new actor with the same ID to demonstrate state recovery");
        CounterActor newCounter = new CounterActor(system, "file-counter", null, messageJournal, snapshotStore);
        newCounter.start();
        
        // Wait for state initialization and recovery to complete
        newCounter.forceInitializeState().join();

        // Get the recovered count
        CountDownLatch latch2 = new CountDownLatch(1);
        AtomicInteger result2 = new AtomicInteger();
        newCounter.tell(new CounterMessage.GetCount(count -> {
            result2.set(count);
            latch2.countDown();
        }));

        // Wait for the result
        latch2.await(1, TimeUnit.SECONDS);
        logger.info("Recovered counter value: {}", result2.get());

        // Shutdown the actor
        system.shutdown(newCounter.getActorId());
    }

    /**
     * Example of a chain of stateful actors.
     */
    private static void statefulActorChainExample(ActorSystem system) throws Exception {
        logger.info("Starting stateful actor chain example");

        // Create initial states for the chain
        Integer[] initialStates = {0, 0, 0};

        // Create action functions for the chain
        @SuppressWarnings("unchecked")
        BiFunction<Integer, CounterMessage, Integer>[] actions = new BiFunction[3];

        // First actor: Increment by 1
        actions[0] = (state, message) -> {
            if (message instanceof CounterMessage.Increment) {
                return state + 1;
            } else if (message instanceof CounterMessage.Reset) {
                return 0;
            } else if (message instanceof CounterMessage.GetCount getCount) {
                getCount.callback().accept(state);
            }
            return state;
        };

        // Second actor: Increment by 2
        actions[1] = (state, message) -> {
            if (message instanceof CounterMessage.Increment) {
                return state + 2;
            } else if (message instanceof CounterMessage.Reset) {
                return 0;
            } else if (message instanceof CounterMessage.GetCount getCount) {
                getCount.callback().accept(state);
            }
            return state;
        };

        // Third actor: Increment by 3
        actions[2] = (state, message) -> {
            if (message instanceof CounterMessage.Increment) {
                return state + 3;
            } else if (message instanceof CounterMessage.Reset) {
                return 0;
            } else if (message instanceof CounterMessage.GetCount getCount) {
                getCount.callback().accept(state);
            }
            return state;
        };

        // Create individual actors instead of a chain to avoid UnsupportedOperationException
        String[] actorIds = new String[3];
        @SuppressWarnings("unchecked") // Suppressing the unchecked cast warning
        StatefulActor<Integer, CounterMessage>[] actors = new StatefulActor[3];
        
        for (int i = 0; i < 3; i++) {
            String actorId = "counter-chain-" + (i + 1);
            actorIds[i] = actorId;
            
            // Create a stateful actor using the static method
            actors[i] = FunctionalStatefulActor.createStatefulActor(
                system, 
                actorId, 
                initialStates[i], 
                actions[i],
                PersistenceFactory.createBatchedFileMessageJournal(),
                PersistenceFactory.createFileSnapshotStore()
            );
            
            // Start the actor
            actors[i].start();
            
            // Wait for initialization to complete
            actors[i].forceInitializeState().join();
        }

        // Send messages to the first actor
        Pid firstActorPid = new Pid(actorIds[0], system);
        firstActorPid.tell(new CounterMessage.Increment(1));

        // Wait for the message to be processed
        Thread.sleep(100);

        // Get the counts from each actor - using the direct actor references
        for (int i = 0; i < 3; i++) {
            String actorId = actorIds[i];
            CountDownLatch latch = new CountDownLatch(1);
            int[] result = new int[1];

            // Use the direct actor reference instead of looking it up
            actors[i].tell(new CounterMessage.GetCount(count -> {
                result[0] = count;
                latch.countDown();
            }));

            latch.await(1, TimeUnit.SECONDS);
            logger.info("Actor {} count: {}", actorId, result[0]);
        }

        // Shutdown the actors
        for (String actorId : actorIds) {
            system.shutdown(actorId);
        }
    }

    /**
     * Messages for the counter actor.
     * 
     * IMPORTANT: This interface and all its implementations must implement java.io.Serializable
     * for persistence to work correctly. Each implementation should also define a serialVersionUID.
     */
    public sealed interface CounterMessage extends systems.cajun.persistence.OperationAwareMessage permits 
            CounterMessage.Increment, 
            CounterMessage.Reset, 
            CounterMessage.GetCount,
            CounterMessage.ThrowError {

        /**
         * Message to increment the counter.
         *
         * @param amount The amount to increment by (ignored in the example)
         */
        record Increment(int amount) implements CounterMessage {
            private static final long serialVersionUID = 1L;
            
            @Override
            public boolean isReadOnly() {
                return false;
            }
        }

        /**
         * Message to reset the counter to 0.
         */
        record Reset() implements CounterMessage {
            private static final long serialVersionUID = 1L;
            
            @Override
            public boolean isReadOnly() {
                return false;
            }
        }

        /**
         * Message to get the current count.
         *
         * @param callback Callback to receive the count
         */
        /**
         * Message to get the current count.
         * 
         * IMPORTANT SERIALIZATION EXAMPLE:
         * This class demonstrates how to handle non-serializable fields like Consumer:
         * 1. Mark the field as transient to exclude it from serialization
         * 2. Implement custom readObject method to handle deserialization
         * 3. Provide a default value for the transient field after deserialization
         */
        static final class GetCount implements CounterMessage {
            private static final long serialVersionUID = 1L;
            // Consumer is not Serializable by default, so we mark it as transient
            private transient java.util.function.Consumer<Integer> callback;
            
            @Override
            public boolean isReadOnly() {
                return true;
            }
            
            public GetCount(java.util.function.Consumer<Integer> callback) {
                this.callback = callback;
            }
            
            public java.util.function.Consumer<Integer> callback() {
                return callback;
            }
            
            // Handle the case where the callback is null after deserialization
            private void readObject(java.io.ObjectInputStream in) throws java.io.IOException, ClassNotFoundException {
                in.defaultReadObject();
                // If deserialized, provide a no-op callback
                if (callback == null) {
                    callback = (i) -> {};
                }
            }
        }
        
        /**
         * Message to intentionally throw an error for testing error handling.
         */
        record ThrowError() implements CounterMessage {
            private static final long serialVersionUID = 1L;
            
            @Override
            public boolean isReadOnly() {
                return false;
            }
        }
    }

    /**
     * A stateful actor that maintains a counter.
     */
    public static class CounterActor extends StatefulActor<Integer, CounterMessage> {

        public CounterActor(ActorSystem system, String actorId, Integer initialState) {
            super(system, actorId, initialState);
            // Configure error handling strategy
            withSupervisionStrategy(SupervisionStrategy.RESTART);
        }

        public CounterActor(ActorSystem system, String actorId, Integer initialState, 
                            BatchedMessageJournal<CounterMessage> messageJournal) {
            super(system, actorId, initialState, messageJournal);
            // Configure error handling strategy
            withSupervisionStrategy(SupervisionStrategy.RESTART);
        }
        
        public CounterActor(ActorSystem system, String actorId, Integer initialState, 
                            BatchedMessageJournal<CounterMessage> messageJournal,
                            SnapshotStore<Integer> snapshotStore) {
            super(system, actorId, initialState, messageJournal, snapshotStore);
            // Configure error handling strategy
            withSupervisionStrategy(SupervisionStrategy.RESTART);
        }
        
        @Override
        protected void preStart() {
            super.preStart();
            logger.debug("CounterActor {} starting", getActorId());
        }
        
        @Override
        protected void postStop() {
            super.postStop();
            logger.debug("CounterActor {} stopping", getActorId());
        }
        
        @Override
        protected boolean onError(CounterMessage message, Throwable error) {
            logger.error("Error in CounterActor {}: {}", getActorId(), error.getMessage());
            return super.onError(message, error);
        }

        @Override
        protected Integer processMessage(Integer state, CounterMessage message) {
            // Handle null state (could happen during recovery)
            if (state == null) {
                state = 0;
            }
            
            try {
                if (message instanceof CounterMessage.Increment increment) {
                    return state + increment.amount();
                } else if (message instanceof CounterMessage.Reset) {
                    return 0;
                } else if (message instanceof CounterMessage.GetCount getCount) {
                    getCount.callback().accept(state);
                } else if (message instanceof CounterMessage.ThrowError) {
                    throw new RuntimeException("Intentional error for demonstration");
                }
                return state;
            } catch (Exception e) {
                // This will be caught by the actor framework and passed to onError
                throw e;
            }
        }
        
        /**
         * Forces a snapshot to be taken immediately.
         */
        public CompletableFuture<Void> forceSnapshot() {
            return super.forceSnapshot();
        }
    }
    
    /**
     * Example demonstrating error handling in stateful actors.
     */
    private static void errorHandlingExample(ActorSystem system) throws Exception {
        logger.info("Starting error handling example");
        
        // Create a counter actor with error handling strategy
        CounterActor counter = new CounterActor(system, "error-counter", 0);
        counter.start();
        counter.forceInitializeState().join();
        
        // Increment the counter
        counter.tell(new CounterMessage.Increment(5));
        Thread.sleep(100);
        
        // Get the current count
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger result = new AtomicInteger();
        counter.tell(new CounterMessage.GetCount(count -> {
            result.set(count);
            latch.countDown();
        }));
        
        // Wait for the response
        latch.await(1, TimeUnit.SECONDS);
        logger.info("Counter value before error: {}", result.get());
        
        // Send a message that will cause an error
        logger.info("Sending message that will cause an error");
        counter.tell(new CounterMessage.ThrowError());
        
        // Wait for error handling to complete
        Thread.sleep(500);
        
        // Verify the actor is still responsive after error
        CountDownLatch latch2 = new CountDownLatch(1);
        AtomicInteger result2 = new AtomicInteger();
        counter.tell(new CounterMessage.GetCount(count -> {
            result2.set(count);
            latch2.countDown();
        }));
        
        // Wait for the response
        latch2.await(1, TimeUnit.SECONDS);
        logger.info("Counter value after error: {}", result2.get());
        
        // Shutdown the actor
        system.shutdown(counter.getActorId());
    }
}
