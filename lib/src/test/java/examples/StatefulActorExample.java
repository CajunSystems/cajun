///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS com.cajunsystems:cajun:0.1.4
//JAVA 21+
//PREVIEW

package examples;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.cajunsystems.ActorContext;
import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.SupervisionStrategy;
import com.cajunsystems.handler.StatefulHandler;
import com.cajunsystems.persistence.BatchedMessageJournal;
import com.cajunsystems.persistence.OperationAwareMessage;
import com.cajunsystems.persistence.SnapshotStore;
import com.cajunsystems.runtime.persistence.PersistenceFactory;
import com.cajunsystems.config.BackpressureConfig;
import com.cajunsystems.backpressure.BackpressureStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Example demonstrating the use of stateful actors with the interface-based approach.
 * This example shows:
 * 1. Creating and using a stateful actor with in-memory persistence
 * 2. Creating and using a stateful actor with file-based persistence
 * 3. Creating a chain of stateful actors
 * 4. State initialization and recovery
 * 5. Error handling with supervision strategies
 * 
 * IMPORTANT SERIALIZATION REQUIREMENTS:
 * For persistence to work correctly, both the State and Message types 
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

        // Create backpressure configuration
        BackpressureConfig backpressureConfig = new BackpressureConfig.Builder()
                .warningThreshold(0.8f)
                .criticalThreshold(0.9f)
                .recoveryThreshold(0.2f)
                .strategy(BackpressureStrategy.BLOCK)
                .maxCapacity(1000)
                .build();
                
        logger.info("Created backpressure configuration: warning={}, critical={}, recovery={}",
                backpressureConfig.getWarningThreshold(),
                backpressureConfig.getCriticalThreshold(),
                backpressureConfig.getRecoveryThreshold());

        // Example 1: Simple counter actor with in-memory persistence
        simpleCounterExample(system, backpressureConfig);

        // Example 2: Counter actor with file-based persistence
        fileBasedCounterExample(system, tempDir.toString(), backpressureConfig);

        // Example 3: Chain of stateful actors
        statefulActorChainExample(system, backpressureConfig);
        
        // Example 4: Error handling demonstration
        errorHandlingExample(system, backpressureConfig);
        
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
    private static void simpleCounterExample(ActorSystem system, BackpressureConfig backpressureConfig) throws Exception {
        logger.info("Starting simple counter example with in-memory persistence");

        // Create a counter actor with default persistence using the interface-based approach
        Pid counterPid = system.statefulActorOf(CounterHandler.class, 0)
            .withId("counter-1")
            .withBackpressureConfig(backpressureConfig)
            .spawn();
        
        logger.info("Counter actor created and started");

        // Send increment messages
        counterPid.tell(new CounterMessage.Increment(5));
        counterPid.tell(new CounterMessage.Increment(10));

        // Wait for the messages to be processed - using a more deterministic approach
        Thread.sleep(100);

        // Get the current count
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger result = new AtomicInteger();
        counterPid.tell(new CounterMessage.GetCount(count -> {
            result.set(count);
            latch.countDown();
        }));
        
        latch.await(1, TimeUnit.SECONDS);
        int count = result.get();
        logger.info("Counter value: {}", count);
        
        // Reset the counter
        counterPid.tell(new CounterMessage.Reset());
        
        // Get the current count after reset
        CountDownLatch latch2 = new CountDownLatch(1);
        AtomicInteger result2 = new AtomicInteger();
        counterPid.tell(new CounterMessage.GetCount(count2 -> {
            result2.set(count2);
            latch2.countDown();
        }));
        
        latch2.await(1, TimeUnit.SECONDS);
        int count2 = result2.get();
        logger.info("Counter value after reset: {}", count2);

        // Shutdown the actor
        system.shutdown(counterPid.actorId());
    }

    /**
     * Example of a counter actor with file-based persistence.
     */
    private static void fileBasedCounterExample(ActorSystem system, String persistenceDir, BackpressureConfig backpressureConfig) throws Exception {
        logger.info("Starting file-based counter example");
        
        // Create a file-based message journal and snapshot store
        BatchedMessageJournal<CounterMessage> messageJournal =
                PersistenceFactory.createBatchedFileMessageJournal(persistenceDir + "/journal");
        SnapshotStore<Integer> snapshotStore =
                PersistenceFactory.createFileSnapshotStore(persistenceDir + "/snapshots");
        
        // Create a counter actor with file-based persistence using the interface-based approach
        Pid counterPid = system.statefulActorOf(CounterHandler.class, 0)
            .withId("file-counter")
            .withPersistence(messageJournal, snapshotStore)
            .withBackpressureConfig(backpressureConfig)
            .spawn();
            
        logger.info("File-based counter actor created and started");

        // Send increment messages
        counterPid.tell(new CounterMessage.Increment(3));
        counterPid.tell(new CounterMessage.Increment(7));
        
        // Get the current count
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger result = new AtomicInteger();
        counterPid.tell(new CounterMessage.GetCount(count -> {
            result.set(count);
            latch.countDown();
        }));
        
        latch.await(1, TimeUnit.SECONDS);
        logger.info("File-based counter value: {}", result.get());
        
        // Stop the actor
        system.shutdown(counterPid.actorId());
        logger.info("File-based counter actor stopped");
        
        // Create a new actor with the same ID to demonstrate state recovery
        Pid recoveredCounterPid = system.statefulActorOf(CounterHandler.class, 0)
            .withId("file-counter")
            .withPersistence(messageJournal, snapshotStore)
            .withBackpressureConfig(backpressureConfig)
            .spawn();
            
        logger.info("Recovered counter actor created and started");
        
        // Get the recovered count
        CountDownLatch latch2 = new CountDownLatch(1);
        AtomicInteger recoveredResult = new AtomicInteger();
        recoveredCounterPid.tell(new CounterMessage.GetCount(count -> {
            recoveredResult.set(count);
            latch2.countDown();
        }));
        
        latch2.await(1, TimeUnit.SECONDS);
        logger.info("Recovered counter value: {}", recoveredResult.get());
    }

    /**
     * Example of a chain of stateful actors.
     */
    private static void statefulActorChainExample(ActorSystem system, BackpressureConfig backpressureConfig) throws Exception {
        logger.info("Starting stateful actor chain example");
        
        // Create three different handlers for the chain
        CounterHandlerMultiplier handler1 = new CounterHandlerMultiplier(1);
        CounterHandlerMultiplier handler2 = new CounterHandlerMultiplier(2);
        CounterHandlerMultiplier handler3 = new CounterHandlerMultiplier(3);
        
        // Create three actors with the handlers
        Pid actor1Pid = system.statefulActorOf(handler1, 0)
            .withId("counter-chain-1")
            .withBackpressureConfig(backpressureConfig)
            .spawn();
            
        Pid actor2Pid = system.statefulActorOf(handler2, 0)
            .withId("counter-chain-2")
            .withBackpressureConfig(backpressureConfig)
            .spawn();
            
        Pid actor3Pid = system.statefulActorOf(handler3, 0)
            .withId("counter-chain-3")
            .withBackpressureConfig(backpressureConfig)
            .spawn();
        
        // Connect the actors in a chain by setting up message forwarding
        handler1.setNextActor(actor2Pid);
        handler2.setNextActor(actor3Pid);
        
        logger.info("Stateful actor chain created");
        
        // Send a message to the first actor in the chain
        // It will be processed by all actors in the chain
        actor1Pid.tell(new CounterMessage.Increment(1));
        
        // Wait for processing to complete
        Thread.sleep(500);
        
        // Get the final count from the last actor in the chain
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger result = new AtomicInteger();
        actor3Pid.tell(new CounterMessage.GetCount(count -> {
            result.set(count);
            latch.countDown();
        }));
        
        latch.await(1, TimeUnit.SECONDS);
        logger.info("Final count from chain: {}", result.get());
    }

    /**
     * Example demonstrating error handling in stateful actors.
     */
    private static void errorHandlingExample(ActorSystem system, BackpressureConfig backpressureConfig) throws Exception {
        logger.info("Starting error handling example");
        
        // Create a counter actor with error handling strategy
        Pid counterPid = system.statefulActorOf(ErrorProneCounterHandler.class, 0)
            .withId("error-counter")
            .withSupervisionStrategy(SupervisionStrategy.RESUME)
            .withBackpressureConfig(backpressureConfig)
            .spawn();
        
        logger.info("Counter actor created and started");
        
        // Increment the counter
        counterPid.tell(new CounterMessage.Increment(5));
        Thread.sleep(100);
        
        // Get the current count
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger result = new AtomicInteger();
        counterPid.tell(new CounterMessage.GetCount(count -> {
            result.set(count);
            latch.countDown();
        }));
        
        // Wait for the response
        latch.await(1, TimeUnit.SECONDS);
        logger.info("Counter value before error: {}", result.get());
        
        // Send a message that will cause an error
        logger.info("Sending message that will cause an error");
        counterPid.tell(new CounterMessage.ThrowError());
        
        // Wait for error handling to complete
        Thread.sleep(500);
        
        // Verify the actor is still responsive after error
        CountDownLatch latch2 = new CountDownLatch(1);
        AtomicInteger result2 = new AtomicInteger();
        counterPid.tell(new CounterMessage.GetCount(count -> {
            result2.set(count);
            latch2.countDown();
        }));
        
        // Wait for the response
        latch2.await(1, TimeUnit.SECONDS);
        logger.info("Counter value after error: {}", result2.get());
        
        // Shutdown the actor
        system.shutdown(counterPid.actorId());
    }

    /**
     * Messages for the counter actor.
     * 
     * IMPORTANT: This interface and all its implementations must implement java.io.Serializable
     * for persistence to work correctly. Each implementation should also define a serialVersionUID.
     */
    public sealed interface CounterMessage extends OperationAwareMessage permits
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
    /**
     * Handler implementation for the counter actor using the new interface-based approach.
     */
    public static class CounterHandler implements StatefulHandler<Integer, CounterMessage> {
        private static final Logger logger = LoggerFactory.getLogger(CounterHandler.class);
        
        @Override
        public Integer receive(CounterMessage message, Integer state, ActorContext context) {
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
        
        @Override
        public Integer preStart(Integer state, ActorContext context) {
            logger.debug("CounterHandler for actor {} starting", context.getActorId());
            return state;
        }
        
        @Override
        public void postStop(Integer state, ActorContext context) {
            logger.debug("CounterHandler for actor {} stopping", context.getActorId());
        }
        
        @Override
        public boolean onError(CounterMessage message, Integer state, Throwable exception, ActorContext context) {
            logger.error("Error in CounterHandler for actor {}: {}", context.getActorId(), exception.getMessage());
            return false; // Let the supervision strategy handle it
        }
    }
    
    /**
     * A specialized counter handler that multiplies the increment amount by a factor.
     * Used for the actor chain example.
     */
    public static class CounterHandlerMultiplier implements StatefulHandler<Integer, CounterMessage> {
        private static final Logger logger = LoggerFactory.getLogger(CounterHandlerMultiplier.class);
        private final int multiplier;
        private Pid nextActor;
        
        public CounterHandlerMultiplier(int multiplier) {
            this.multiplier = multiplier;
        }
        
        public void setNextActor(Pid nextActor) {
            this.nextActor = nextActor;
        }
        
        @Override
        public Integer receive(CounterMessage message, Integer state, ActorContext context) {
            // Handle null state (could happen during recovery)
            if (state == null) {
                state = 0;
            }
            
            try {
                if (message instanceof CounterMessage.Increment increment) {
                    logger.info("Actor {} incrementing by {}", context.getActorId(), increment.amount() * multiplier);
                    Integer newState = state + (increment.amount() * multiplier);
                    
                    // Forward the message to the next actor in the chain if set
                    if (nextActor != null) {
                        context.tell(nextActor, message);
                    }
                    
                    return newState;
                } else if (message instanceof CounterMessage.Reset) {
                    return 0;
                } else if (message instanceof CounterMessage.GetCount getCount) {
                    getCount.callback().accept(state);
                }
                return state;
            } catch (Exception e) {
                throw e;
            }
        }
    }
    
    /**
     * A counter handler that intentionally throws an error for testing error handling.
     */
    public static class ErrorProneCounterHandler implements StatefulHandler<Integer, CounterMessage> {
        private static final Logger logger = LoggerFactory.getLogger(ErrorProneCounterHandler.class);
        
        @Override
        public Integer receive(CounterMessage message, Integer state, ActorContext context) {
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
        
        @Override
        public Integer preStart(Integer state, ActorContext context) {
            logger.debug("ErrorProneCounterHandler for actor {} starting", context.getActorId());
            return state;
        }
        
        @Override
        public void postStop(Integer state, ActorContext context) {
            logger.debug("ErrorProneCounterHandler for actor {} stopping", context.getActorId());
        }
        
        @Override
        public boolean onError(CounterMessage message, Integer state, Throwable exception, ActorContext context) {
            logger.error("Error in ErrorProneCounterHandler for actor {}: {}", context.getActorId(), exception.getMessage());
            return false; // Let the supervision strategy handle it
        }
    }
}
