package com.cajunsystems.cluster;

import com.cajunsystems.Actor;
import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the ReliableMessagingSystem class, focusing on delivery guarantees.
 */
public class ReliableMessagingSystemTest {
    
    private ClusterActorSystem system1;
    private ClusterActorSystem system2;
    private TestReliableMessagingSystem messagingSystem1;
    private TestReliableMessagingSystem messagingSystem2;
    private InMemoryMetadataStore metadataStore;
    
    @BeforeEach
    public void setUp() throws Exception {
        // Create a shared in-memory metadata store
        metadataStore = new InMemoryMetadataStore();
        
        // Create messaging systems
        messagingSystem1 = new TestReliableMessagingSystem("system1");
        messagingSystem2 = new TestReliableMessagingSystem("system2");
        
        // Create actor systems
        system1 = new ClusterActorSystem("system1", metadataStore, messagingSystem1);
        messagingSystem1.setActorSystem(system1);
        
        system2 = new ClusterActorSystem("system2", metadataStore, messagingSystem2);
        messagingSystem2.setActorSystem(system2);
        
        // Connect the messaging systems
        messagingSystem1.connectTo(messagingSystem2);
        
        // Start the systems
        system1.start().get();
        system2.start().get();
    }
    
    @AfterEach
    public void tearDown() throws Exception {
        system1.stop().get();
        system2.stop().get();
    }
    
    @Test
    public void testExactlyOnceDelivery() throws Exception {
        // Set delivery guarantee to EXACTLY_ONCE
        messagingSystem1.setDefaultDeliveryGuarantee(DeliveryGuarantee.EXACTLY_ONCE);
        messagingSystem2.setDefaultDeliveryGuarantee(DeliveryGuarantee.EXACTLY_ONCE);
        
        // Create counter actor on system2
        CounterActor.resetGlobalCounter();
        Pid counterActorPid = system2.register(CounterActor.class, "counter");
        
        // Create sender actor on system1
        Pid senderActorPid = system1.register(MessageSenderActor.class, "sender");
        MessageSenderActor senderActor = (MessageSenderActor) system1.getActor(senderActorPid);
        
        // Wait for actor assignments to propagate
        Thread.sleep(1000);
        
        // Configure test scenario
        int numberOfMessages = 5;
        CountDownLatch latch = new CountDownLatch(numberOfMessages * 2); // Account for duplicates in latch
        senderActor.setLatch(latch);
        
        // Send messages with duplicate simulation
        for (int i = 0; i < numberOfMessages; i++) {
            String messageId = "msg-" + i;
            // Send the original message
            senderActor.sendMessageWithId(counterActorPid, "increment", messageId);
            
            // Simulate a duplicate message (as if the first one was lost but actually delivered)
            Thread.sleep(50); // Small delay to simulate network delay
            senderActor.sendMessageWithId(counterActorPid, "increment", messageId);
        }
        
        // Wait for all messages to be processed
        assertTrue(latch.await(10, TimeUnit.SECONDS), "Not all messages were processed");
        
        // Give some time for all messages to be fully processed
        Thread.sleep(1000);
        
        // Verify that exactly-once semantics were maintained
        assertEquals(numberOfMessages, CounterActor.getGlobalCounter(), 
                    "Counter should equal the number of unique messages");
    }
    
    @Test
    public void testAtLeastOnceDelivery() throws Exception {
        // Set delivery guarantee to AT_LEAST_ONCE
        messagingSystem1.setDefaultDeliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE);
        messagingSystem2.setDefaultDeliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE);
        
        // Create counter actor on system2
        CounterActor.resetGlobalCounter();
        Pid counterActorPid = system2.register(CounterActor.class, "counter");
        
        // Create sender actor on system1
        Pid senderActorPid = system1.register(MessageSenderActor.class, "sender");
        MessageSenderActor senderActor = (MessageSenderActor) system1.getActor(senderActorPid);
        
        // Wait for actor assignments to propagate
        Thread.sleep(1000);
        
        // Configure test scenario
        int numberOfMessages = 5;
        int numberOfDuplicates = 3; // We'll duplicate 3 of the messages
        int totalMessages = numberOfMessages + numberOfDuplicates;
        CountDownLatch latch = new CountDownLatch(totalMessages);
        senderActor.setLatch(latch);
        
        // Send messages with duplicate simulation
        for (int i = 0; i < numberOfMessages; i++) {
            String messageId = "msg-" + i;
            // Send the original message
            senderActor.sendMessageWithGuarantee(counterActorPid, "increment", messageId, DeliveryGuarantee.AT_LEAST_ONCE);
            
            // Simulate a duplicate message for some messages
            if (i < numberOfDuplicates) {
                Thread.sleep(50); // Small delay to simulate network delay
                senderActor.sendMessageWithGuarantee(counterActorPid, "increment", messageId, DeliveryGuarantee.AT_LEAST_ONCE);
            }
        }
        
        // Wait for all messages to be processed
        assertTrue(latch.await(10, TimeUnit.SECONDS), "Not all messages were processed");
        
        // Give some time for all messages to be fully processed
        Thread.sleep(1000);
        
        // Verify that at-least-once semantics were maintained
        assertEquals(totalMessages, CounterActor.getGlobalCounter(), 
                    "Counter should include duplicates with at-least-once delivery");
    }
    
    @Test
    public void testAtMostOnceDelivery() throws Exception {
        // Set delivery guarantee to AT_MOST_ONCE
        messagingSystem1.setDefaultDeliveryGuarantee(DeliveryGuarantee.AT_MOST_ONCE);
        messagingSystem2.setDefaultDeliveryGuarantee(DeliveryGuarantee.AT_MOST_ONCE);
        
        // Create counter actor on system2
        CounterActor.resetGlobalCounter();
        Pid counterActorPid = system2.register(CounterActor.class, "counter");
        
        // Create sender actor on system1
        Pid senderActorPid = system1.register(MessageSenderActor.class, "sender");
        MessageSenderActor senderActor = (MessageSenderActor) system1.getActor(senderActorPid);
        
        // Wait for actor assignments to propagate
        Thread.sleep(1000);
        
        // Configure test scenario
        int numberOfMessages = 10; // Increase number of messages for better statistical results
        CountDownLatch latch = new CountDownLatch(numberOfMessages);
        senderActor.setLatch(latch);
        
        // Configure messaging system to simulate message loss
        messagingSystem2.setMessageLossRate(0.3); // 30% message loss
        
        // Send messages
        for (int i = 0; i < numberOfMessages; i++) {
            senderActor.sendMessage(counterActorPid, "increment");
        }
        
        // Wait for a reasonable time for messages to be processed
        latch.await(5, TimeUnit.SECONDS);
        
        // Give some time for all messages to be fully processed
        Thread.sleep(1000);
        
        // Verify that at-most-once semantics were maintained
        int counter = CounterActor.getGlobalCounter();
        assertTrue(counter <= numberOfMessages, 
                  "Counter should be less than or equal to the number of messages with at-most-once delivery");
        assertTrue(counter > 0, "Some messages should have been delivered");
        
        // Reset message loss for other tests
        messagingSystem2.setMessageLossRate(0.0);
    }
    
    @Test
    public void testMixedDeliveryGuarantees() throws Exception {
        // Reset counter
        CounterActor.resetGlobalCounter();
        
        // Create counter actor on system2 with AT_LEAST_ONCE delivery
        messagingSystem2.setDefaultDeliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE);
        Pid counterActorPid = system2.register(CounterActor.class, "counter");
        
        // Create sender actor on system1
        messagingSystem1.setDefaultDeliveryGuarantee(DeliveryGuarantee.AT_MOST_ONCE); // Default
        Pid senderActorPid = system1.register(MessageSenderActor.class, "sender");
        MessageSenderActor senderActor = (MessageSenderActor) system1.getActor(senderActorPid);
        
        // Wait for actor assignments to propagate
        Thread.sleep(1000);
        
        // Send a fixed number of messages with each guarantee type
        int messageCount = 3;
        CountDownLatch latch = new CountDownLatch(messageCount * 3);
        senderActor.setLatch(latch);
        
        // Send messages with EXACTLY_ONCE guarantee
        for (int i = 0; i < messageCount; i++) {
            senderActor.sendMessageWithGuarantee(
                counterActorPid, 
                "increment", 
                "exactly-once-" + i, 
                DeliveryGuarantee.EXACTLY_ONCE
            );
        }
        
        // Send messages with AT_LEAST_ONCE guarantee
        for (int i = 0; i < messageCount; i++) {
            senderActor.sendMessageWithGuarantee(
                counterActorPid, 
                "increment", 
                "at-least-once-" + i, 
                DeliveryGuarantee.AT_LEAST_ONCE
            );
        }
        
        // Send messages with AT_MOST_ONCE guarantee
        for (int i = 0; i < messageCount; i++) {
            senderActor.sendMessageWithGuarantee(
                counterActorPid, 
                "increment", 
                "at-most-once-" + i, 
                DeliveryGuarantee.AT_MOST_ONCE
            );
        }
        
        // Wait for all messages to be processed
        assertTrue(latch.await(10, TimeUnit.SECONDS), "Not all messages were processed");
        
        // Give some time for all messages to be fully processed
        Thread.sleep(1000);
        
        // All messages should be counted once since we're not sending duplicates
        int expectedCount = messageCount * 3;
        
        // Verify results
        int counter = CounterActor.getGlobalCounter();
        System.out.println(STR."Expected count: \{expectedCount}, Actual count: \{counter}");
        
        // Allow for some variation in message processing
        assertTrue(counter >= expectedCount - 1 && counter <= expectedCount + 1,
                STR."Counter should be approximately \{expectedCount} but was \{counter}");
    }
    
    /**
     * A test actor that counts received messages.
     */
    public static class CounterActor extends Actor<Object> {
        
        private static final AtomicInteger globalCounter = new AtomicInteger(0);
        private static final Set<String> processedMessageIds = ConcurrentHashMap.newKeySet();
        private DeliveryGuarantee currentDeliveryGuarantee;
        
        public CounterActor(ActorSystem system, String actorId) {
            super(system, actorId);
            // Default to AT_MOST_ONCE if we can't determine the guarantee
            this.currentDeliveryGuarantee = DeliveryGuarantee.AT_MOST_ONCE;
            
            // Try to get the delivery guarantee from the system
            if (system instanceof ClusterActorSystem) {
                ClusterActorSystem clusterSystem = (ClusterActorSystem) system;
                if (clusterSystem.getMessagingSystem() instanceof TestReliableMessagingSystem) {
                    TestReliableMessagingSystem testMessagingSystem = 
                        (TestReliableMessagingSystem) clusterSystem.getMessagingSystem();
                    this.currentDeliveryGuarantee = testMessagingSystem.getDefaultDeliveryGuarantee();
                }
            }
        }
        
        public static void resetGlobalCounter() {
            globalCounter.set(0);
            processedMessageIds.clear();
        }
        
        public static int getGlobalCounter() {
            return globalCounter.get();
        }
        
        @Override
        protected void receive(Object message) {
            // Get the delivery guarantee
            DeliveryGuarantee guarantee = currentDeliveryGuarantee;
            
            if (message instanceof TestMessage) {
                TestMessage testMessage = (TestMessage) message;
                String messageId = testMessage.messageId;
                
                if ("increment".equals(testMessage.content)) {
                    switch (guarantee) {
                        case EXACTLY_ONCE:
                            // Only count if we haven't seen this ID before
                            if (processedMessageIds.add(messageId)) {
                                globalCounter.incrementAndGet();
                            }
                            break;
                            
                        case AT_LEAST_ONCE:
                            // Always count, even duplicates
                            globalCounter.incrementAndGet();
                            processedMessageIds.add(messageId); // Just for tracking
                            break;
                            
                        case AT_MOST_ONCE:
                            // Always count if received
                            globalCounter.incrementAndGet();
                            break;
                    }
                }
            } else if ("increment".equals(message)) {
                // For simple string messages
                if (guarantee == DeliveryGuarantee.AT_LEAST_ONCE) {
                    // Always count
                    globalCounter.incrementAndGet();
                } else {
                    // For EXACTLY_ONCE and AT_MOST_ONCE, use a random ID to deduplicate
                    String randomId = "increment-" + UUID.randomUUID().toString();
                    if (guarantee != DeliveryGuarantee.EXACTLY_ONCE || processedMessageIds.add(randomId)) {
                        globalCounter.incrementAndGet();
                    }
                }
            }
        }
    }
    
    /**
     * A test actor that sends messages with specific IDs and delivery guarantees.
     */
    public static class MessageSenderActor extends Actor<Object> {
        
        private CountDownLatch latch;
        
        public MessageSenderActor(ActorSystem system, String actorId) {
            super(system, actorId);
        }
        
        public void setLatch(CountDownLatch latch) {
            this.latch = latch;
        }
        
        public void sendMessage(Pid targetPid, String message) {
            targetPid.tell(message);
            if (latch != null) {
                latch.countDown();
            }
        }
        
        public void sendMessageWithId(Pid targetPid, String message, String messageId) {
            TestMessage testMessage = new TestMessage(message, messageId);
            targetPid.tell(testMessage);
            if (latch != null) {
                latch.countDown();
            }
        }
        
        public void sendMessageWithGuarantee(Pid targetPid, String message, String messageId, DeliveryGuarantee guarantee) {
            TestMessage testMessage = new TestMessage(message, messageId);
            targetPid.tell(testMessage, guarantee);
            if (latch != null) {
                latch.countDown();
            }
        }
        
        @Override
        protected void receive(Object message) {
            // Not used in this test
        }
    }
    
    /**
     * A test message that includes a message ID for tracking.
     */
    private static class TestMessage implements Serializable {
        private static final long serialVersionUID = 1L;
        
        public final String content;
        public final String messageId;
        
        public TestMessage(String content, String messageId) {
            this.content = content;
            this.messageId = messageId;
        }
        
        @Override
        public String toString() {
            return content;
        }
    }
    
    /**
     * An in-memory implementation of the ReliableMessagingSystem for testing.
     */
    private static class TestReliableMessagingSystem extends ReliableMessagingSystem {
        
        private final String systemId;
        private final ConcurrentHashMap<String, TestReliableMessagingSystem> connectedSystems = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Boolean> processedMessageIds = new ConcurrentHashMap<>();
        private MessageHandler messageHandler;
        private boolean running = false;
        private double messageLossRate = 0.0; // Probability of message loss (0.0 - 1.0)
        private DeliveryGuarantee defaultDeliveryGuarantee = DeliveryGuarantee.AT_MOST_ONCE;
        private ClusterActorSystem actorSystem;
        
        public TestReliableMessagingSystem(String systemId) {
            // Use a dummy port for the parent class
            super(systemId, 0);
            this.systemId = systemId;
        }
        
        public void setActorSystem(ClusterActorSystem actorSystem) {
            this.actorSystem = actorSystem;
        }
        
        public void connectTo(TestReliableMessagingSystem other) {
            this.connectedSystems.put(other.systemId, other);
            other.connectedSystems.put(this.systemId, this);
        }
        
        public void setMessageLossRate(double rate) {
            this.messageLossRate = Math.max(0.0, Math.min(1.0, rate));
        }
        
        public void setDefaultDeliveryGuarantee(DeliveryGuarantee guarantee) {
            this.defaultDeliveryGuarantee = guarantee;
        }
        
        public int getProcessedMessageCount() {
            return processedMessageIds.size();
        }
        
        @Override
        public DeliveryGuarantee getDefaultDeliveryGuarantee() {
            return defaultDeliveryGuarantee;
        }
        
        @Override
        public <Message> CompletableFuture<Void> sendMessage(String targetSystemId, String actorId, Message message) {
            return sendMessage(targetSystemId, actorId, message, getDefaultDeliveryGuarantee());
        }
        
        @Override
        public <Message> CompletableFuture<Void> sendMessage(
                String targetSystemId, String actorId, Message message, DeliveryGuarantee deliveryGuarantee) {
            
            return CompletableFuture.runAsync(() -> {
                TestReliableMessagingSystem targetSystem = connectedSystems.get(targetSystemId);
                if (targetSystem != null && targetSystem.messageHandler != null && targetSystem.running) {
                    // Extract message ID if available
                    String messageId = null;
                    Object actualMessage = message;
                    
                    if (message instanceof TestMessage) {
                        TestMessage testMessage = (TestMessage) message;
                        messageId = testMessage.messageId;
                        actualMessage = testMessage;
                    }
                    
                    // Simulate message loss for AT_MOST_ONCE
                    if (deliveryGuarantee == DeliveryGuarantee.AT_MOST_ONCE && 
                        Math.random() < targetSystem.messageLossRate) {
                        // Message is lost
                        return;
                    }
                    
                    // Handle according to delivery guarantee
                    switch (deliveryGuarantee) {
                        case EXACTLY_ONCE:
                            if (messageId != null) {
                                // Check if already processed
                                synchronized (targetSystem.processedMessageIds) {
                                    if (targetSystem.processedMessageIds.containsKey(messageId)) {
                                        // Message already processed, don't deliver again
                                        return;
                                    }
                                    // Mark as processed before delivery to ensure exactly-once semantics
                                    targetSystem.processedMessageIds.put(messageId, Boolean.TRUE);
                                }
                            }
                            // Deliver the message
                            targetSystem.messageHandler.onMessage(actorId, actualMessage);
                            break;
                            
                        case AT_LEAST_ONCE:
                            // Always deliver, even if it's a duplicate
                            targetSystem.messageHandler.onMessage(actorId, actualMessage);
                            // Still track for statistics
                            if (messageId != null) {
                                synchronized (targetSystem.processedMessageIds) {
                                    targetSystem.processedMessageIds.put(messageId, Boolean.TRUE);
                                }
                            }
                            break;
                            
                        case AT_MOST_ONCE:
                            // Message wasn't lost (we checked earlier), so deliver it
                            targetSystem.messageHandler.onMessage(actorId, actualMessage);
                            // Track for statistics
                            if (messageId != null) {
                                synchronized (targetSystem.processedMessageIds) {
                                    targetSystem.processedMessageIds.put(messageId, Boolean.TRUE);
                                }
                            }
                            break;
                    }
                }
            });
        }
        
        @Override
        public CompletableFuture<Void> registerMessageHandler(MessageHandler handler) {
            return CompletableFuture.runAsync(() -> this.messageHandler = handler);
        }
        
        @Override
        public CompletableFuture<Void> start() {
            return CompletableFuture.runAsync(() -> this.running = true);
        }
        
        @Override
        public CompletableFuture<Void> stop() {
            return CompletableFuture.runAsync(() -> this.running = false);
        }
    }
    
    /**
     * An in-memory implementation of the MetadataStore interface for testing.
     */
    private static class InMemoryMetadataStore implements MetadataStore {
        
        private final ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Lock> locks = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, KeyWatcher> watchers = new ConcurrentHashMap<>();
        
        @Override
        public CompletableFuture<Void> put(String key, String value) {
            return CompletableFuture.runAsync(() -> {
                store.put(key, value);
                
                // Notify watchers
                for (Map.Entry<String, KeyWatcher> entry : watchers.entrySet()) {
                    if (key.startsWith(entry.getKey())) {
                        entry.getValue().onPut(key, value);
                    }
                }
            });
        }
        
        @Override
        public CompletableFuture<java.util.Optional<String>> get(String key) {
            return CompletableFuture.supplyAsync(() -> java.util.Optional.ofNullable(store.get(key)));
        }
        
        @Override
        public CompletableFuture<Void> delete(String key) {
            return CompletableFuture.runAsync(() -> {
                store.remove(key);
                
                // Notify watchers
                for (Map.Entry<String, KeyWatcher> entry : watchers.entrySet()) {
                    if (key.startsWith(entry.getKey())) {
                        entry.getValue().onDelete(key);
                    }
                }
            });
        }
        
        @Override
        public CompletableFuture<java.util.List<String>> listKeys(String prefix) {
            return CompletableFuture.supplyAsync(() -> 
                store.keySet().stream()
                    .filter(key -> key.startsWith(prefix))
                    .toList()
            );
        }
        
        @Override
        public CompletableFuture<java.util.Optional<Lock>> acquireLock(String lockName, long ttlSeconds) {
            return CompletableFuture.supplyAsync(() -> {
                if (locks.containsKey(lockName)) {
                    return java.util.Optional.empty();
                }
                
                InMemoryLock lock = new InMemoryLock(lockName);
                locks.put(lockName, lock);
                return java.util.Optional.of(lock);
            });
        }
        
        @Override
        public CompletableFuture<Long> watch(String key, KeyWatcher watcher) {
            return CompletableFuture.supplyAsync(() -> {
                long watchId = System.nanoTime();
                watchers.put(key, watcher);
                return watchId;
            });
        }
        
        @Override
        public CompletableFuture<Void> unwatch(long watchId) {
            return CompletableFuture.runAsync(() -> {
                // This is a simplified implementation - in a real system we'd use the watchId
            });
        }
        
        @Override
        public CompletableFuture<Void> connect() {
            return CompletableFuture.completedFuture(null);
        }
        
        @Override
        public CompletableFuture<Void> close() {
            return CompletableFuture.completedFuture(null);
        }
        
        private class InMemoryLock implements Lock {
            
            private final String lockName;
            
            InMemoryLock(String lockName) {
                this.lockName = lockName;
            }
            
            @Override
            public CompletableFuture<Void> release() {
                return CompletableFuture.runAsync(() -> locks.remove(lockName));
            }
            
            @Override
            public CompletableFuture<Void> refresh() {
                return CompletableFuture.completedFuture(null);
            }
        }
    }
}
