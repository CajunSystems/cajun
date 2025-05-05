package systems.cajun.cluster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import systems.cajun.Actor;
import systems.cajun.ActorSystem;
import systems.cajun.Pid;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Performance tests for the Cluster Actor system.
 * These tests measure the performance improvement of direct communication
 * when actors are on the same node.
 */
@Tag("performance")
public class ClusterPerformanceTest {

    private static final Logger logger = LoggerFactory.getLogger(ClusterPerformanceTest.class);
    private ClusterActorSystem system;
    private InMemoryMetadataStore metadataStore;
    private InMemoryMessagingSystem messagingSystem;

    @BeforeEach
    void setUp() throws Exception {
        // Create a metadata store and messaging system
        metadataStore = new InMemoryMetadataStore();
        messagingSystem = new InMemoryMessagingSystem("system1");

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
    void tearDown() throws Exception {
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
     * Tests the throughput of message passing between actors on the same node.
     * This test creates a sender and receiver actor on the same node and measures
     * how quickly messages can be passed between them.
     */
    @Test
    void testSameNodeMessageThroughput() throws Exception {
        final int MESSAGE_COUNT = 10_000;

        // Create a completion latch to wait for all messages
        CountDownLatch completionLatch = new CountDownLatch(MESSAGE_COUNT);

        // Create the receiver actor
        Pid receiverPid = system.register(ReceiverActor.class, "receiver-actor");
        ReceiverActor receiver = (ReceiverActor) system.getActor(receiverPid);
        receiver.setCompletionLatch(completionLatch);

        // Create the sender actor
        Pid senderPid = system.register(SenderActor.class, "sender-actor");
        SenderActor sender = (SenderActor) system.getActor(senderPid);
        sender.setTarget(receiverPid);

        // Wait for actor assignments to propagate
        Thread.sleep(1000);

        // Measure throughput
        long startTime = System.nanoTime();

        // Send messages
        sender.sendMessages(MESSAGE_COUNT);

        // Wait for all messages to be processed
        boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
        long endTime = System.nanoTime();

        if (!completed) {
            logger.warn("Not all messages were processed within the timeout period");
        }

        double elapsedSeconds = (endTime - startTime) / 1_000_000_000.0;
        double messagesPerSecond = MESSAGE_COUNT / elapsedSeconds;

        logger.info("Same-Node Message Throughput Test Results:");
        logger.info("Messages Sent: {}", MESSAGE_COUNT);
        logger.info("Elapsed Time: {} seconds", elapsedSeconds);
        logger.info("Throughput: {} messages/second", messagesPerSecond);

        // Print debug logs for test output
        System.out.println("[DEBUG_LOG] Same-Node Message Throughput Test Results:");
        System.out.println("[DEBUG_LOG] Messages Sent: " + MESSAGE_COUNT);
        System.out.println("[DEBUG_LOG] Elapsed Time: " + elapsedSeconds + " seconds");
        System.out.println("[DEBUG_LOG] Throughput: " + messagesPerSecond + " messages/second");
    }

    /**
     * A simple actor that sends messages to a target actor.
     */
    public static class SenderActor extends Actor<Object> {
        private Pid target;

        public SenderActor(ActorSystem system, String actorId) {
            super(system, actorId);
        }

        public void setTarget(Pid target) {
            this.target = target;
        }

        public void sendMessages(int count) {
            for (int i = 0; i < count; i++) {
                target.tell("Message " + i);
            }
        }

        @Override
        protected void receive(Object message) {
            // Not used in this test
        }
    }

    /**
     * A simple actor that receives messages and counts down a latch.
     */
    public static class ReceiverActor extends Actor<Object> {
        private CountDownLatch completionLatch;
        private final AtomicInteger messageCount = new AtomicInteger(0);

        public ReceiverActor(ActorSystem system, String actorId) {
            super(system, actorId);
        }

        public void setCompletionLatch(CountDownLatch latch) {
            this.completionLatch = latch;
        }

        @Override
        protected void receive(Object message) {
            messageCount.incrementAndGet();
            if (completionLatch != null) {
                completionLatch.countDown();
            }
        }

        public int getMessageCount() {
            return messageCount.get();
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
            return java.util.concurrent.CompletableFuture.runAsync(() -> {
                // This is a simplified implementation - in a real system we'd use the watchId
            });
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

    /**
     * An in-memory implementation of the MessagingSystem interface for testing.
     */
    private static class InMemoryMessagingSystem implements MessagingSystem {
        private final String systemId;
        private final java.util.concurrent.ConcurrentHashMap<String, InMemoryMessagingSystem> connectedSystems = new java.util.concurrent.ConcurrentHashMap<>();
        private MessageHandler messageHandler;
        private boolean running = false;

        public InMemoryMessagingSystem(String systemId) {
            this.systemId = systemId;
        }

        public void connectTo(InMemoryMessagingSystem other) {
            this.connectedSystems.put(other.systemId, other);
            other.connectedSystems.put(this.systemId, this);
        }

        @Override
        public <Message> java.util.concurrent.CompletableFuture<Void> sendMessage(String targetSystemId, String actorId, Message message) {
            return java.util.concurrent.CompletableFuture.runAsync(() -> {
                InMemoryMessagingSystem targetSystem = connectedSystems.get(targetSystemId);
                if (targetSystem != null && targetSystem.messageHandler != null && targetSystem.running) {
                    targetSystem.messageHandler.onMessage(actorId, message);
                }
            });
        }

        @Override
        public java.util.concurrent.CompletableFuture<Void> registerMessageHandler(MessageHandler handler) {
            return java.util.concurrent.CompletableFuture.runAsync(() -> this.messageHandler = handler);
        }

        @Override
        public java.util.concurrent.CompletableFuture<Void> start() {
            return java.util.concurrent.CompletableFuture.runAsync(() -> {
                this.running = true;
            });
        }

        @Override
        public java.util.concurrent.CompletableFuture<Void> stop() {
            return java.util.concurrent.CompletableFuture.runAsync(() -> {
                this.running = false;
            });
        }
    }
}
