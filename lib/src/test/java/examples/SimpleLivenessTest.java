///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS com.cajunsystems:cajun:0.3.0
//JAVA 21+
//PREVIEW

package examples;

import com.cajunsystems.ActorContext;
import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.handler.Handler;

/**
 * Minimal example to test if the actor system keeps the JVM alive.
 * 
 * Expected behavior: After "Main thread exiting" is printed, the JVM should
 * stay alive indefinitely (until Ctrl+C) because the actor system threads
 * are non-daemon threads.
 * 
 * Actual behavior (if bug exists): The JVM exits immediately after main() completes.
 * 
 * To test:
 * 1. Run this class
 * 2. Observe if the JVM stays alive after "Main thread exiting" is printed
 * 3. If it exits immediately, the liveness problem exists
 */
public class SimpleLivenessTest {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("[" + System.currentTimeMillis() + "] Creating actor system...");
        
        ActorSystem system = new ActorSystem();

        System.out.println("[" + System.currentTimeMillis() + "] Creating actor...");
        
        // Create a simple actor that prints messages
        Pid actor = system.actorOf(new Handler<String>() {
            @Override
            public void receive(String message, ActorContext context) {
                System.out.println("[" + System.currentTimeMillis() + "] Actor received: " + message);
            }
        }).withId("test-actor").spawn();

        System.out.println("[" + System.currentTimeMillis() + "] Sending message...");
        
        // Send a message
        actor.tell("Hello World!");
        
        // Give it a moment to process
        Thread.sleep(500);

        System.out.println("[" + System.currentTimeMillis() + "] Main thread exiting...");
        System.out.println("If the JVM exits now, the liveness problem exists!");
        System.out.println("If the JVM stays alive, press Ctrl+C to exit.");
        
        // Main thread exits here
        // Expected: JVM should stay alive because actor system threads are non-daemon
        // If bug exists: JVM will exit immediately
    }
}
