package com.cajunsystems.cluster;

import com.cajunsystems.Actor;
import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.metrics.ClusterMetrics;
import com.cajunsystems.persistence.PersistenceProvider;
import com.cajunsystems.persistence.PersistenceProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Map;
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
    static final String ACTOR_ASSIGNMENT_PREFIX = "cajun/actor/";
    static final String NODE_PREFIX = "cajun/node/";
    private static final String LEADER_KEY = "cajun/leader";
    private static final long HEARTBEAT_INTERVAL_SECONDS = 5;
    private static final long ACTOR_CACHE_TTL_MS = 60_000; // 60-second TTL

    private final String systemId;
    private final ClusterMetrics clusterMetrics;
    private final MetadataStore metadataStore;
    private final MessagingSystem messagingSystem;
    private final ScheduledExecutorService scheduler;
    private final Set<String> knownNodes = ConcurrentHashMap.newKeySet();
    // Local cache of actor→node assignments with TTL-based expiry
    private final TtlCache<String, String> actorAssignmentCache = new TtlCache<>(ACTOR_CACHE_TTL_MS);
    private final AtomicBoolean isLeader = new AtomicBoolean(false);
    private MetadataStore.Lock leaderLock;
    private DeliveryGuarantee defaultDeliveryGuarantee = DeliveryGuarantee.EXACTLY_ONCE;
    private PersistenceProvider persistenceProvider; // null = use existing registry default
    private final ClusterManagementApi managementApi;
    // Actors being shut down locally only (skip metadata deletion — new assignment already written)
    private final Set<String> skipMetadataDeleteActors = ConcurrentHashMap.newKeySet();

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
        this.clusterMetrics = new ClusterMetrics(systemId);
        this.metadataStore = metadataStore;
        this.messagingSystem = messagingSystem;
        this.scheduler = new ScheduledThreadPoolExecutor(1);

        // Set default delivery guarantee if messaging system supports it
        if (messagingSystem instanceof ReliableMessagingSystem rms) {
            rms.setDefaultDeliveryGuarantee(defaultDeliveryGuarantee);
            rms.setClusterMetrics(clusterMetrics);
        }

        // Register message handler for remote actor messages
        messagingSystem.registerMessageHandler(this::handleRemoteMessage);

        this.managementApi = new DefaultClusterManagementApi(this);
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
                    setupPersistence();  // register shared persistence provider before accepting actors

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
                            clusterMetrics.incrementNodeJoinCount();
                            logger.info("Node joined: {}", nodeId);
                        }

                        @Override
                        public void onDelete(String key) {
                            String nodeId = key.substring(NODE_PREFIX.length());
                            knownNodes.remove(nodeId);
                            clusterMetrics.incrementNodeDepartureCount();
                            logger.info("Node left: {}", nodeId);

                            // If we're the leader, reassign actors from the departed node
                            if (isLeader.get()) {
                                reassignActorsFromNode(nodeId);
                            }
                        }
                    });

                    // Watch actor assignment changes — drive cache eviction and real-time updates
                    metadataStore.watch(ACTOR_ASSIGNMENT_PREFIX, new MetadataStore.KeyWatcher() {
                        @Override
                        public void onPut(String key, String value) {
                            String watchedActorId = key.substring(ACTOR_ASSIGNMENT_PREFIX.length());
                            actorAssignmentCache.put(watchedActorId, value);
                            logger.debug("Actor assignment updated via watcher: '{}' → node '{}'", watchedActorId, value);
                        }
                        @Override
                        public void onDelete(String key) {
                            String watchedActorId = key.substring(ACTOR_ASSIGNMENT_PREFIX.length());
                            actorAssignmentCache.invalidate(watchedActorId);
                            logger.debug("Actor assignment evicted via watcher: '{}'", watchedActorId);
                        }
                    });

                    // Schedule periodic TTL cleanup every 30 seconds
                    scheduler.scheduleAtFixedRate(
                            actorAssignmentCache::cleanupExpired,
                            30,
                            30,
                            TimeUnit.SECONDS
                    );
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
                    // Suppress per-actor metadata deletion during system stop.
                    // When the whole system shuts down, actor assignments should remain
                    // in the metadata store so the leader can detect orphaned actors and
                    // reassign them to surviving nodes. The node key deletion above already
                    // signals departure; individual actor metadata must not be deleted here.
                    Set<String> localActors = getRegisteredActorIds();
                    skipMetadataDeleteActors.addAll(localActors);
                    try {
                        super.shutdown();
                    } finally {
                        skipMetadataDeleteActors.removeAll(localActors);
                    }
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

    /**
     * Registers multiple actors in the metadata store in parallel using CompletableFuture.allOf().
     * Significantly faster than sequential registration for large numbers of actors.
     *
     * @param actorIds List of actor IDs to register under this node
     * @return CompletableFuture that completes when all registrations are done
     */
    public CompletableFuture<Void> batchRegisterActors(java.util.List<String> actorIds) {
        if (actorIds == null || actorIds.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        long start = System.nanoTime();
        java.util.List<CompletableFuture<Void>> futures = actorIds.stream()
                .map(actorId -> metadataStore.put(ACTOR_ASSIGNMENT_PREFIX + actorId, systemId)
                        .exceptionally(ex -> {
                            logger.error("Failed to batch-register actor '{}': {}", actorId, ex.getMessage());
                            return null;
                        }))
                .toList();
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .thenRun(() -> logger.info("Batch registered {} actors in {}ms",
                        actorIds.size(), (System.nanoTime() - start) / 1_000_000));
    }

    @Override
    public void shutdown(String actorId) {
        // During shutdownLocalOnly(), Actor.stop() calls system.shutdown(actorId) as part
        // of its teardown. Skip metadata deletion in that case — the new assignment was
        // already written and must not be overwritten.
        if (!skipMetadataDeleteActors.contains(actorId)) {
            metadataStore.delete(ACTOR_ASSIGNMENT_PREFIX + actorId)
                    .exceptionally(ex -> {
                        logger.error("Failed to unregister actor {} from metadata store", actorId, ex);
                        return null;
                    });
        }

        // Shut down the actor locally
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
            clusterMetrics.incrementLocalMessagesRouted();
            actor.tell(message);
            return;
        }

        // Check assignment cache before going to metadata store (primary fast path)
        Optional<String> cachedNodeId = actorAssignmentCache.get(actorId);
        if (cachedNodeId.isPresent()) {
            clusterMetrics.incrementCacheHit();
            routeToNode(actorId, cachedNodeId.get(), message, deliveryGuarantee);
            return;
        }

        // Cache miss — look up in metadata store
        clusterMetrics.incrementCacheMiss();
        metadataStore.get(ACTOR_ASSIGNMENT_PREFIX + actorId)
                .thenAccept(optionalNodeId -> {
                    if (optionalNodeId.isPresent()) {
                        String nodeId = optionalNodeId.get();
                        actorAssignmentCache.put(actorId, nodeId);
                        routeToNode(actorId, nodeId, message, deliveryGuarantee);
                    } else {
                        logger.warn("Actor '{}' not found in cluster metadata store — not yet registered or already deregistered", actorId);
                    }
                })
                .exceptionally(ex -> {
                    logger.error("Metadata store unreachable and no cached assignment for actor '{}' — message dropped", actorId);
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

    @SuppressWarnings("unchecked")
    private <Message> void routeToNode(String actorId, String nodeId, Message message,
                                        DeliveryGuarantee deliveryGuarantee) {
        if (nodeId.equals(systemId)) {
            Actor<Message> localActor = (Actor<Message>) getActor(new Pid(actorId, this));
            if (localActor != null) {
                clusterMetrics.incrementLocalMessagesRouted();
                localActor.tell(message);
            } else {
                logger.warn("Actor '{}' is registered to node '{}' (this node) but not found in local actor registry — it may still be initializing",
                        actorId, systemId);
            }
        } else {
            long routeStart = System.nanoTime();
            clusterMetrics.incrementRemoteMessagesRouted();
            CompletableFuture<Void> sendFuture;
            if (messagingSystem instanceof ReliableMessagingSystem rms) {
                sendFuture = rms.sendMessage(nodeId, actorId, message, deliveryGuarantee);
            } else {
                sendFuture = messagingSystem.sendMessage(nodeId, actorId, message);
            }
            sendFuture
                .thenRun(() -> clusterMetrics.recordRoutingLatency(System.nanoTime() - routeStart))
                .exceptionally(ex -> {
                    // ReliableMessagingSystem already increments remoteMessageFailures at the
                    // transport layer in doSendMessage(). Only increment here for other
                    // messaging system implementations to avoid double-counting.
                    if (!(messagingSystem instanceof ReliableMessagingSystem)) {
                        clusterMetrics.incrementRemoteMessageFailures();
                    }
                    logger.error("Failed to send message to actor '{}' on node '{}': {}",
                            actorId, nodeId, ex.getMessage(), ex);
                    return null;
                });
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
     * Gets the cluster metrics for this actor system.
     *
     * @return The ClusterMetrics instance
     */
    public ClusterMetrics getClusterMetrics() {
        return clusterMetrics;
    }

    /**
     * Returns the management API for programmatic cluster operations.
     */
    public ClusterManagementApi getManagementApi() {
        return managementApi;
    }

    /**
     * Invalidates the local actor-assignment cache entry for the given actor.
     * Called by plan 30-2 after a migration or drain operation updates the assignment.
     */
    void invalidateActorAssignmentCache(String actorId) {
        actorAssignmentCache.invalidate(actorId);
    }

    /**
     * Stops the actor's local mailbox without deleting its cluster assignment from the
     * metadata store. Used by the management API during migration so the new assignment
     * written to etcd is not overwritten by the usual shutdown path.
     */
    void shutdownLocalOnly(String actorId) {
        // Mark this actor so that the Actor.stop() → system.shutdown(actorId) callback
        // (triggered during super.shutdown) skips metadata deletion.
        skipMetadataDeleteActors.add(actorId);
        try {
            super.shutdown(actorId);
        } finally {
            skipMetadataDeleteActors.remove(actorId);
        }
    }

    public Map<String, String> getActorAssignmentCache() {
        return actorAssignmentCache.snapshot();
    }

    /**
     * Returns a health check snapshot for this cluster node.
     *
     * @return A ClusterHealthStatus record reflecting current node health
     */
    public ClusterHealthStatus healthCheck() {
        boolean persistenceHealthy = persistenceProvider == null || persistenceProvider.isHealthy();
        String persistenceProviderName = persistenceProvider == null ? null : persistenceProvider.getProviderName();
        boolean messagingRunning = messagingSystem != null;
        return ClusterHealthStatus.of(
                systemId,
                isLeader.get(),
                knownNodes.size(),
                messagingRunning,
                persistenceHealthy,
                persistenceProviderName
        );
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

    /**
     * Configures a shared persistence provider for this cluster node.
     * Registered in {@link PersistenceProviderRegistry} and set as the default
     * during {@link #start()}. All {@link com.cajunsystems.StatefulActor}s spawned
     * on this node will use this provider.
     */
    public ClusterActorSystem withPersistenceProvider(PersistenceProvider provider) {
        this.persistenceProvider = provider;
        return this;
    }

    private void setupPersistence() {
        if (persistenceProvider == null) {
            return;
        }
        PersistenceProviderRegistry registry = PersistenceProviderRegistry.getInstance();
        registry.registerProvider(persistenceProvider);
        registry.setDefaultProvider(persistenceProvider.getProviderName());
        if (!persistenceProvider.isHealthy()) {
            logger.warn(
                "Persistence provider '{}' reports unhealthy at cluster startup — " +
                "StatefulActors may fail to recover state",
                persistenceProvider.getProviderName());
        } else {
            logger.info("Persistence provider '{}' configured and healthy",
                persistenceProvider.getProviderName());
        }
    }
}
