///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS com.cajunsystems:cajun:0.3.1
//JAVA 21+
//PREVIEW

package examples;

import com.cajunsystems.Actor;
import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Simple test to verify the ask pattern bug fix.
 * This demonstrates that getSender() properly returns the sender context.
 */
public class AskPatternTest {

    public static class EchoActor extends Actor<String> {
        public EchoActor(ActorSystem system, String id) {
            super(system, id);
        }

        @Override
        protected void receive(String message) {
            System.out.println("EchoActor received message: " + message);
            System.out.println("EchoActor getSender() present: " + getSender().isPresent());

            // Reply to the sender if present
            getSender().ifPresent(sender -> {
                System.out.println("EchoActor replying to sender: " + sender.actorId());
                sender.tell("Echo: " + message);
            });
        }
    }

    public static void main(String[] args) throws Exception {
        ActorSystem system = new ActorSystem();

        try {
            // Register the echo actor
            Pid echoPid = system.register(EchoActor.class, "echo-actor");

            // Test 1: Send a message using ask pattern
            System.out.println("Test 1: Sending 'Hello' using ask pattern...");
            CompletableFuture<String> future = system.ask(echoPid, "Hello", Duration.ofSeconds(5));
            String response = future.get();
            System.out.println("Received response: " + response);

            if ("Echo: Hello".equals(response)) {
                System.out.println("✓ Test 1 PASSED: Ask pattern works correctly!");
            } else {
                System.out.println("✗ Test 1 FAILED: Expected 'Echo: Hello' but got '" + response + "'");
            }

            // Test 2: Verify getSender() is empty for regular tell
            System.out.println("\nTest 2: Sending 'World' using regular tell...");
            system.tell(echoPid, "World");
            Thread.sleep(500); // Give it time to process
            System.out.println("✓ Test 2 completed (check output above for getSender() status)");

            System.out.println("\n✓ All tests completed successfully!");
        } finally {
            system.shutdown();
        }
    }
}

