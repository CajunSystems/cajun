package examples;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import systems.cajun.Actor;
import systems.cajun.ActorSystem;
import systems.cajun.FunctionalStatefulActor;
import systems.cajun.Pid;
import systems.cajun.StatefulActor;
import systems.cajun.persistence.StateStore;
import systems.cajun.persistence.StateStoreFactory;

import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

/**
 * Example demonstrating the use of stateful actors.
 * This example shows:
 * 1. Creating and using a stateful actor with in-memory persistence
 * 2. Creating and using a stateful actor with file-based persistence
 * 3. Creating a chain of stateful actors
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

        // Create a counter actor with initial state 0
        CounterActor counterActor = new CounterActor(system, "counter", 0);
        counterActor.start();

        // Send increment messages
        counterActor.tell(new CounterMessage.Increment(5));
        counterActor.tell(new CounterMessage.Increment(10));

        // Wait for the messages to be processed
        Thread.sleep(100);

        // Get the current count
        CountDownLatch latch = new CountDownLatch(1);
        int[] result = new int[1];
        counterActor.tell(new CounterMessage.GetCount(count -> {
            result[0] = count;
            latch.countDown();
        }));

        // Wait for the result
        latch.await(1, TimeUnit.SECONDS);
        logger.info("Counter value: {}", result[0]);

        // Reset the counter
        counterActor.tell(new CounterMessage.Reset());

        // Wait for the reset to be processed
        Thread.sleep(100);

        // Get the count again
        CountDownLatch latch2 = new CountDownLatch(1);
        counterActor.tell(new CounterMessage.GetCount(count -> {
            result[0] = count;
            latch2.countDown();
        }));

        // Wait for the result
        latch2.await(1, TimeUnit.SECONDS);
        logger.info("Counter value after reset: {}", result[0]);

        // Shutdown the actor
        system.shutdown(counterActor.getActorId());
    }

    /**
     * Example of a counter actor with file-based persistence.
     */
    private static void fileBasedCounterExample(ActorSystem system, String stateDir) throws Exception {
        logger.info("Starting counter example with file-based persistence");

        // Create a state store
        StateStore<String, Integer> stateStore = StateStoreFactory.createFileStore(stateDir);

        // Create a counter actor with initial state 0 and file-based persistence
        CounterActor counterActor = new CounterActor(system, "file-counter", 0, stateStore);
        counterActor.start();

        // Send increment messages
        counterActor.tell(new CounterMessage.Increment(3));
        counterActor.tell(new CounterMessage.Increment(7));

        // Wait for the messages to be processed
        Thread.sleep(100);

        // Get the current count
        CountDownLatch latch = new CountDownLatch(1);
        int[] result = new int[1];
        counterActor.tell(new CounterMessage.GetCount(count -> {
            result[0] = count;
            latch.countDown();
        }));

        // Wait for the result
        latch.await(1, TimeUnit.SECONDS);
        logger.info("File-based counter value: {}", result[0]);

        // Shutdown the actor
        system.shutdown(counterActor.getActorId());

        // Create a new actor with the same ID to demonstrate state recovery
        logger.info("Creating a new actor with the same ID to demonstrate state recovery");
        CounterActor newCounterActor = new CounterActor(system, "file-counter", 0, stateStore);
        newCounterActor.start();

        // Get the recovered count
        CountDownLatch latch2 = new CountDownLatch(1);
        newCounterActor.tell(new CounterMessage.GetCount(count -> {
            result[0] = count;
            latch2.countDown();
        }));

        // Wait for the result
        latch2.await(1, TimeUnit.SECONDS);
        logger.info("Recovered counter value: {}", result[0]);

        // Shutdown the actor
        system.shutdown(newCounterActor.getActorId());

        // Close the state store
        stateStore.close();
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

        // Create the chain
        Pid firstActorPid = FunctionalStatefulActor.createChain(
                system, "counter-chain", 3, initialStates, actions);

        // Send messages to the chain
        firstActorPid.tell(new CounterMessage.Increment(1));

        // Wait for the message to propagate through the chain
        Thread.sleep(100);

        // Get the counts from each actor in the chain
        for (int i = 1; i <= 3; i++) {
            String actorId = "counter-chain-" + i;
            CountDownLatch latch = new CountDownLatch(1);
            int[] result = new int[1];

            @SuppressWarnings("unchecked")
            Actor<CounterMessage> actor = (Actor<CounterMessage>) system.getActor(new Pid(actorId, system));
            actor.tell(new CounterMessage.GetCount(count -> {
                result[0] = count;
                latch.countDown();
            }));

            latch.await(1, TimeUnit.SECONDS);
            logger.info("Actor {} count: {}", actorId, result[0]);
        }

        // Shutdown the chain
        for (int i = 1; i <= 3; i++) {
            system.shutdown("counter-chain-" + i);
        }
    }

    /**
     * Messages for the counter actor.
     */
    public sealed interface CounterMessage permits 
            CounterMessage.Increment, 
            CounterMessage.Reset, 
            CounterMessage.GetCount {

        /**
         * Message to increment the counter.
         *
         * @param amount The amount to increment by (ignored in the example)
         */
        record Increment(int amount) implements CounterMessage {}

        /**
         * Message to reset the counter to 0.
         */
        record Reset() implements CounterMessage {}

        /**
         * Message to get the current count.
         *
         * @param callback Callback to receive the count
         */
        record GetCount(java.util.function.Consumer<Integer> callback) implements CounterMessage {}
    }

    /**
     * A stateful actor that maintains a counter.
     */
    public static class CounterActor extends StatefulActor<Integer, CounterMessage> {

        public CounterActor(ActorSystem system, String actorId, Integer initialState) {
            super(system, actorId, initialState);
        }

        public CounterActor(ActorSystem system, String actorId, Integer initialState, 
                            StateStore<String, Integer> stateStore) {
            super(system, actorId, initialState, stateStore);
        }

        @Override
        protected Integer processMessage(Integer state, CounterMessage message) {
            if (message instanceof CounterMessage.Increment increment) {
                return state + increment.amount();
            } else if (message instanceof CounterMessage.Reset) {
                return 0;
            } else if (message instanceof CounterMessage.GetCount getCount) {
                getCount.callback().accept(state);
            }
            return state;
        }
    }
}
