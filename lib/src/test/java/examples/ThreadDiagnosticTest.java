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
 * Diagnostic test to examine thread behavior and liveness.
 * This will print all active threads before main() exits.
 */
public class ThreadDiagnosticTest {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Thread Diagnostic Test ===\n");
        
        System.out.println("1. Creating actor system...");
        ActorSystem system = new ActorSystem();

        System.out.println("2. Creating actor...");
        Pid actor = system.actorOf(new Handler<String>() {
            @Override
            public void receive(String message, ActorContext context) {
                System.out.println("   [ACTOR] Received: " + message);
            }
        }).withId("diagnostic-actor").spawn();

        System.out.println("3. Sending message...");
        actor.tell("Test message");
        
        // Wait for message processing
        Thread.sleep(1000);

        System.out.println("\n4. Listing all active threads:");
        System.out.println("   (Non-daemon threads keep the JVM alive)\n");
        
        ThreadGroup rootGroup = Thread.currentThread().getThreadGroup();
        while (rootGroup.getParent() != null) {
            rootGroup = rootGroup.getParent();
        }
        
        Thread[] threads = new Thread[rootGroup.activeCount() * 2];
        int count = rootGroup.enumerate(threads, true);
        
        int nonDaemonCount = 0;
        for (int i = 0; i < count; i++) {
            Thread t = threads[i];
            if (t != null) {
                String daemon = t.isDaemon() ? "[DAEMON]" : "[NON-DAEMON]";
                System.out.printf("   %-15s %-30s %s%n", daemon, t.getName(), t.getState());
                if (!t.isDaemon()) {
                    nonDaemonCount++;
                }
            }
        }
        
        System.out.println("\n5. Summary:");
        System.out.println("   Total threads: " + count);
        System.out.println("   Non-daemon threads: " + nonDaemonCount);
        
        System.out.println("\n6. Main thread exiting...");
        System.out.println("   Expected: JVM stays alive if non-daemon threads > 1");
        System.out.println("   (1 non-daemon thread would be main, which is exiting)");
        System.out.println("\n   Waiting to see if JVM exits or stays alive...");
        System.out.println("   If it stays alive, you'll need to press Ctrl+C to exit.\n");
        
        // Main exits here
    }
}
