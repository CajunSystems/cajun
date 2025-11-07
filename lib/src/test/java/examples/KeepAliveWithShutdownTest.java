///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS com.cajunsystems:cajun:0.1.4
//JAVA 21+
//PREVIEW

package examples;

import com.cajunsystems.ActorContext;
import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.handler.Handler;

/**
 * Test that demonstrates:
 * 1. JVM stays alive after main() exits (keep-alive thread)
 * 2. JVM properly exits when shutdown() is called
 */
public class KeepAliveWithShutdownTest {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Keep-Alive with Shutdown Test ===\n");
        
        System.out.println("1. Creating actor system...");
        ActorSystem system = new ActorSystem();

        System.out.println("2. Creating actor...");
        Pid actor = system.actorOf(new Handler<String>() {
            @Override
            public void receive(String message, ActorContext context) {
                System.out.println("   [ACTOR] Received: " + message);
            }
        }).withId("test-actor").spawn();

        System.out.println("3. Sending message...");
        actor.tell("Hello World!");
        
        // Wait for message processing
        Thread.sleep(500);

        System.out.println("\n4. Main thread will exit now...");
        System.out.println("   JVM should stay alive due to keep-alive thread.");
        
        // Schedule shutdown in a separate thread
        Thread shutdownThread = new Thread(() -> {
            try {
                System.out.println("\n5. Waiting 3 seconds before shutdown...");
                Thread.sleep(3000);
                System.out.println("6. Calling system.shutdown()...");
                system.shutdown();
                System.out.println("7. Shutdown complete. JVM should exit now.\n");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        shutdownThread.setDaemon(true); // Daemon thread won't prevent JVM exit
        shutdownThread.start();
        
        System.out.println("   Main thread exiting...\n");
        // Main exits, but JVM stays alive due to keep-alive thread
        // After 3 seconds, shutdown() is called and JVM exits
    }
}
