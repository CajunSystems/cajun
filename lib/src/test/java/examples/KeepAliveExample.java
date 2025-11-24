///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS com.cajunsystems:cajun:0.3.1
//JAVA 21+
//PREVIEW

package examples;

import com.cajunsystems.ActorContext;
import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.handler.Handler;

/**
 * Example demonstrating that the actor system keeps the JVM alive
 * after the main method completes, until system.shutdown() is called.
 * 
 * To test: Run this example and observe that it doesn't exit automatically.
 * The JVM will stay alive because the actor system has a non-daemon keep-alive thread.
 * You'll need to manually terminate the process (Ctrl+C) or wait for the shutdown.
 */
public class KeepAliveExample {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("Starting actor system...");
        
        ActorSystem system = new ActorSystem();

        // Create an actor
        Pid actor = system.actorOf(new Handler<String>() {
            @Override
            public void receive(String message, ActorContext context) {
                System.out.println("Actor received: " + message);
            }
        }).withId("demo-actor").spawn();

        // Send a message
        actor.tell("Hello from main!");
        
        // Wait a bit for message processing
        Thread.sleep(500);

        System.out.println("Main method completed!");
        System.out.println("Notice: JVM is still running because the actor system has a keep-alive thread.");
        System.out.println("The actor system will keep the JVM alive until system.shutdown() is called.");
        
        // Schedule shutdown after 3 seconds to demonstrate
        Thread shutdownThread = new Thread(() -> {
            try {
                Thread.sleep(3000);
                System.out.println("\nShutting down actor system...");
                system.shutdown();
                System.out.println("Actor system shut down. JVM will now exit.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        shutdownThread.setDaemon(false); // Non-daemon to ensure it runs
        shutdownThread.start();
        
        System.out.println("Main thread exiting, but JVM stays alive...\n");
        // Main thread exits here, but JVM continues running
    }
}
