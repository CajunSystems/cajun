# Cajun Cluster Mode Improvements

After reviewing the cluster mode implementation in Cajun, I've identified several areas for improvement to enhance performance, reliability, and usability. Below are concrete suggestions with code examples for each improvement.

## 1. Performance Improvements

### 1.1 Connection Pooling for EtcdMetadataStore

The current implementation creates a new connection for each operation. Implementing connection pooling would reduce connection overhead and improve performance.

```java
public class EtcdMetadataStore implements MetadataStore {
    private final ConnectionPool connectionPool;
    
    public EtcdMetadataStore(String... endpoints) {
        this(endpoints, 10); // Default max connections
    }
    
    public EtcdMetadataStore(String[] endpoints, int maxConnections) {
        this.endpoints = endpoints;
        this.connectionPool = new ConnectionPool(maxConnections);
    }
    
    @Override
    public CompletableFuture<Void> connect() {
        return CompletableFuture.runAsync(() -> {
            Client.Builder builder = Client.builder().endpoints(endpoints);
            connectionPool.initialize(builder, maxConnections);
        });
    }
    
    @Override
    public CompletableFuture<Void> put(String key, String value) {
        return connectionPool.withClient(client -> {
            ByteSequence keyBytes = ByteSequence.from(key, StandardCharsets.UTF_8);
            ByteSequence valueBytes = ByteSequence.from(value, StandardCharsets.UTF_8);
            
            return client.getKVClient().put(keyBytes, valueBytes)
                    .thenApply(putResponse -> null);
        });
    }
}
```

### 1.2 Batch Operations for Actor Registration

Currently, each actor registration is a separate operation. Implementing batch operations would reduce network overhead.

```java
public class ClusterActorSystem extends ActorSystem {
    private final Map<String, String> pendingRegistrations = new ConcurrentHashMap<>();
    private final ScheduledExecutorService batchScheduler = Executors.newSingleThreadScheduledExecutor();
    private static final int BATCH_SIZE = 50;
    
    // Schedule periodic batch processing
    private void processPendingRegistrations() {
        if (pendingRegistrations.isEmpty()) return;
        
        // Take a batch of pending registrations
        Map<String, String> batch = new HashMap<>();
        Iterator<Map.Entry<String, String>> iterator = pendingRegistrations.entrySet().iterator();
        int count = 0;
        
        while (iterator.hasNext() && count < BATCH_SIZE) {
            Map.Entry<String, String> entry = iterator.next();
            batch.put(ACTOR_ASSIGNMENT_PREFIX + entry.getKey(), entry.getValue());
            iterator.remove();
            count++;
        }
        
        // Process the batch
        if (!batch.isEmpty()) {
            metadataStore.putAll(batch)
                .exceptionally(ex -> {
                    logger.error("Failed to register actors in batch", ex);
                    // Re-add failed registrations
                    for (Map.Entry<String, String> entry : batch.entrySet()) {
                        String actorId = entry.getKey().substring(ACTOR_ASSIGNMENT_PREFIX.length());
                        pendingRegistrations.put(actorId, entry.getValue());
                    }
                    return null;
                });
        }
    }
}
```

### 1.3 Caching for Actor Locations

Implement a local cache for actor locations to reduce metadata store lookups.

```java
public class ClusterActorSystem extends ActorSystem {
    private final Map<String, String> actorLocationCache = new ConcurrentHashMap<>();
    private final long cacheExpiryMs = 5000; // 5 seconds
    private final Map<String, Long> cacheTimestamps = new ConcurrentHashMap<>();
    
    public <Message> void routeMessage(String actorId, Message message, DeliveryGuarantee deliveryGuarantee) {
        // Check if the actor is local
        Actor<Message> actor = (Actor<Message>) getActor(new Pid(actorId, this));
        if (actor != null) {
            actor.tell(message);
            return;
        }
        
        // Check cache for actor location
        String cachedNodeId = actorLocationCache.get(actorId);
        Long timestamp = cacheTimestamps.get(actorId);
        long now = System.currentTimeMillis();
        
        if (cachedNodeId != null && timestamp != null && (now - timestamp) < cacheExpiryMs) {
            // Use cached location
            sendMessageToNode(cachedNodeId, actorId, message, deliveryGuarantee);
            return;
        }
        
        // Look up in metadata store
        metadataStore.get(ACTOR_ASSIGNMENT_PREFIX + actorId)
                .thenAccept(optionalNodeId -> {
                    if (optionalNodeId.isPresent()) {
                        String nodeId = optionalNodeId.get();
                        // Update cache
                        actorLocationCache.put(actorId, nodeId);
                        cacheTimestamps.put(actorId, System.currentTimeMillis());
                        
                        if (!nodeId.equals(systemId)) {
                            sendMessageToNode(nodeId, actorId, message, deliveryGuarantee);
                        }
                    }
                });
    }
}
```

## 2. Reliability Improvements

### 2.1 Circuit Breaker for Remote Messaging

Implement a circuit breaker pattern to handle failures in remote messaging.

```java
public class CircuitBreaker {
    private final String name;
    private final int failureThreshold;
    private final long resetTimeoutMs;
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicBoolean open = new AtomicBoolean(false);
    private final AtomicLong lastFailureTime = new AtomicLong(0);
    
    public <T> CompletableFuture<T> execute(Supplier<CompletableFuture<T>> action) {
        if (isOpen()) {
            return CompletableFuture.failedFuture(
                new CircuitBreakerOpenException("Circuit breaker " + name + " is open"));
        }
        
        return action.get()
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    recordFailure();
                } else {
                    reset();
                }
            });
    }
    
    // Implementation details omitted for brevity
}

// Usage in ReliableMessagingSystem
public class ReliableMessagingSystem implements MessagingSystem {
    private final Map<String, CircuitBreaker> nodeCircuitBreakers = new ConcurrentHashMap<>();
    
    public <Message> CompletableFuture<Void> sendMessage(
            String targetSystemId, String actorId, Message message, DeliveryGuarantee deliveryGuarantee) {
        
        CircuitBreaker circuitBreaker = nodeCircuitBreakers.computeIfAbsent(targetSystemId, 
            id -> new CircuitBreaker("node-" + id, 5, 30000));
        
        return circuitBreaker.execute(() -> CompletableFuture.runAsync(() -> {
            // Existing message sending logic
        }));
    }
}
```

### 2.2 Improved Error Handling in EtcdMetadataStore

Enhance error handling in the EtcdMetadataStore to handle common etcd failures.

```java
public class EtcdMetadataStore implements MetadataStore {
    private final int maxRetries;
    private final long retryDelayMs;
    
    // Helper method for retrying operations
    private <T> CompletableFuture<T> withRetry(Supplier<CompletableFuture<T>> operation) {
        return withRetry(operation, 0);
    }
    
    private <T> CompletableFuture<T> withRetry(Supplier<CompletableFuture<T>> operation, int attempt) {
        return operation.get().exceptionally(ex -> {
            if (attempt < maxRetries) {
                // Retry with exponential backoff
                long delay = retryDelayMs * (long)Math.pow(2, attempt);
                CompletableFuture<T> future = new CompletableFuture<>();
                
                scheduler.schedule(() -> {
                    withRetry(operation, attempt + 1)
                        .whenComplete((result, error) -> {
                            if (error != null) {
                                future.completeExceptionally(error);
                            } else {
                                future.complete(result);
                            }
                        });
                }, delay, TimeUnit.MILLISECONDS);
                
                return future.join();
            } else {
                throw new CompletionException("Operation failed after " + maxRetries + " retries", ex);
            }
        });
    }
    
    @Override
    public CompletableFuture<Void> put(String key, String value) {
        return withRetry(() -> {
            // Existing put logic
        });
    }
}
```

### 2.3 Graceful Degradation for Cluster Features

Implement graceful degradation when cluster services are unavailable.

```java
public class ClusterActorSystem extends ActorSystem {
    private final AtomicBoolean metadataStoreAvailable = new AtomicBoolean(true);
    private final AtomicBoolean messagingSystemAvailable = new AtomicBoolean(true);
    
    // Health check method
    private void checkClusterHealth() {
        // Check metadata store health
        metadataStore.ping()
            .thenAccept(result -> {
                boolean wasAvailable = metadataStoreAvailable.getAndSet(result);
                if (wasAvailable && !result) {
                    logger.warn("Metadata store became unavailable, operating in degraded mode");
                } else if (!wasAvailable && result) {
                    logger.info("Metadata store is available again");
                    syncActorRegistry();
                }
            });
        
        // Check messaging system health
        messagingSystem.ping()
            .thenAccept(result -> {
                boolean wasAvailable = messagingSystemAvailable.getAndSet(result);
                if (wasAvailable && !result) {
                    logger.warn("Messaging system became unavailable, operating in degraded mode");
                }
            });
    }
}
```

## 3. Usability Improvements

### 3.1 Simplified Cluster Configuration

Create a builder pattern for easier cluster configuration.

```java
public class ClusterConfig {
    private final String systemId;
    private final String[] metadataEndpoints;
    private final int messagingPort;
    private final DeliveryGuarantee defaultDeliveryGuarantee;
    private final Map<String, NodeAddress> knownNodes;
    
    public static class Builder {
        private String systemId;
        private String[] metadataEndpoints;
        private int messagingPort = 8080;
        private DeliveryGuarantee defaultDeliveryGuarantee = DeliveryGuarantee.EXACTLY_ONCE;
        private final Map<String, NodeAddress> knownNodes = new HashMap<>();
        
        public Builder(String systemId, String... metadataEndpoints) {
            this.systemId = systemId;
            this.metadataEndpoints = metadataEndpoints;
        }
        
        public Builder withMessagingPort(int port) {
            this.messagingPort = port;
            return this;
        }
        
        public Builder withDeliveryGuarantee(DeliveryGuarantee guarantee) {
            this.defaultDeliveryGuarantee = guarantee;
            return this;
        }
        
        public Builder addNode(String nodeId, String host, int port) {
            this.knownNodes.put(nodeId, new NodeAddress(host, port));
            return this;
        }
        
        public ClusterConfig build() {
            return new ClusterConfig(this);
        }
    }
}

// Usage example
ClusterConfig config = new ClusterConfig.Builder("node1", "http://etcd-host:2379")
    .withMessagingPort(8080)
    .withDeliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE)
    .addNode("node2", "node2-host", 8080)
    .build();

ClusterActorSystem system = ClusterFactory.createClusterSystem(config);
```

### 3.2 Monitoring and Metrics

Add monitoring and metrics capabilities to the cluster.

```java
public interface ClusterMetrics {
    int getActorCount();
    int getNodeCount();
    double getMessageThroughput();
    double getAverageMessageLatency();
    long getFailedMessageCount();
    Map<String, Boolean> getHealthStatus();
}

public class ClusterActorSystem extends ActorSystem implements ClusterMetrics {
    private final AtomicLong messagesSent = new AtomicLong(0);
    private final AtomicLong messagesReceived = new AtomicLong(0);
    private final AtomicLong failedMessages = new AtomicLong(0);
    private final AtomicLong totalLatency = new AtomicLong(0);
    private final AtomicLong latencyMeasurements = new AtomicLong(0);
    
    @Override
    public int getActorCount() {
        return getActors().size();
    }
    
    @Override
    public int getNodeCount() {
        return knownNodes.size();
    }
    
    @Override
    public double getAverageMessageLatency() {
        long measurements = latencyMeasurements.get();
        if (measurements == 0) return 0;
        return (double) totalLatency.get() / measurements;
    }
    
    // Other metrics methods implementation
}
```

### 3.3 Cluster Management API

Add a management API for cluster operations.

```java
public interface ClusterManager {
    CompletableFuture<List<NodeInfo>> getClusterNodes();
    CompletableFuture<List<ActorInfo>> getClusterActors();
    CompletableFuture<Boolean> migrateActor(String actorId, String targetNodeId);
    CompletableFuture<Boolean> drainNode(String nodeId);
    CompletableFuture<Boolean> forceLeaderElection();
}

public class ClusterActorSystem extends ActorSystem implements ClusterManager {
    @Override
    public CompletableFuture<List<NodeInfo>> getClusterNodes() {
        return metadataStore.listKeys(NODE_PREFIX)
            .thenCompose(keys -> {
                List<CompletableFuture<NodeInfo>> futures = new ArrayList<>();
                
                for (String key : keys) {
                    String nodeId = key.substring(NODE_PREFIX.length());
                    CompletableFuture<NodeInfo> future = metadataStore.get(key)
                        .thenApply(optValue -> {
                            String lastHeartbeat = optValue.orElse("");
                            boolean isLeaderNode = nodeId.equals(currentLeader);
                            return new NodeInfo(nodeId, lastHeartbeat, isLeaderNode);
                        });
                    futures.add(future);
                }
                
                return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .thenApply(v -> futures.stream()
                        .map(CompletableFuture::join)
                        .collect(Collectors.toList()));
            });
    }
    
    // Other management methods implementation
}
```

## 4. Security Improvements

### 4.1 TLS Support for Cluster Communication

Add TLS support for secure communication between nodes.

```java
public class SecureMessagingSystem extends ReliableMessagingSystem {
    public SecureMessagingSystem(String systemId, int port, SSLContext sslContext) {
        super(systemId, port);
        this.sslContext = sslContext;
    }
    
    @Override
    protected Socket createSocket() {
        return sslContext.getSocketFactory().createSocket();
    }
    
    @Override
    protected ServerSocket createServerSocket(int port) throws IOException {
        return sslContext.getServerSocketFactory().createServerSocket(port);
    }
}
```

### 4.2 Authentication for Cluster Nodes

Implement authentication between cluster nodes.

```java
public class AuthenticatedMessagingSystem extends MessagingSystem {
    private final String authToken;
    
    @Override
    protected void handleClient(Socket clientSocket) {
        try {
            // Read auth token first
            DataInputStream in = new DataInputStream(clientSocket.getInputStream());
            String receivedToken = in.readUTF();
            
            if (!authToken.equals(receivedToken)) {
                logger.warn("Authentication failed from {}", clientSocket.getInetAddress());
                clientSocket.close();
                return;
            }
            
            // Continue with normal message handling
            super.handleClient(clientSocket);
        } catch (IOException e) {
            logger.error("Error during authentication", e);
        }
    }
}
```

## 5. Scalability Improvements

### 5.1 Sharding for Actor Distribution

Implement actor sharding for better distribution across nodes.

```java
public class ShardedClusterActorSystem extends ClusterActorSystem {
    private final int shardCount;
    
    @Override
    protected String selectNodeForActor(String actorId) {
        // Determine shard for this actor
        int shard = Math.abs(actorId.hashCode() % shardCount);
        
        // Get nodes responsible for this shard
        List<String> shardNodes = getNodesForShard(shard);
        
        // Select the least loaded node from the shard nodes
        return selectLeastLoadedNode(shardNodes);
    }
}
```

### 5.2 Dynamic Node Discovery

Add support for dynamic node discovery.

```java
public class ClusterActorSystem extends ActorSystem {
    public CompletableFuture<Void> joinCluster(String discoveryEndpoint) {
        return CompletableFuture.runAsync(() -> {
            // Register this node with the discovery service
            discoveryClient.register(systemId, getNodeAddress());
            
            // Start listening for node changes
            discoveryClient.watchNodes(nodes -> {
                for (NodeInfo node : nodes) {
                    if (!knownNodes.contains(node.getId())) {
                        // Add new node
                        addNode(node.getId(), node.getHost(), node.getPort());
                    }
                }
                
                // Remove nodes that are no longer in the discovery service
                List<String> currentNodeIds = nodes.stream()
                    .map(NodeInfo::getId)
                    .collect(Collectors.toList());
                
                knownNodes.removeIf(nodeId -> !currentNodeIds.contains(nodeId));
            });
        });
    }
}
```

## Conclusion

These improvements would significantly enhance the Cajun cluster mode by improving performance, reliability, usability, security, and scalability. The suggested changes are designed to be backward compatible and can be implemented incrementally.
