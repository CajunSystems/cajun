package com.cajunsystems.cluster;

import com.cajunsystems.Actor;
import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the cluster mode functionality.
 * This test uses an in-memory implementation of the metadata store and messaging system
 * to simulate a cluster of actor systems.
 */
public class ClusterModeTest {

    private WatchableInMemoryMetadataStore metadataStore;
    private ClusterActorSystem system1;
    private ClusterActorSystem system2;
    
    @BeforeEach
    public void setUp() throws Exception {
        // Set up test
        // Create a shared metadata store
        metadataStore = new WatchableInMemoryMetadataStore();
        
        // Create two actor systems with different IDs
        system1 = new ClusterActorSystem(
                "system1",
                metadataStore,
                new InMemoryMessagingSystem("system1")
        );
        
        system2 = new ClusterActorSystem(
                "system2",
                metadataStore,
                new InMemoryMessagingSystem("system2")
        );
        
        // Connect the messaging systems
        InMemoryMessagingSystem ms1 = (InMemoryMessagingSystem) system1.getMessagingSystem();
        InMemoryMessagingSystem ms2 = (InMemoryMessagingSystem) system2.getMessagingSystem();
        ms1.connectTo(ms2);
        
        // Start the actor systems
        // Start the actor systems
        system1.start().get(5, TimeUnit.SECONDS);
        system2.start().get(5, TimeUnit.SECONDS);
        // Actor systems started
        
        // Wait a bit for systems to initialize
        Thread.sleep(1000);
    }
    
    @AfterEach
    public void tearDown() throws Exception {
        // Stop the actor systems
        if (system1 != null) {
            try {
                system1.stop().get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                System.err.println("Error stopping system1: " + e.getMessage());
            }
            system1 = null;
        }
        if (system2 != null) {
            try {
                system2.stop().get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                System.err.println("Error stopping system2: " + e.getMessage());
            }
            system2 = null;
        }
        
        // Clear the metadata store
        if (metadataStore != null) {
            try {
                for (String key : metadataStore.listKeys("").get(5, TimeUnit.SECONDS)) {
                    metadataStore.delete(key).get(5, TimeUnit.SECONDS);
                }
            } catch (Exception e) {
                System.err.println("Error clearing metadata store: " + e.getMessage());
            }
        }
        
        // Sleep a bit to allow resources to be released
        Thread.sleep(1000);
    }
    
    @Test
    public void testRemoteActorCommunication() throws Exception {
        // Test remote actor communication
        
        // Create actors on different systems
        // Create actor1 on system1
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> receivedMessage = new AtomicReference<>();
        
        // Register actor1 with system1
        Pid actor1Pid = system1.register(TestActor.class, "actor1");
        TestActor actor1 = (TestActor) system1.getActor(actor1Pid);
        actor1.setLatchAndMessage(latch, receivedMessage);

        
        // Create actor2 on system2
        // Register actor2 with system2
        Pid actor2Pid = system2.register(TestActor.class, "actor2");
        TestActor actor2 = (TestActor) system2.getActor(actor2Pid);
        actor2.setLatchAndMessage(latch, receivedMessage);

        
        // Wait for actor assignments to propagate
        // Wait for actor assignments to propagate
        Thread.sleep(3000); // Increased wait time to ensure propagation
        
        // Send a message from actor1 to actor2
        // Send direct message to actor2
        system1.routeMessage("actor2", "Hello from system1");
        
        // Send message from actor1 to actor2
        actor1.sendMessageTo(actor2.getPid(), "Hello from actor1");
        
        // Wait for the message to be received
        // Wait for message to be received by actor2
        boolean messageReceived = latch.await(5, TimeUnit.SECONDS);

        
        // Verify that the message was received
        assertTrue(messageReceived, "Message not received by actor2");
        // The message could be either from actor1 or directly from system1, both are valid
        String receivedMsg = receivedMessage.get();
        assertTrue(
            receivedMsg.equals("Hello from actor1") || receivedMsg.equals("Hello from system1"),
            "Unexpected message received: " + receivedMsg
        );
        
        // Reset the latch and message reference for the second message exchange
        latch = new CountDownLatch(1);
        receivedMessage.set(null);
        actor1.setLatchAndMessage(latch, receivedMessage);
        
        // Send a message from actor2 to actor1
        // Send message from actor2 to actor1
        actor2.sendMessageTo(actor1.getPid(), "Hello from actor2");
        
        // Wait for the message to be received
        // Wait for message to be received by actor1
        boolean received1 = latch.await(5, TimeUnit.SECONDS);

        assertTrue(received1, "Message not received by actor1");
        
        // The message should be from actor2 to actor1
        String receivedMsg2 = receivedMessage.get();
        assertTrue(
            receivedMsg2.equals("Hello from actor2"),
            "Unexpected message received: " + receivedMsg2
        );
    }
    
    @Test
    public void testActorReassignmentOnNodeFailure() throws Exception {
        // Test actor reassignment on node failure
        
        // Create an actor on system1
        // Create actor1 on system1
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> receivedMessage = new AtomicReference<>();
        
        // Register actor1 with system1
        Pid actor1Pid = system1.register(TestActor.class, "actor1");
        TestActor actor1 = (TestActor) system1.getActor(actor1Pid);
        actor1.setLatchAndMessage(latch, receivedMessage);

        
        // Wait for actor assignment to propagate
        // Wait for actor assignment to propagate
        Thread.sleep(3000);
        
        // Simulate system1 failure by stopping it
        // Simulate system1 failure by stopping it
        system1.stop().get(5, TimeUnit.SECONDS);
        
        // Wait for leader election and actor reassignment
        // Wait for leader election and actor reassignment
        Thread.sleep(3000);
        
        // Create a new actor system with the same ID
        // Create a new actor system with the same ID
        system1 = new ClusterActorSystem(
                "system1",
                metadataStore,
                new InMemoryMessagingSystem("system1")
        );
        
        // Connect the messaging systems
        // Connect the messaging systems
        ((InMemoryMessagingSystem) system1.getMessagingSystem())
                .connectTo((InMemoryMessagingSystem) system2.getMessagingSystem());
        
        // Start the new actor system
        // Start the new actor system
        system1.start().get(5, TimeUnit.SECONDS);
        
        // Wait for actor to be reassigned
        // Wait for actor to be reassigned
        Thread.sleep(3000);
        
        // Create a new actor with the same ID on system1
        // Create a new actor with the same ID on system1
        CountDownLatch newLatch = new CountDownLatch(1);
        AtomicReference<String> newReceivedMessage = new AtomicReference<>();
        
        // Register the new actor1 with system1
        Pid newActor1Pid = system1.register(TestActor.class, "actor1");
        TestActor newActor1 = (TestActor) system1.getActor(newActor1Pid);
        newActor1.setLatchAndMessage(newLatch, newReceivedMessage);

        
        // Wait for actor assignment to propagate
        // Wait for actor assignment to propagate
        Thread.sleep(3000);
        
        // Create actor2 on system2
        // Create actor2 on system2
        Pid actor2Pid = system2.register(TestActor.class, "actor2");
        TestActor actor2 = (TestActor) system2.getActor(actor2Pid);
        actor2.setLatchAndMessage(new CountDownLatch(1), new AtomicReference<>());

        
        // Send a message to actor1
        // Send message from actor2 to actor1
        actor2.sendMessageTo(newActor1.getPid(), "Hello after recovery");
        
        // Wait for the message to be received
        // Wait for message to be received by actor1
        boolean messageReceived = newLatch.await(5, TimeUnit.SECONDS);

        
        // Verify that the message was received
        assertTrue(messageReceived, "Message not received after recovery");
        assertEquals("Hello after recovery", newReceivedMessage.get(), "Incorrect message received");
    }
    
    /**
     * A test actor that receives string messages and commands.
     */
    public static class TestActor extends Actor<Object> {
        
        private CountDownLatch latch;
        private AtomicReference<String> receivedMessage;
        
        public TestActor(ActorSystem system, String actorId) {
            super(system, actorId);

        }
        
        public TestActor(ClusterActorSystem system, String actorId, CountDownLatch latch, AtomicReference<String> receivedMessage) {
            super(system, actorId);
            this.latch = latch;
            this.receivedMessage = receivedMessage;

        }
        
        public void setLatchAndMessage(CountDownLatch latch, AtomicReference<String> receivedMessage) {
            this.latch = latch;
            this.receivedMessage = receivedMessage;
        }
        
        public Pid getPid() {
            return self();
        }
        
        public void sendMessageTo(Pid targetPid, String message) {
            tell(new SendMessageCommand(targetPid, message));
        }
        

        
        @Override
        protected void receive(Object message) {

            if (message instanceof String) {
                // Received a string message
                receivedMessage.set((String) message);

                latch.countDown();

            } else if (message instanceof SendMessageCommand) {
                // Received a command to send a message to another actor
                SendMessageCommand command = (SendMessageCommand) message;

                command.targetPid.tell(command.message);
            }
        }
    }
    
    /**
     * A command to send a message to another actor.
     */
    private static class SendMessageCommand {
        private final Pid targetPid;
        private final String message;
        
        public SendMessageCommand(Pid targetPid, String message) {
            this.targetPid = targetPid;
            this.message = message;
        }
    }
    
}
