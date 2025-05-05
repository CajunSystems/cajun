package systems.cajun.cluster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import systems.cajun.Actor;
import systems.cajun.ActorSystem;
import systems.cajun.Pid;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests to verify that messages between actors on the same node are delivered
 * directly and not through the messaging system.
 */
public class ClusterLocalActorTest {

    private InMemoryMetadataStore metadataStore;
    private MockMessagingSystem messagingSystem;
    private ClusterActorSystem system;
    
    @BeforeEach
    public void setUp() throws Exception {
        // Create a metadata store and messaging system
        metadataStore = new InMemoryMetadataStore();
        messagingSystem = new MockMessagingSystem("system1");
        
        // Create a cluster actor system
        system = new ClusterActorSystem(
                "system1",
                metadataStore,
                messagingSystem
        );
        
        // Start the actor system
        system.start().get(5, TimeUnit.SECONDS);
        
        // Wait a bit for system to initialize
        Thread.sleep(1000);
    }
    
    @AfterEach
    public void tearDown() throws Exception {
        // Stop the actor system
        if (system != null) {
            try {
                system.stop().get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                System.err.println("Error stopping system: " + e.getMessage());
            }
            system = null;
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
    
    /**
     * Test that verifies messages between actors on the same node are delivered directly
     * and not through the messaging system.
     */
    @Test
    public void testLocalActorCommunication() throws Exception {
        // Create a completion latch to wait for message receipt
        CountDownLatch messageLatch = new CountDownLatch(1);
        AtomicReference<String> receivedMessage = new AtomicReference<>();
        
        // Create the receiver actor
        Pid receiverPid = system.register(TestActor.class, "receiver-actor");
        TestActor receiver = (TestActor) system.getActor(receiverPid);
        receiver.setLatchAndMessage(messageLatch, receivedMessage);
        
        // Create the sender actor
        Pid senderPid = system.register(TestActor.class, "sender-actor");
        TestActor sender = (TestActor) system.getActor(senderPid);
        
        // Wait for actor registrations to complete
        // The issue might be that the actor registration in the metadata store is async
        // and not fully complete before we try to send messages
        
        // More robust wait to ensure actor registration is complete
        // Wait longer than the default 1 second
        Thread.sleep(3000);
        
        // Reset the message counter in the messaging system
        messagingSystem.resetMessageCount();
        
        // Send a message from sender to receiver
        sender.sendMessageTo(receiverPid, "Hello from sender");
        
        // Wait for the message to be received
        boolean messageReceived = messageLatch.await(5, TimeUnit.SECONDS);
        
        // Verify that the message was received
        assertTrue(messageReceived, "Message not received by receiver actor");
        assertEquals("Hello from sender", receivedMessage.get(), "Incorrect message received");
        
        // Verify that the message was not sent through the messaging system
        assertEquals(0, messagingSystem.getMessageCount(), 
                "Message was sent through the messaging system instead of being delivered directly");
    }
    
    /**
     * A test actor that can receive and send messages.
     */
    public static class TestActor extends Actor<Object> {
        
        private CountDownLatch latch;
        private AtomicReference<String> receivedMessage;
        
        public TestActor(ActorSystem system, String actorId) {
            super(system, actorId);
        }
        
        public void setLatchAndMessage(CountDownLatch latch, AtomicReference<String> receivedMessage) {
            this.latch = latch;
            this.receivedMessage = receivedMessage;
        }
        
        public void sendMessageTo(Pid targetPid, String message) {
            targetPid.tell(message);
        }
        
        @Override
        protected void receive(Object message) {
            if (message instanceof String) {
                if (receivedMessage != null) {
                    receivedMessage.set((String) message);
                }
                
                if (latch != null) {
                    latch.countDown();
                }
            }
        }
    }
    
    /**
     * A mock messaging system that counts messages sent through it.
     */
    private static class MockMessagingSystem implements MessagingSystem {
        
        private final String systemId;
        private MessageHandler messageHandler;
        private final AtomicInteger messageCount = new AtomicInteger(0);
        private boolean running = false;
        
        public MockMessagingSystem(String systemId) {
            this.systemId = systemId;
        }
        
        public int getMessageCount() {
            return messageCount.get();
        }
        
        public void resetMessageCount() {
            messageCount.set(0);
        }
        
        @Override
        public <Message> java.util.concurrent.CompletableFuture<Void> sendMessage(
                String targetSystemId, String actorId, Message message) {
            // Count the message
            messageCount.incrementAndGet();
            
            // We don't actually deliver the message in this mock
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        
        @Override
        public java.util.concurrent.CompletableFuture<Void> registerMessageHandler(MessageHandler handler) {
            return java.util.concurrent.CompletableFuture.runAsync(() -> this.messageHandler = handler);
        }
        
        @Override
        public java.util.concurrent.CompletableFuture<Void> start() {
            return java.util.concurrent.CompletableFuture.runAsync(() -> this.running = true);
        }
        
        @Override
        public java.util.concurrent.CompletableFuture<Void> stop() {
            return java.util.concurrent.CompletableFuture.runAsync(() -> this.running = false);
        }
    }
    
    /**
     * An in-memory implementation of the MetadataStore interface for testing.
     */
    private static class InMemoryMetadataStore implements MetadataStore {
        
        private final java.util.concurrent.ConcurrentHashMap<String, String> store = new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.concurrent.ConcurrentHashMap<String, Lock> locks = new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.concurrent.ConcurrentHashMap<String, KeyWatcher> watchers = new java.util.concurrent.ConcurrentHashMap<>();
        
        @Override
        public java.util.concurrent.CompletableFuture<Void> put(String key, String value) {
            return java.util.concurrent.CompletableFuture.runAsync(() -> {
                store.put(key, value);
                
                // Notify watchers
                for (java.util.Map.Entry<String, KeyWatcher> entry : watchers.entrySet()) {
                    if (key.startsWith(entry.getKey())) {
                        entry.getValue().onPut(key, value);
                    }
                }
            });
        }
        
        @Override
        public java.util.concurrent.CompletableFuture<java.util.Optional<String>> get(String key) {
            return java.util.concurrent.CompletableFuture.supplyAsync(() -> java.util.Optional.ofNullable(store.get(key)));
        }
        
        @Override
        public java.util.concurrent.CompletableFuture<Void> delete(String key) {
            return java.util.concurrent.CompletableFuture.runAsync(() -> {
                store.remove(key);
                
                // Notify watchers
                for (java.util.Map.Entry<String, KeyWatcher> entry : watchers.entrySet()) {
                    if (key.startsWith(entry.getKey())) {
                        entry.getValue().onDelete(key);
                    }
                }
            });
        }
        
        @Override
        public java.util.concurrent.CompletableFuture<java.util.List<String>> listKeys(String prefix) {
            return java.util.concurrent.CompletableFuture.supplyAsync(() -> 
                store.keySet().stream()
                    .filter(key -> key.startsWith(prefix))
                    .toList()
            );
        }
        
        @Override
        public java.util.concurrent.CompletableFuture<java.util.Optional<Lock>> acquireLock(String lockName, long ttlSeconds) {
            return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                if (locks.containsKey(lockName)) {
                    return java.util.Optional.empty();
                }
                
                InMemoryLock lock = new InMemoryLock(lockName);
                locks.put(lockName, lock);
                return java.util.Optional.of(lock);
            });
        }
        
        @Override
        public java.util.concurrent.CompletableFuture<Long> watch(String key, KeyWatcher watcher) {
            return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                long watchId = System.nanoTime();
                watchers.put(key, watcher);
                return watchId;
            });
        }
        
        @Override
        public java.util.concurrent.CompletableFuture<Void> unwatch(long watchId) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        
        @Override
        public java.util.concurrent.CompletableFuture<Void> connect() {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        
        @Override
        public java.util.concurrent.CompletableFuture<Void> close() {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        
        private class InMemoryLock implements Lock {
            
            private final String lockName;
            
            InMemoryLock(String lockName) {
                this.lockName = lockName;
            }
            
            @Override
            public java.util.concurrent.CompletableFuture<Void> release() {
                return java.util.concurrent.CompletableFuture.runAsync(() -> locks.remove(lockName));
            }
            
            @Override
            public java.util.concurrent.CompletableFuture<Void> refresh() {
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
        }
    }
}
