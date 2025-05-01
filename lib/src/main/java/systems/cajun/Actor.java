package systems.cajun;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentHashMap;

public abstract class Actor<Message> {

    private static final Logger logger = LoggerFactory.getLogger(Actor.class);
    private static final int DEFAULT_SHUTDOWN_TIMEOUT_SECONDS = 10;
    private static final int DEFAULT_BATCH_SIZE = 10; // Default number of messages to process in a batch

    private final BlockingQueue<Message> mailbox;
    private volatile boolean isRunning;
    private final String actorId;
    private final ActorSystem system;
    private final Pid pid;
    private final ExecutorService executor;
    private SupervisionStrategy supervisionStrategy = SupervisionStrategy.RESUME;
    private Actor<?> parent;
    private final Map<String, Actor<?>> children = new ConcurrentHashMap<>();
    private int shutdownTimeoutSeconds = DEFAULT_SHUTDOWN_TIMEOUT_SECONDS;
    private int batchSize = DEFAULT_BATCH_SIZE;
    private volatile Thread mailboxThread;

    // Reference to the next actor in a workflow chain
    private Pid nextActor;


    public Actor(ActorSystem system) {
        this(system, UUID.randomUUID().toString());
    }

    public Actor(ActorSystem system, String actorId) {
        this.system = system;
        this.actorId = actorId;
        this.mailbox = new LinkedBlockingQueue<>();
        // Use a shared executor from the actor system if available, otherwise create a dedicated one
        this.executor = system.getSharedExecutor() != null ? 
                       system.getSharedExecutor() : 
                       Executors.newVirtualThreadPerTaskExecutor();
        this.pid = new Pid(actorId, system);
        logger.debug("Actor {} created", actorId);
    }

    protected abstract void receive(Message message);

    /**
     * Called before the actor starts processing messages.
     * Override to perform initialization logic.
     */
    protected void preStart() {
        // Default implementation does nothing
    }

    /**
     * Called after the actor has stopped processing messages.
     * Override to perform cleanup logic.
     */
    protected void postStop() {
        // Default implementation does nothing
    }

    /**
     * Called when an exception occurs during message processing.
     * Override to provide custom error handling.
     * 
     * @param message The message that caused the exception
     * @param exception The exception that was thrown
     * @return true if the message should be reprocessed, false otherwise
     */
    protected boolean onError(Message message, Throwable exception) {
        // Default implementation logs the error and doesn't reprocess
        return false;
    }

    /**
     * Sets the batch size for message processing.
     * A larger batch size can improve throughput but may increase latency.
     * 
     * @param batchSize The number of messages to process in a batch
     * @return This actor instance for method chaining
     */
    public Actor<Message> withBatchSize(int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("Batch size must be at least 1");
        }
        this.batchSize = batchSize;
        return this;
    }

    public void start() {
        if (isRunning) {
            logger.debug("Actor {} is already running", actorId);
            return;
        }
        
        isRunning = true;
        logger.info("Starting actor {}", actorId);
        
        try {
            preStart();
            
            // Use a virtual thread directly for the mailbox processing
            // This avoids the overhead of StructuredTaskScope when not needed
            mailboxThread = Thread.ofVirtual()
                .name("actor-" + actorId)
                .start(() -> {
                    try {
                        processMailbox();
                    } catch (Exception e) {
                        if (!(e instanceof InterruptedException)) {
                            logger.error("Unexpected error in actor {}", actorId, e);
                        }
                    }
                });
        } catch (Exception e) {
            isRunning = false;
            logger.error("Error during actor {} startup", actorId, e);
            throw e;
        }
    }

    public Pid self() {
        return pid;
    }

    public String getActorId() {
        return actorId;
    }

    public void tell(Message message) {
        mailbox.offer(message);
    }

    /**
     * Sets the next actor in a workflow chain.
     * 
     * @param nextActor The PID of the next actor
     * @return This actor instance for method chaining
     */
    public Actor<Message> withNext(Pid nextActor) {
        this.nextActor = nextActor;
        return this;
    }

    /**
     * Forwards a message to the next actor in the workflow chain.
     * If no next actor is set, this method does nothing.
     * 
     * @param message The message to forward
     */
    protected void forward(Message message) {
        if (nextActor != null) {
            nextActor.tell(message);
        } else {
            logger.warn("Actor {} attempted to forward message but no next actor is set", actorId);
        }
    }

    protected void processMailbox() {
        List<Message> batch = new ArrayList<>(batchSize);
        
        while (isRunning) {
            try {
                // Get at least one message (blocking)
                Message firstMessage = mailbox.take();
                batch.add(firstMessage);
                
                // Try to drain more messages up to batch size (non-blocking)
                if (batchSize > 1) {
                    mailbox.drainTo(batch, batchSize - 1);
                }
                
                // Process the batch
                for (Message message : batch) {
                    if (!isRunning) break; // Check if we should stop processing
                    
                    try {
                        receive(message);
                    } catch (Exception e) {
                        logger.error("Actor {} error processing message: {}", actorId, message, e);
                        handleException(message, e);
                    }
                }
                
                // Clear the batch for reuse
                batch.clear();
                
            } catch (InterruptedException e) {
                // This is expected during shutdown, so use debug level
                logger.debug("Actor {} mailbox processing interrupted", actorId);
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    public boolean isRunning() {
        return isRunning;
    }

    /**
     * Sets the shutdown timeout for this actor.
     * 
     * @param timeoutSeconds The timeout in seconds to wait for actor termination
     * @return This actor instance for method chaining
     */
    public Actor<Message> withShutdownTimeout(int timeoutSeconds) {
        this.shutdownTimeoutSeconds = timeoutSeconds;
        return this;
    }

    public void stop() {
        if (!isRunning) {
            return;
        }
        logger.debug("Stopping actor {}", actorId);
        isRunning = false;

        // Stop all children first
        for (Actor<?> child : new ConcurrentHashMap<>(children).values()) {
            logger.debug("Stopping child actor {} of parent {}", child.getActorId(), actorId);
            child.stop();
        }

        // Drain the mailbox to prevent processing during shutdown
        mailbox.clear();
        
        // Interrupt the mailbox thread if it exists
        if (mailboxThread != null) {
            mailboxThread.interrupt();
            try {
                // Give the mailbox thread a short time to terminate
                mailboxThread.join(TimeUnit.SECONDS.toMillis(1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            mailboxThread = null;
        }

        // Only shut down the executor if it's not shared from the actor system
        if (system.getSharedExecutor() != executor) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(shutdownTimeoutSeconds, TimeUnit.SECONDS)) {
                    logger.warn("Actor {} executor did not terminate in time, forcing shutdown", actorId);
                    executor.shutdownNow();
                    // Give it one more chance with a short timeout
                    if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                        logger.error("Actor {} executor could not be terminated even after forced shutdown", actorId);
                    }
                }
            } catch (InterruptedException e) {
                // This is expected during mass shutdowns, so lower the log level
                logger.debug("Actor {} shutdown interrupted", actorId, e);
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        try {
            postStop();
        } catch (Exception e) {
            logger.error("Error during actor {} postStop", actorId, e);
        } finally {
            // Clear children map
            children.clear();

            // Remove from parent if exists
            if (parent != null) {
                parent.removeChild(actorId);
            }

            system.shutdown(actorId);
        }
    }

    /**
     * Sets the supervision strategy for this actor.
     * 
     * @param strategy The supervision strategy to use
     * @return This actor instance for method chaining
     */
    public Actor<Message> withSupervisionStrategy(SupervisionStrategy strategy) {
        this.supervisionStrategy = strategy;
        return this;
    }

    /**
     * Sets the parent of this actor.
     * 
     * @param parent The parent actor
     */
    void setParent(Actor<?> parent) {
        this.parent = parent;
    }

    /**
     * Returns the parent of this actor, or null if this actor has no parent.
     * 
     * @return The parent actor
     */
    public Actor<?> getParent() {
        return parent;
    }

    /**
     * Adds a child actor to this actor.
     * 
     * @param child The child actor to add
     */
    void addChild(Actor<?> child) {
        children.put(child.getActorId(), child);
        child.setParent(this);
    }

    /**
     * Removes a child actor from this actor.
     * 
     * @param childId The ID of the child actor to remove
     */
    void removeChild(String childId) {
        children.remove(childId);
    }

    /**
     * Returns an unmodifiable view of the children of this actor.
     * 
     * @return The children of this actor
     */
    public Map<String, Actor<?>> getChildren() {
        return Collections.unmodifiableMap(children);
    }

    /**
     * Creates and registers a child actor of the specified class.
     * 
     * @param <T> The type of the child actor
     * @param actorClass The class of the child actor
     * @param childId The ID for the child actor
     * @return The PID of the created child actor
     */
    public <T extends Actor<?>> Pid createChild(Class<T> actorClass, String childId) {
        return system.registerChild(actorClass, childId, this);
    }

    /**
     * Creates and registers a child actor of the specified class with an auto-generated ID.
     * 
     * @param <T> The type of the child actor
     * @param actorClass The class of the child actor
     * @return The PID of the created child actor
     */
    public <T extends Actor<?>> Pid createChild(Class<T> actorClass) {
        return system.registerChild(actorClass, this);
    }

    /**
     * Handles exceptions according to the current supervision strategy.
     * 
     * @param message The message that caused the exception
     * @param exception The exception that was thrown
     */
    protected void handleException(Message message, Throwable exception) {
        boolean shouldReprocess = onError(message, exception);

        switch (supervisionStrategy) {
            case RESUME -> {
                logger.debug("Actor {} resuming after error", actorId);
                // Continue processing next message
            }
            case RESTART -> {
                logger.info("Restarting actor {}", actorId);
                stop();
                start();
                if (shouldReprocess) {
                    tell(message); // Reprocess the failed message
                }
            }
            case STOP -> {
                logger.info("Stopping actor {} due to error", actorId);
                stop();
            }
            case ESCALATE -> {
                logger.info("Escalating error from actor {}", actorId);

                // Save parent reference before stopping
                Actor<?> parentRef = parent;

                // Stop before escalating to prevent race conditions
                stop();

                if (parentRef != null) {
                    // Propagate the error to the parent actor
                    parentRef.handleChildError(this, exception);
                } else {
                    // No parent, throw the exception to the system
                    throw new ActorException("Error in actor", exception, actorId);
                }
            }
        }
    }

    /**
     * Handles an error from a child actor.
     * 
     * @param child The child actor that experienced an error
     * @param exception The exception that was thrown
     */
    @SuppressWarnings("unchecked")
    void handleChildError(Actor<?> child, Throwable exception) {
        logger.info("Actor {} handling error from child {}", actorId, child.getActorId());

        // Always remove the child from our children map since it's already stopped
        // or will be stopped by the supervision strategy
        removeChild(child.getActorId());

        // Apply this actor's supervision strategy to the child error
        switch (supervisionStrategy) {
            case RESUME -> {
                logger.debug("Actor {} allowing child {} to resume after error", actorId, child.getActorId());
                // Child might already be stopped, we need to restart it
                if (!child.isRunning()) {
                    child.start();
                }
                // Re-add the child to our children map
                addChild(child);
            }
            case RESTART -> {
                logger.info("Actor {} restarting child {} after error", actorId, child.getActorId());
                // Make sure the child is stopped before restarting
                if (child.isRunning()) {
                    child.stop();
                }
                child.start();
                // Re-add the child to our children map
                addChild(child);
            }
            case STOP -> {
                logger.info("Actor {} confirming stop of child {} due to error", actorId, child.getActorId());
                // Make sure the child is stopped
                if (child.isRunning()) {
                    child.stop();
                }
                // Child is already removed from children map
            }
            case ESCALATE -> {
                logger.info("Actor {} escalating error from child {}", actorId, child.getActorId());

                // Make sure the child is stopped
                if (child.isRunning()) {
                    child.stop();
                }

                if (parent != null) {
                    // Continue escalating up the hierarchy
                    parent.handleChildError(this, exception);
                } else {
                    // No parent, throw the exception to the system
                    throw new ActorException("Error in child actor", exception, child.getActorId());
                }
            }
        }
    }

    /**
     * Supervision strategies for handling actor failures.
     */
    public enum SupervisionStrategy {
        /**
         * Resume processing the next message, ignoring the failure.
         */
        RESUME,

        /**
         * Restart the actor, then continue processing messages.
         */
        RESTART,

        /**
         * Stop the actor.
         */
        STOP,

        /**
         * Escalate the failure to the parent/system.
         */
        ESCALATE
    }
}
