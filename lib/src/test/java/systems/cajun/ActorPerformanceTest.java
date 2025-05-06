package systems.cajun;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Performance tests for the Actor system.
 * These tests are tagged with "performance" and are not run during normal test execution.
 * To run these tests specifically, use: ./gradlew test --tests "systems.cajun.performance.*" or
 * ./gradlew test -PincludeTags="performance"
 */
@Tag("performance")
public class ActorPerformanceTest {

    private static final Logger logger = LoggerFactory.getLogger(ActorPerformanceTest.class);
    private ActorSystem actorSystem;

    @BeforeEach
    void setUp() {
        actorSystem = new ActorSystem();
    }

    @AfterEach
    void tearDown() {
        if (actorSystem != null) {
            actorSystem.shutdown();
        }
    }

    /**
     * Tests the throughput of message passing in a simple actor chain.
     * This test creates a chain of actors and measures how quickly messages can be
     * passed through the chain.
     */
    @Test
    void testActorChainThroughput() throws InterruptedException {
        final int CHAIN_LENGTH = 10;
        final int MESSAGE_COUNT = 10_000;
        
        // Create a completion latch to wait for all messages
        CountDownLatch completionLatch = new CountDownLatch(MESSAGE_COUNT);
        
        // Create the last actor in the chain that will count down the latch
        Pid lastActor = actorSystem.register(FinalActor.class, "final-actor");
        ((FinalActor)actorSystem.getActor(lastActor)).setCompletionLatch(completionLatch);
        
        // Create a chain of pass-through actors
        Pid previousActor = lastActor;
        Pid firstActor = null; // Will be set to the first actor in the chain
        
        for (int i = CHAIN_LENGTH - 1; i >= 0; i--) {
            Pid actor = actorSystem.register(PassThroughActor.class, "actor-" + i);
            Actor<?> actorRef = actorSystem.getActor(actor);
            if (actorRef instanceof ChainedActor) {
                ((ChainedActor<?>) actorRef).withNext(previousActor);
            }
            previousActor = actor;
            
            if (i == 0) {
                firstActor = actor;
            }
        }
        
        // Ensure firstActor is not null
        if (firstActor == null) {
            throw new IllegalStateException("Failed to create actor chain - first actor is null");
        }
        
        // Measure throughput
        long startTime = System.nanoTime();
        
        // Send messages to the first actor
        for (int i = 0; i < MESSAGE_COUNT; i++) {
            firstActor.tell(new PerformanceMessage(i));
        }
        
        // Wait for all messages to be processed
        boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
        long endTime = System.nanoTime();
        
        if (!completed) {
            logger.warn("Not all messages were processed within the timeout period");
        }
        
        double elapsedSeconds = (endTime - startTime) / 1_000_000_000.0;
        double messagesPerSecond = MESSAGE_COUNT / elapsedSeconds;
        
        logger.info("Actor Chain Performance Test Results:");
        logger.info("Chain Length: {}", CHAIN_LENGTH);
        logger.info("Messages Sent: {}", MESSAGE_COUNT);
        logger.info("Elapsed Time: {} seconds", elapsedSeconds);
        logger.info("Throughput: {} messages/second", messagesPerSecond);
    }
    
    /**
     * Tests the throughput of a many-to-one actor configuration.
     * This test creates many sender actors all sending messages to a single receiver actor.
     */
    @Test
    void testManyToOneThroughput() throws InterruptedException {
        final int SENDER_COUNT = 100;
        final int MESSAGES_PER_SENDER = 1_000;
        final int TOTAL_MESSAGES = SENDER_COUNT * MESSAGES_PER_SENDER;
        
        // Create a completion latch to wait for all messages
        CountDownLatch completionLatch = new CountDownLatch(TOTAL_MESSAGES);
        
        // Create the receiver actor
        Pid receiverActor = actorSystem.register(CountingActor.class, "receiver-actor");
        ((CountingActor)actorSystem.getActor(receiverActor)).setCompletionLatch(completionLatch);
        
        // Create sender actors
        List<Pid> senderActors = new ArrayList<>();
        for (int i = 0; i < SENDER_COUNT; i++) {
            Pid sender = actorSystem.register(SenderActor.class, "sender-" + i);
            Actor<?> senderRef = actorSystem.getActor(sender);
            ((SenderActor)senderRef).setTarget(receiverActor);
            senderActors.add(sender);
        }
        
        // Measure throughput
        long startTime = System.nanoTime();
        
        // Trigger all senders to send their messages
        for (int i = 0; i < SENDER_COUNT; i++) {
            senderActors.get(i).tell(new StartSendingCommand(MESSAGES_PER_SENDER));
        }
        
        // Wait for all messages to be processed
        boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
        long endTime = System.nanoTime();
        
        if (!completed) {
            logger.warn("Not all messages were processed within the timeout period");
        }
        
        double elapsedSeconds = (endTime - startTime) / 1_000_000_000.0;
        double messagesPerSecond = TOTAL_MESSAGES / elapsedSeconds;
        
        logger.info("Many-to-One Performance Test Results:");
        logger.info("Sender Actors: {}", SENDER_COUNT);
        logger.info("Messages per Sender: {}", MESSAGES_PER_SENDER);
        logger.info("Total Messages: {}", TOTAL_MESSAGES);
        logger.info("Elapsed Time: {} seconds", elapsedSeconds);
        logger.info("Throughput: {} messages/second", messagesPerSecond);
    }
    
    /**
     * Tests the performance of creating and stopping a large number of actors.
     */
    @Test
    void testActorLifecyclePerformance() {
        final int ACTOR_COUNT = 10_000;
        final int SHUTDOWN_TIMEOUT = 2; // Shorter timeout for performance tests
        
        // Measure actor creation time
        long startCreationTime = System.nanoTime();
        List<Pid> actors = new ArrayList<>();
        
        for (int i = 0; i < ACTOR_COUNT; i++) {
            Pid actor = actorSystem.register(EmptyActor.class, "empty-actor-" + i);
            // Configure each actor with a shorter shutdown timeout
            ((EmptyActor)actorSystem.getActor(actor)).withShutdownTimeout(SHUTDOWN_TIMEOUT);
            actors.add(actor);
        }
        
        long endCreationTime = System.nanoTime();
        double creationTimeSeconds = (endCreationTime - startCreationTime) / 1_000_000_000.0;
        
        // Shutdown in smaller batches to avoid overwhelming the system
        long startShutdownTime = System.nanoTime();
        
        // Shutdown in batches of 1000 actors
        final int BATCH_SIZE = 1000;
        for (int i = 0; i < actors.size(); i += BATCH_SIZE) {
            int endIndex = Math.min(i + BATCH_SIZE, actors.size());
            List<Pid> batch = actors.subList(i, endIndex);
            
            // Stop each actor in the batch
            for (Pid pid : batch) {
                Actor<?> actor = actorSystem.getActor(pid);
                if (actor != null && actor.isRunning()) {
                    actor.stop();
                }
            }
            
            // Small pause between batches to allow for cleanup
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        long endShutdownTime = System.nanoTime();
        double shutdownTimeSeconds = (endShutdownTime - startShutdownTime) / 1_000_000_000.0;
        
        logger.info("Actor Lifecycle Performance Test Results:");
        logger.info("Actor Count: {}", ACTOR_COUNT);
        logger.info("Creation Time: {} seconds", creationTimeSeconds);
        logger.info("Creation Rate: {} actors/second", ACTOR_COUNT / creationTimeSeconds);
        logger.info("Shutdown Time: {} seconds", shutdownTimeSeconds);
        logger.info("Shutdown Rate: {} actors/second", ACTOR_COUNT / shutdownTimeSeconds);
    }
}

/**
 * Message class for performance testing.
 */
class PerformanceMessage {
    private final int id;
    
    public PerformanceMessage(int id) {
        this.id = id;
    }
    
    public int getId() {
        return id;
    }
}

/**
 * Command to start sending messages.
 */
class StartSendingCommand {
    private final int count;
    
    public StartSendingCommand(int count) {
        this.count = count;
    }
    
    public int getCount() {
        return count;
    }
}

/**
 * A simple actor that just passes messages to the next actor in the chain.
 */
class PassThroughActor extends ChainedActor<PerformanceMessage> {
    
    public PassThroughActor(ActorSystem system, String actorId) {
        super(system, actorId);
    }
    
    @Override
    protected void receive(PerformanceMessage message) {
        forward(message);
    }
}

/**
 * The final actor in a chain that counts down a latch when it receives messages.
 */
class FinalActor extends Actor<PerformanceMessage> {
    private CountDownLatch completionLatch;
    private final AtomicInteger messageCount = new AtomicInteger(0);
    
    public FinalActor(ActorSystem system, String actorId) {
        super(system, actorId);
    }
    
    public void setCompletionLatch(CountDownLatch latch) {
        this.completionLatch = latch;
    }
    
    @Override
    protected void receive(PerformanceMessage message) {
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
 * An actor that sends a specified number of messages to a target actor.
 */
class SenderActor extends Actor<StartSendingCommand> {
    private Pid target;
    
    public SenderActor(ActorSystem system, String actorId) {
        super(system, actorId);
    }
    
    public void setTarget(Pid target) {
        this.target = target;
    }
    
    @Override
    protected void receive(StartSendingCommand command) {
        for (int i = 0; i < command.getCount(); i++) {
            target.tell(new PerformanceMessage(i));
        }
    }
}

/**
 * An actor that counts messages and counts down a latch.
 */
class CountingActor extends Actor<PerformanceMessage> {
    private CountDownLatch completionLatch;
    private final AtomicInteger messageCount = new AtomicInteger(0);
    
    public CountingActor(ActorSystem system, String actorId) {
        super(system, actorId);
    }
    
    public void setCompletionLatch(CountDownLatch latch) {
        this.completionLatch = latch;
    }
    
    @Override
    protected void receive(PerformanceMessage message) {
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
 * An empty actor that does nothing - used for lifecycle testing.
 */
class EmptyActor extends Actor<Object> {
    
    public EmptyActor(ActorSystem system, String actorId) {
        super(system, actorId);
    }
    
    @Override
    protected void receive(Object message) {
        // Do nothing
    }
}
