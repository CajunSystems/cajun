package com.cajunsystems.cluster;

import com.cajunsystems.metrics.ClusterMetrics;
import com.cajunsystems.persistence.PersistenceProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ClusterHealthStatusTest {

    private ClusterActorSystem system;
    private static final String SYSTEM_ID = "test-node-1";

    @BeforeEach
    void setUp() throws Exception {
        system = new ClusterActorSystem(
                SYSTEM_ID,
                new InMemoryMetadataStore(),
                new NoOpMessagingSystem()
        );
        system.start().get(5, TimeUnit.SECONDS);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (system != null) {
            system.stop().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void testHealthCheckReturnsNodeId() {
        ClusterHealthStatus status = system.healthCheck();
        assertEquals(SYSTEM_ID, status.nodeId());
    }

    @Test
    void testHealthCheckNoPersistence() {
        ClusterHealthStatus status = system.healthCheck();
        assertTrue(status.persistenceHealthy());
        assertNull(status.persistenceProviderName());
    }

    @Test
    void testHealthCheckWithUnhealthyProvider() {
        PersistenceProvider mockProvider = mock(PersistenceProvider.class);
        when(mockProvider.isHealthy()).thenReturn(false);
        when(mockProvider.getProviderName()).thenReturn("mock-provider");

        system.withPersistenceProvider(mockProvider);

        ClusterHealthStatus status = system.healthCheck();
        assertFalse(status.persistenceHealthy());
        assertFalse(status.healthy());
    }

    @Test
    void testHealthCheckIsLeader() {
        // Initially not leader
        ClusterHealthStatus status = system.healthCheck();
        assertFalse(status.isLeader());
    }

    @Test
    void testGetClusterMetricsNotNull() {
        ClusterMetrics metrics = system.getClusterMetrics();
        assertNotNull(metrics);
        assertEquals(SYSTEM_ID, metrics.getNodeId());
    }

    // --- Helper infrastructure ---

    private static class NoOpMessagingSystem implements MessagingSystem {
        @Override
        public <Message> CompletableFuture<Void> sendMessage(String targetSystemId, String actorId, Message message) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> registerMessageHandler(MessageHandler handler) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> start() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> stop() {
            return CompletableFuture.completedFuture(null);
        }
    }

    private static class InMemoryMetadataStore implements MetadataStore {

        private final ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Lock> locks = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, KeyWatcher> watchers = new ConcurrentHashMap<>();

        @Override
        public CompletableFuture<Void> put(String key, String value) {
            return CompletableFuture.runAsync(() -> {
                store.put(key, value);
                for (Map.Entry<String, KeyWatcher> entry : watchers.entrySet()) {
                    if (key.startsWith(entry.getKey())) {
                        entry.getValue().onPut(key, value);
                    }
                }
            });
        }

        @Override
        public CompletableFuture<Optional<String>> get(String key) {
            return CompletableFuture.supplyAsync(() -> Optional.ofNullable(store.get(key)));
        }

        @Override
        public CompletableFuture<Void> delete(String key) {
            return CompletableFuture.runAsync(() -> {
                store.remove(key);
                for (Map.Entry<String, KeyWatcher> entry : watchers.entrySet()) {
                    if (key.startsWith(entry.getKey())) {
                        entry.getValue().onDelete(key);
                    }
                }
            });
        }

        @Override
        public CompletableFuture<List<String>> listKeys(String prefix) {
            return CompletableFuture.supplyAsync(() ->
                    store.keySet().stream()
                            .filter(key -> key.startsWith(prefix))
                            .toList()
            );
        }

        @Override
        public CompletableFuture<Optional<Lock>> acquireLock(String lockName, long ttlSeconds) {
            return CompletableFuture.supplyAsync(() -> {
                if (locks.containsKey(lockName)) {
                    return Optional.empty();
                }
                InMemoryLock lock = new InMemoryLock(lockName);
                locks.put(lockName, lock);
                return Optional.of(lock);
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
            return CompletableFuture.completedFuture(null);
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
