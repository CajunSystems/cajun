///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS com.cajunsystems:cajun:0.3.1
//JAVA 21+
//PREVIEW

package examples;

import com.cajunsystems.Actor;
import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Example demonstrating sender context propagation through actor hierarchies.
 * Shows the difference between tell() and forward().
 */
public class SenderPropagationExample {

    // Message types
    record Request(String data) {}
    record Response(String result) {}

    /**
     * Grandparent actor that initiates requests
     */
    static class GrandparentActor extends Actor<Object> {
        public GrandparentActor(ActorSystem system, String actorId) {
            super(system, actorId);
        }

        @Override
        protected void receive(Object message) {
            if (message instanceof Response response) {
                getLogger().info("Grandparent received response: {}", response.result());
            }
        }
    }

    /**
     * Parent actor that forwards requests to child
     */
    static class ParentActorWithForward extends Actor<Object> {
        private Pid childPid;

        public ParentActorWithForward(ActorSystem system, String actorId) {
            super(system, actorId);
        }

        @Override
        protected void receive(Object message) {
            if (message instanceof Request request) {
                getLogger().info("Parent forwarding request to child (sender preserved)");
                Optional<Pid> sender = getSender();
                getLogger().info("Parent sees sender as: {}", sender);
                
                // Forward preserves the original sender (grandparent)
                forward(childPid, request);
            }
        }
    }

    /**
     * Parent actor that uses tell() instead of forward()
     */
    static class ParentActorWithTell extends Actor<Object> {
        private Pid childPid;

        public ParentActorWithTell(ActorSystem system, String actorId) {
            super(system, actorId);
        }

        @Override
        protected void receive(Object message) {
            if (message instanceof Request request) {
                getLogger().info("Parent sending request to child with tell() (sender lost)");
                Optional<Pid> sender = getSender();
                getLogger().info("Parent sees sender as: {}", sender);
                
                // tell() does NOT preserve sender context
                childPid.tell(request);
            }
        }
    }

    /**
     * Child actor that processes requests and replies to sender
     */
    static class ChildActor extends Actor<Object> {
        public ChildActor(ActorSystem system, String actorId) {
            super(system, actorId);
        }

        @Override
        protected void receive(Object message) {
            if (message instanceof Request request) {
                Optional<Pid> sender = getSender();
                getLogger().info("Child processing request. Sender is: {}", sender);
                
                // Process and reply to sender
                Response response = new Response("Processed: " + request.data());
                
                sender.ifPresentOrElse(
                    pid -> {
                        getLogger().info("Child replying to: {}", pid);
                        pid.tell(response);
                    },
                    () -> getLogger().warn("Child has no sender to reply to!")
                );
            }
        }
    }

    public static void main(String[] args) throws Exception {
        ActorSystem system = new ActorSystem();

        System.out.println("\n=== Example 1: Using forward() - Sender is preserved ===\n");
        demonstrateForward(system);
        
        Thread.sleep(1000);
        
        System.out.println("\n=== Example 2: Using tell() - Sender is lost ===\n");
        demonstrateTell(system);
        
        Thread.sleep(1000);
        system.shutdown();
    }

    private static void demonstrateForward(ActorSystem system) throws Exception {
        // Create actors
        Pid childPid = system.register(ChildActor.class, "child-forward");
        Pid parentPid = system.register(ParentActorWithForward.class, "parent-forward");
        ParentActorWithForward parent = (ParentActorWithForward) system.getActor(parentPid);
        parent.childPid = childPid;
        Pid grandparentPid = system.register(GrandparentActor.class, "grandparent-forward");

        // Grandparent asks parent
        CompletableFuture<Response> future = system.ask(
            parentPid,
            new Request("test data"),
            Duration.ofSeconds(1)
        );

        try {
            Response response = future.get(2, TimeUnit.SECONDS);
            System.out.println("✓ SUCCESS: Grandparent received response: " + response.result());
        } catch (Exception e) {
            System.out.println("✗ FAILED: " + e.getMessage());
        }
    }

    private static void demonstrateTell(ActorSystem system) throws Exception {
        // Create actors
        Pid childPid = system.register(ChildActor.class, "child-tell");
        Pid parentPid = system.register(ParentActorWithTell.class, "parent-tell");
        ParentActorWithTell parent = (ParentActorWithTell) system.getActor(parentPid);
        parent.childPid = childPid;
        Pid grandparentPid = system.register(GrandparentActor.class, "grandparent-tell");

        // Grandparent asks parent
        CompletableFuture<Response> future = system.ask(
            parentPid,
            new Request("test data"),
            Duration.ofSeconds(1)
        );

        try {
            Response response = future.get(2, TimeUnit.SECONDS);
            System.out.println("✓ SUCCESS: Grandparent received response: " + response.result());
        } catch (Exception e) {
            System.out.println("✗ EXPECTED TIMEOUT: Child has no sender to reply to (sender was lost)");
        }
    }
}
