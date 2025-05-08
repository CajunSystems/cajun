package systems.cajun.cluster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import systems.cajun.Actor;
import systems.cajun.ActorSystem;
import systems.cajun.Pid;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An extension of ActorSystem that supports clustering.
 * This implementation uses a metadata store for actor assignment and leader election,
 * and a messaging system for communication between actor systems.
 */
public class ClusterActorSystem extends ActorSystem {

    private static final Logger logger = LoggerFactory.getLogger(ClusterActorSystem.class);
    private static final String ACTOR_ASSIGNMENT_PREFIX = "cajun/actor/";
    private static final String NODE_PREFIX = "cajun/node/";
    private static final String LEADER_KEY = "cajun/leader";
    private static final long HEARTBEAT_INTERVAL_SECONDS = 5;

    private final String systemId;
    private final MetadataStore metadataStore;
    private final MessagingSystem messagingSystem;
    private final ScheduledExecutorService scheduler;
    private final Set<String> knownNodes = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean isLeader = new AtomicBoolean(false);
    private MetadataStore.Lock leaderLock;
    private DeliveryGuarantee defaultDeliveryGuarantee = DeliveryGuarantee.EXACTLY_ONCE;

    /**
     * Creates a new ClusterActorSystem with the specified configuration.
     *
     * @param systemId The unique ID for this actor system in the cluster
     * @param metadataStore The metadata store implementation
     * @param messagingSystem The messaging system implementation
     */
    public ClusterActorSystem(String systemId, MetadataStore metadataStore, MessagingSystem messagingSystem) {
        super(true); // Use shared executor
        this.systemId = systemId;
        this.metadataStore = metadataStore;
        this.messagingSystem = messagingSystem;
        this.scheduler = new ScheduledThreadPoolExecutor(1);

        // Set default delivery guarantee if messaging system supports it
        if (messagingSystem instanceof ReliableMessagingSystem) {
            ((ReliableMessagingSystem) messagingSystem).setDefaultDeliveryGuarantee(defaultDeliveryGuarantee);
        }

        // Register message handler for remote actor messages
        messagingSystem.registerMessageHandler(this::handleRemoteMessage);
    }

    /**
     * Creates a new ClusterActorSystem with a generated system ID.
     *
     * @param metadataStore The metadata store implementation
     * @param messagingSystem The messaging system implementation
     */
    public ClusterActorSystem(MetadataStore metadataStore, MessagingSystem messagingSystem) {
        this(UUID.randomUUID().toString(), metadataStore, messagingSystem);
    }

    /**
     * Starts the cluster actor system.
     * This connects to the metadata store, starts the messaging system,
     * and begins the leader election and heartbeat processes.
     *
     * @return A CompletableFuture that completes when the system is started
     */
    public CompletableFuture<Void> start() {
        return metadataStore.connect()
                .thenCompose(v -> messagingSystem.start())
                .thenCompose(v -> registerNode())
                .thenRun(() -> {
                    // Start heartbeat
                    scheduler.scheduleAtFixedRate(
                            this::sendHeartbeat,
                            0,
                            HEARTBEAT_INTERVAL_SECONDS,
                            TimeUnit.SECONDS
                    );

                    // Start leader election
                    scheduler.scheduleAtFixedRate(
                            this::tryBecomeLeader,
                            1,
                            HEARTBEAT_INTERVAL_SECONDS * 3,
                            TimeUnit.SECONDS
                    );

                    // Watch for node changes
                    metadataStore.watch(NODE_PREFIX, new MetadataStore.KeyWatcher() {
                        @Override
                        public void onPut(String key, String value) {
                            String nodeId = key.substring(NODE_PREFIX.length());
                            knownNodes.add(nodeId);
                            logger.info("Node joined: {}", nodeId);
                        }

                        @Override
                        public void onDelete(String key) {
                            String nodeId = key.substring(NODE_PREFIX.length());
                            knownNodes.remove(nodeId);
                            logger.info("Node left: {}", nodeId);

                            // If we're the leader, reassign actors from the departed node
                            if (isLeader.get()) {
                                reassignActorsFromNode(nodeId);
                            }
                        }
                    });
                });
    }

    /**
     * Stops the cluster actor system.
     * This unregisters the node, stops the messaging system, and disconnects from the metadata store.
     *
     * @return A CompletableFuture that completes when the system is stopped
     */
    public CompletableFuture<Void> stop() {
        // Stop the scheduler
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Release leader lock if we have it
        if (leaderLock != null) {
            leaderLock.release();
            leaderLock = null;
            isLeader.set(false);
        }

        // Unregister node
        return metadataStore.delete(NODE_PREFIX + systemId)
                .thenCompose(v -> messagingSystem.stop())
                .thenCompose(v -> metadataStore.close())
                .whenComplete((v, ex) -> {
                    // Call parent shutdown
                    super.shutdown();
                });
    }

    @Override
    public void shutdown() {
        // Use the cluster-aware stop method
        stop();
    }

    @Override
    public <T extends Actor<?>> Pid register(Class<T> actorClass, String actorId) {
        // First register the actor locally
        Pid pid = super.register(actorClass, actorId);

        // Then register the actor in the metadata store
        metadataStore.put(ACTOR_ASSIGNMENT_PREFIX + actorId, systemId)
                .exceptionally(ex -> {
                    logger.error("Failed to register actor {} in metadata store", actorId, ex);
                    return null;
                });

        return pid;
    }

    @Override
    public <T extends Actor<?>> Pid register(Class<T> actorClass) {
        // Use the parent implementation which will generate an actor ID
        Pid pid = super.register(actorClass);

        // Then register the actor in the metadata store
        String actorId = pid.actorId();
        metadataStore.put(ACTOR_ASSIGNMENT_PREFIX + actorId, systemId)
                .exceptionally(ex -> {
                    logger.error("Failed to register actor {} in metadata store", actorId, ex);
                    return null;
                });

        return pid;
    }

    @Override
    public void shutdown(String actorId) {
        // First remove the actor from the metadata store
        metadataStore.delete(ACTOR_ASSIGNMENT_PREFIX + actorId)
                .exceptionally(ex -> {
                    logger.error("Failed to unregister actor {} from metadata store", actorId, ex);
                    return null;
                });

        // Then shut down the actor locally
        super.shutdown(actorId);
    }

    /**
     * Routes a message to an actor, either locally or remotely.
     * This method is called by Pid.tell().
     * Note: This method has the same signature as the one in ActorSystem but is not an override
     * because the method in ActorSystem is package-private.
     *
     * @param actorId The ID of the target actor
     * @param message The message to send
     * @param <Message> The type of the message
     */
    @SuppressWarnings({"unchecked", "RedundantSuppression"})
    public <Message> void routeMessage(String actorId, Message message) {
        routeMessage(actorId, message, defaultDeliveryGuarantee);
    }

    /**
     * Routes a message to an actor with a specific delivery guarantee.
     *
     * @param actorId The ID of the target actor
     * @param message The message to send
     * @param deliveryGuarantee The delivery guarantees to use
     * @param <Message> The type of the message
     */
    @SuppressWarnings({"unchecked", "RedundantSuppression"})
    public <Message> void routeMessage(String actorId, Message message, DeliveryGuarantee deliveryGuarantee) {
        // Check if the actor is local
        Actor<Message> actor = (Actor<Message>) getActor(new Pid(actorId, this));
        if (actor != null) {
            // Actor is local, deliver the message directly
            actor.tell(message);
            return;
        }

        // Actor is not local, look up its location in the metadata store
        metadataStore.get(ACTOR_ASSIGNMENT_PREFIX + actorId)
                .thenAccept(optionalNodeId -> {
                    if (optionalNodeId.isPresent()) {
                        String nodeId = optionalNodeId.get();
                        if (!nodeId.equals(systemId)) {
                            // Actor is on another node, forward the message
                            if (messagingSystem instanceof ReliableMessagingSystem) {
                                // Use the reliable messaging system with the specified delivery guarantee
                                ((ReliableMessagingSystem) messagingSystem).sendMessage(
                                        nodeId, actorId, message, deliveryGuarantee)
                                    .exceptionally(ex -> {
                                        logger.error("Failed to send message to actor {} on node {}", 
                                                    actorId, nodeId, ex);
                                        return null;
                                    });
                            } else {
                                // Fall back to the standard messaging system
                                messagingSystem.sendMessage(nodeId, actorId, message)
                                    .exceptionally(ex -> {
                                        logger.error("Failed to send message to actor {} on node {}", 
                                                    actorId, nodeId, ex);
                                        return null;
                                    });
                            }
                        } else {
                            // Actor is registered to this node but not found locally
                            // Try to get the actor again (it might have been created since our first check)
                            Actor<Message> localActor = (Actor<Message>) getActor(new Pid(actorId, this));
                            if (localActor != null) {
                                // Actor is now available locally, deliver the message directly
                                localActor.tell(message);
                            } else {
                                logger.warn("Actor {} is registered to this node but not found locally", actorId);
                            }
                        }
                    } else {
                        logger.warn("Actor {} not found in the cluster", actorId);
                    }
                })
                .exceptionally(ex -> {
                    logger.error("Failed to look up actor {} in metadata store", actorId, ex);
                    return null;
                });
    }

    /**
     * Routes a message to an actor with a delay, either locally or remotely.
     * This method is called by Pid.tell() with delay.
     * Note: This method has the same signature as the one in ActorSystem but is not an override
     * because the method in ActorSystem is package-private.
     *
     * @param actorId The ID of the target actor
     * @param message The message to send
     * @param delay The delay amount
     * @param timeUnit The time unit for the delay
     * @param <Message> The type of the message
     */
    @SuppressWarnings("RedundantSuppression")
    public <Message> void routeMessage(String actorId, Message message, Long delay, TimeUnit timeUnit) {
        routeMessage(actorId, message, delay, timeUnit, defaultDeliveryGuarantee);
    }

    /**
     * Routes a message to an actor with a delay and specific delivery guarantee.
     *
     * @param actorId The ID of the target actor
     * @param message The message to send
     * @param delay The delay amount
     * @param timeUnit The time unit for the delay
     * @param deliveryGuarantee The delivery guarantee to use
     * @param <Message> The type of the message
     */
    @SuppressWarnings("RedundantSuppression")
    public <Message> void routeMessage(String actorId, Message message, Long delay, TimeUnit timeUnit, 
                              DeliveryGuarantee deliveryGuarantee) {
        // For delayed messages, we schedule them locally and handle routing when the delay expires
        scheduler.schedule(() -> {
            routeMessage(actorId, message, deliveryGuarantee);
        }, delay, timeUnit);
    }

    /**
     * Handles a message received from a remote actor system.
     *
     * @param actorId The ID of the target actor
     * @param message The message
     * @param <Message> The type of the message
     */
    @SuppressWarnings("unchecked")
    private <Message> void handleRemoteMessage(String actorId, Message message) {
        Actor<Message> actor = (Actor<Message>) getActor(new Pid(actorId, this));
        if (actor != null) {
            actor.tell(message);
        } else {
            logger.warn("Received message for unknown actor: {}", actorId);
        }
    }

    /**
     * Registers this node in the metadata store.
     *
     * @return A CompletableFuture that completes when the node is registered
     */
    private CompletableFuture<Void> registerNode() {
        return metadataStore.put(NODE_PREFIX + systemId, "")
                .thenCompose(v -> metadataStore.listKeys(NODE_PREFIX))
                .thenAccept(keys -> {
                    for (String key : keys) {
                        String nodeId = key.substring(NODE_PREFIX.length());
                        knownNodes.add(nodeId);
                    }
                    logger.info("Registered node {} in cluster with {} known nodes", systemId, knownNodes.size());
                });
    }

    /**
     * Sends a heartbeat to keep the node registration active.
     */
    private void sendHeartbeat() {
        metadataStore.put(NODE_PREFIX + systemId, Long.toString(System.currentTimeMillis()))
                .exceptionally(ex -> {
                    logger.error("Failed to send heartbeat", ex);
                    return null;
                });
    }

    /**
     * Attempts to become the leader of the cluster.
     */
    private void tryBecomeLeader() {
        // If we're already the leader, refresh the lock
        if (isLeader.get() && leaderLock != null) {
            leaderLock.refresh()
                    .exceptionally(ex -> {
                        logger.error("Failed to refresh leader lock", ex);
                        isLeader.set(false);
                        leaderLock = null;
                        return null;
                    });
            return;
        }

        // Try to acquire the leader lock
        metadataStore.acquireLock(LEADER_KEY, HEARTBEAT_INTERVAL_SECONDS * 3)
                .thenAccept(optionalLock -> {
                    if (optionalLock.isPresent()) {
                        leaderLock = optionalLock.get();
                        isLeader.set(true);
                        logger.info("Became leader of the cluster");

                        // As the new leader, check for unassigned actors
                        checkForUnassignedActors();
                    }
                })
                .exceptionally(ex -> {
                    logger.error("Failed to acquire leader lock", ex);
                    return null;
                });
    }

    /**
     * Checks for unassigned actors and assigns them to nodes.
     */
    private void checkForUnassignedActors() {
        if (!isLeader.get()) {
            return;
        }

        metadataStore.listKeys(ACTOR_ASSIGNMENT_PREFIX)
                .thenCompose(actorKeys -> {
                    Set<String> actorIds = new HashSet<>();
                    for (String key : actorKeys) {
                        actorIds.add(key.substring(ACTOR_ASSIGNMENT_PREFIX.length()));
                    }

                    // For each actor, check if its assigned node is still alive
                    CompletableFuture<Void> allChecks = CompletableFuture.completedFuture(null);

                    for (String actorId : actorIds) {
                        allChecks = allChecks.thenCompose(v -> 
                            metadataStore.get(ACTOR_ASSIGNMENT_PREFIX + actorId)
                                .thenCompose(optionalNodeId -> {
                                    if (optionalNodeId.isEmpty() || !knownNodes.contains(optionalNodeId.get())) {
                                        // Actor is unassigned or assigned to a dead node, reassign it
                                        return assignActorToNode(actorId);
                                    }
                                    return CompletableFuture.completedFuture(null);
                                })
                        );
                    }

                    return allChecks;
                })
                .exceptionally(ex -> {
                    logger.error("Failed to check for unassigned actors", ex);
                    return null;
                });
    }

    /**
     * Returns the actor with the specified ID, or Optional.empty() if no such actor exists.
     * 
     * @param pid The PID of the actor to get
     * @return An Optional containing the actor, or Optional.empty() if not found
     */
    public Optional<Actor<?>> getActorOptional(Pid pid) {
        return Optional.ofNullable(getActor(pid));
    }

    /**
     * Reassigns actors from a departed node to available nodes in the cluster.
     * Ensures idempotency and handles race conditions gracefully.
     *
     * @param departedNodeId The ID of the departed node
     */
    private void reassignActorsFromNode(String departedNodeId) {
        if (!isLeader.get()) {
            return;
        }

        metadataStore.listKeys(ACTOR_ASSIGNMENT_PREFIX)
                .thenCompose(actorKeys -> {
                    CompletableFuture<Void> allReassignments = CompletableFuture.completedFuture(null);

                    for (String key : actorKeys) {
                        String actorId = key.substring(ACTOR_ASSIGNMENT_PREFIX.length());

                        allReassignments = allReassignments.thenCompose(v -> 
                            metadataStore.get(key)
                                .thenCompose(optionalNodeId -> {
                                    if (optionalNodeId.isPresent() && optionalNodeId.get().equals(departedNodeId)) {
                                        // Actor was on the departed node, reassign it
                                        return assignActorToNode(actorId);
                                    }
                                    return CompletableFuture.completedFuture(null);
                                })
                        );
                    }

                    return allReassignments;
                })
                .exceptionally(ex -> {
                    logger.error("Failed to reassign actors from node {}", departedNodeId, ex);
                    return null;
                });
    }

    /**
     * Assigns an actor to a node using rendezvous hashing.
     *
     * @param actorId The ID of the actor to assign
     * @return A CompletableFuture that completes when the actor is assigned
     */
    private CompletableFuture<Void> assignActorToNode(String actorId) {
        if (knownNodes.isEmpty()) {
            logger.warn("No nodes available to assign actor {}", actorId);
            return CompletableFuture.completedFuture(null);
        }

        Optional<String> selectedNode = RendezvousHashing.assignKey(actorId, knownNodes);

        if (selectedNode.isPresent()) {
            String nodeId = selectedNode.get();
            logger.info("Assigning actor {} to node {}", actorId, nodeId);

            return metadataStore.put(ACTOR_ASSIGNMENT_PREFIX + actorId, nodeId);
        } else {
            logger.warn("Failed to assign actor {} to a node", actorId);
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * Creates an actor on a specific node in the cluster.
     *
     * @param <T> The type of the actor
     * @param actorClass The class of the actor to create
     * @param actorId The ID for the actor
     * @param nodeId The ID of the node to create the actor on
     * @return The PID of the created actor
     */
    public <T extends Actor<?>> Pid createActorOnNode(Class<T> actorClass, String actorId, String nodeId) {
        if (!knownNodes.contains(nodeId)) {
            throw new IllegalArgumentException("Unknown node ID: " + nodeId);
        }

        // Register the actor assignment in the metadata store
        metadataStore.put(ACTOR_ASSIGNMENT_PREFIX + actorId, nodeId)
                .exceptionally(ex -> {
                    logger.error("Failed to register actor {} on node {} in metadata store", actorId, nodeId, ex);
                    return null;
                });

        // If the actor should be created on this node, create it locally
        if (nodeId.equals(systemId)) {
            return super.register(actorClass, actorId);
        }

        // Otherwise, return a PID that will route messages to the remote node
        return new Pid(actorId, this);
    }

    /**
     * Gets the ID of this actor system.
     *
     * @return The system ID
     */
    public String getSystemId() {
        return systemId;
    }

    /**
     * Gets a set of known node IDs in the cluster.
     *
     * @return A set of node IDs
     */
    public Set<String> getKnownNodes() {
        return new HashSet<>(knownNodes);
    }

    /**
     * Checks if this node is currently the leader of the cluster.
     *
     * @return true if this node is the leader, false otherwise
     */
    public boolean isLeader() {
        return isLeader.get();
    }

    /**
     * Gets the messaging system used by this actor system.
     *
     * @return The messaging system
     */
    public MessagingSystem getMessagingSystem() {
        return messagingSystem;
    }

    /**
     * Gets the metadata store used by this actor system.
     *
     * @return The metadata store
     */
    public MetadataStore getMetadataStore() {
        return metadataStore;
    }

    /**
     * Sets the default delivery guarantee for messages sent by this actor system.
     *
     * @param deliveryGuarantee The delivery guarantee to use by default
     * @return This actor system instance for method chaining
     */
    public ClusterActorSystem withDeliveryGuarantee(DeliveryGuarantee deliveryGuarantee) {
        this.defaultDeliveryGuarantee = deliveryGuarantee;

        // Update the messaging system if it supports delivery guarantees
        if (messagingSystem instanceof ReliableMessagingSystem) {
            ((ReliableMessagingSystem) messagingSystem).setDefaultDeliveryGuarantee(deliveryGuarantee);
        }

        return this;
    }

    /**
     * Gets the default delivery guarantee for this actor system.
     *
     * @return The default delivery guarantee
     */
    public DeliveryGuarantee getDefaultDeliveryGuarantee() {
        return defaultDeliveryGuarantee;
    }
}
