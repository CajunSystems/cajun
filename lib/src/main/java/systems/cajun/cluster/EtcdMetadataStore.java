package systems.cajun.cluster;

import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.KeyValue;
import io.etcd.jetcd.Lease;
import io.etcd.jetcd.Watch;
import io.etcd.jetcd.lease.LeaseKeepAliveResponse;
import io.etcd.jetcd.options.GetOption;
import io.etcd.jetcd.watch.WatchEvent;
import io.grpc.stub.StreamObserver;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Implementation of MetadataStore using etcd as the backend.
 */
public class EtcdMetadataStore implements MetadataStore {
    
    private final String[] endpoints;
    private Client client;
    private final Map<Long, Watch.Watcher> watchers = new ConcurrentHashMap<>();
    private final Map<String, EtcdLock> activeLocks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(1);
    
    /**
     * Creates a new EtcdMetadataStore with the specified endpoints.
     *
     * @param endpoints The etcd endpoints (e.g., "http://localhost:2379")
     */
    public EtcdMetadataStore(String... endpoints) {
        this.endpoints = endpoints;
    }
    
    @Override
    public CompletableFuture<Void> connect() {
        return CompletableFuture.runAsync(() -> {
            client = Client.builder().endpoints(endpoints).build();
        });
    }
    
    @Override
    public CompletableFuture<Void> close() {
        return CompletableFuture.runAsync(() -> {
            // Cancel all watchers
            for (Watch.Watcher watcher : watchers.values()) {
                watcher.close();
            }
            watchers.clear();
            
            // Release all locks
            for (EtcdLock lock : activeLocks.values()) {
                try {
                    lock.release().join();
                } catch (Exception e) {
                    // Ignore exceptions during shutdown
                }
            }
            activeLocks.clear();
            
            // Shutdown the scheduler
            scheduler.shutdown();
            try {
                scheduler.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            // Close the client
            if (client != null) {
                client.close();
                client = null;
            }
        });
    }
    
    @Override
    public CompletableFuture<Void> put(String key, String value) {
        ByteSequence keyBytes = ByteSequence.from(key, StandardCharsets.UTF_8);
        ByteSequence valueBytes = ByteSequence.from(value, StandardCharsets.UTF_8);
        
        return client.getKVClient().put(keyBytes, valueBytes)
                .thenApply(putResponse -> null);
    }
    
    @Override
    public CompletableFuture<Optional<String>> get(String key) {
        ByteSequence keyBytes = ByteSequence.from(key, StandardCharsets.UTF_8);
        
        return client.getKVClient().get(keyBytes)
                .thenApply(getResponse -> {
                    if (getResponse.getKvs().isEmpty()) {
                        return Optional.empty();
                    }
                    
                    ByteSequence value = getResponse.getKvs().get(0).getValue();
                    return Optional.of(value.toString(StandardCharsets.UTF_8));
                });
    }
    
    @Override
    public CompletableFuture<Void> delete(String key) {
        ByteSequence keyBytes = ByteSequence.from(key, StandardCharsets.UTF_8);
        
        return client.getKVClient().delete(keyBytes)
                .thenApply(deleteResponse -> null);
    }
    
    @Override
    public CompletableFuture<List<String>> listKeys(String prefix) {
        ByteSequence prefixBytes = ByteSequence.from(prefix, StandardCharsets.UTF_8);
        GetOption option = GetOption.builder()
                .isPrefix(true)
                .build();
        
        return client.getKVClient().get(prefixBytes, option)
                .thenApply(getResponse -> {
                    List<String> keys = new ArrayList<>();
                    for (KeyValue kv : getResponse.getKvs()) {
                        keys.add(kv.getKey().toString(StandardCharsets.UTF_8));
                    }
                    return keys;
                });
    }
    
    @Override
    public CompletableFuture<Optional<MetadataStore.Lock>> acquireLock(String lockName, long ttlSeconds) {
        ByteSequence lockNameBytes = ByteSequence.from(lockName, StandardCharsets.UTF_8);
        
        return client.getLeaseClient().grant(ttlSeconds)
                .thenCompose(leaseGrantResponse -> {
                    long leaseId = leaseGrantResponse.getID();
                    
                    return client.getLockClient().lock(lockNameBytes, leaseId)
                            .thenApply(lockResponse -> {
                                String lockKey = lockResponse.getKey().toString(StandardCharsets.UTF_8);
                                EtcdLock lock = new EtcdLock(lockKey, leaseId, lockName);
                                activeLocks.put(lockName, lock);
                                return Optional.of((MetadataStore.Lock) lock);
                            })
                            .exceptionally(ex -> {
                                // Failed to acquire lock, revoke the lease
                                client.getLeaseClient().revoke(leaseId);
                                return Optional.empty();
                            });
                });
    }
    
    @Override
    public CompletableFuture<Long> watch(String key, KeyWatcher watcher) {
        ByteSequence keyBytes = ByteSequence.from(key, StandardCharsets.UTF_8);
        
        CompletableFuture<Long> future = new CompletableFuture<>();
        
        Watch.Listener listener = Watch.listener(response -> {
            for (WatchEvent event : response.getEvents()) {
                KeyValue kv = event.getKeyValue();
                String eventKey = kv.getKey().toString(StandardCharsets.UTF_8);
                
                switch (event.getEventType()) {
                    case PUT:
                        String value = kv.getValue().toString(StandardCharsets.UTF_8);
                        watcher.onPut(eventKey, value);
                        break;
                    case DELETE:
                        watcher.onDelete(eventKey);
                        break;
                    default:
                        // Ignore other event types
                }
            }
        });
        
        Watch.Watcher watcherObj = client.getWatchClient().watch(keyBytes, listener);
        long watchId = System.nanoTime(); // Use a unique ID for the watch
        watchers.put(watchId, watcherObj);
        future.complete(watchId);
        
        return future;
    }
    
    @Override
    public CompletableFuture<Void> unwatch(long watchId) {
        return CompletableFuture.runAsync(() -> {
            Watch.Watcher watcher = watchers.remove(watchId);
            if (watcher != null) {
                watcher.close();
            }
        });
    }
    
    /**
     * Implementation of the Lock interface for etcd.
     */
    private class EtcdLock implements MetadataStore.Lock {
        private final String lockKey;
        private final long leaseId;
        private final String lockName;
        private ScheduledFuture<?> keepAliveFuture;
        
        EtcdLock(String lockKey, long leaseId, String lockName) {
            this.lockKey = lockKey;
            this.leaseId = leaseId;
            this.lockName = lockName;
            
            // Set up automatic lease renewal
            StreamObserver<LeaseKeepAliveResponse> observer = new StreamObserver<>() {
                @Override
                public void onNext(LeaseKeepAliveResponse response) {
                    // Lease renewed successfully
                }
                
                @Override
                public void onError(Throwable t) {
                    // Error renewing lease
                }
                
                @Override
                public void onCompleted() {
                    // Lease renewal completed
                }
            };
            
            Lease leaseClient = client.getLeaseClient();
            leaseClient.keepAlive(leaseId, observer);
        }
        
        @Override
        public CompletableFuture<Void> release() {
            ByteSequence lockKeyBytes = ByteSequence.from(lockKey, StandardCharsets.UTF_8);
            
            return client.getLockClient().unlock(lockKeyBytes)
                    .thenCompose(unlockResponse -> 
                        client.getLeaseClient().revoke(leaseId)
                            .thenApply(revokeResponse -> {
                                activeLocks.remove(lockName);
                                if (keepAliveFuture != null) {
                                    keepAliveFuture.cancel(false);
                                }
                                return null;
                            })
                    );
        }
        
        @Override
        public CompletableFuture<Void> refresh() {
            return client.getLeaseClient().keepAliveOnce(leaseId)
                    .thenApply(response -> null);
        }
    }
}
