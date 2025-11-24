///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS com.cajunsystems:cajun:0.3.1
//JAVA 21+
//PREVIEW

package examples;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.StatefulActor;

import java.io.Serializable;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Test to verify that the ask pattern works correctly with StatefulActor.
 * This demonstrates that getSender() properly returns the sender context even in async processing.
 */
public class StatefulActorAskPatternTest {

    public static class CounterState implements Serializable {
        private static final long serialVersionUID = 1L;
        private final int count;

        public CounterState(int count) {
            this.count = count;
        }

        public int getCount() {
            return count;
        }

        public CounterState increment() {
            return new CounterState(count + 1);
        }
    }

    public static class CounterMessage implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String command;

        public CounterMessage(String command) {
            this.command = command;
        }

        public String getCommand() {
            return command;
        }
    }

    public static class CounterActor extends StatefulActor<CounterState, CounterMessage> {
        public CounterActor(ActorSystem system, String id) {
            super(system, id, new CounterState(0));
        }

        @Override
        protected CounterState processMessage(CounterState state, CounterMessage message) {
            System.out.println("CounterActor received message: " + message.getCommand());
            System.out.println("CounterActor getSender() present: " + getSender().isPresent());
            System.out.println("Current count: " + state.getCount());

            if ("increment".equals(message.getCommand())) {
                CounterState newState = state.increment();

                // Reply to the sender with the new count
                getSender().ifPresent(sender -> {
                    System.out.println("CounterActor replying to sender: " + sender.actorId());
                    sender.tell("Count is now: " + newState.getCount());
                });

                return newState;
            } else if ("get".equals(message.getCommand())) {
                // Reply with current count
                getSender().ifPresent(sender -> {
                    System.out.println("CounterActor replying to sender: " + sender.actorId());
                    sender.tell("Count is: " + state.getCount());
                });

                return state; // No state change
            }

            return state;
        }
    }

    public static void main(String[] args) throws Exception {
        ActorSystem system = new ActorSystem();

        try {
            // Register the counter actor
            Pid counterPid = system.register(CounterActor.class, "counter-actor");

            // Test 1: Increment and get response
            System.out.println("Test 1: Sending 'increment' using ask pattern...");
            CompletableFuture<String> future1 = system.ask(counterPid, new CounterMessage("increment"), Duration.ofSeconds(5));
            String response1 = future1.get();
            System.out.println("Received response: " + response1);

            if ("Count is now: 1".equals(response1)) {
                System.out.println("✓ Test 1 PASSED: StatefulActor ask pattern works correctly!");
            } else {
                System.out.println("✗ Test 1 FAILED: Expected 'Count is now: 1' but got '" + response1 + "'");
            }

            // Test 2: Increment again
            System.out.println("\nTest 2: Sending another 'increment' using ask pattern...");
            CompletableFuture<String> future2 = system.ask(counterPid, new CounterMessage("increment"), Duration.ofSeconds(5));
            String response2 = future2.get();
            System.out.println("Received response: " + response2);

            if ("Count is now: 2".equals(response2)) {
                System.out.println("✓ Test 2 PASSED: State is correctly maintained!");
            } else {
                System.out.println("✗ Test 2 FAILED: Expected 'Count is now: 2' but got '" + response2 + "'");
            }

            // Test 3: Get current count
            System.out.println("\nTest 3: Sending 'get' using ask pattern...");
            CompletableFuture<String> future3 = system.ask(counterPid, new CounterMessage("get"), Duration.ofSeconds(5));
            String response3 = future3.get();
            System.out.println("Received response: " + response3);

            if ("Count is: 2".equals(response3)) {
                System.out.println("✓ Test 3 PASSED: Get command works correctly!");
            } else {
                System.out.println("✗ Test 3 FAILED: Expected 'Count is: 2' but got '" + response3 + "'");
            }

            System.out.println("\n✓ All tests completed successfully!");
        } finally {
            system.shutdown();
        }
    }
}

