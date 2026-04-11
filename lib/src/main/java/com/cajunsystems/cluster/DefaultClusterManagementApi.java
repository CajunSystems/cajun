package com.cajunsystems.cluster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

class DefaultClusterManagementApi implements ClusterManagementApi {

    private static final Logger logger = LoggerFactory.getLogger(DefaultClusterManagementApi.class);

    private final ClusterActorSystem system;
    private final MetadataStore metadataStore;

    DefaultClusterManagementApi(ClusterActorSystem system) {
        this.system = system;
        this.metadataStore = system.getMetadataStore();
    }

    @Override
    public CompletableFuture<Set<String>> listNodes() {
        return metadataStore.listKeys(ClusterActorSystem.NODE_PREFIX)
                .thenApply(keys -> keys.stream()
                        .map(k -> k.substring(ClusterActorSystem.NODE_PREFIX.length()))
                        .collect(Collectors.toSet()));
    }

    @Override
    public CompletableFuture<Set<String>> listActors(String nodeId) {
        return metadataStore.listKeys(ClusterActorSystem.ACTOR_ASSIGNMENT_PREFIX)
                .thenCompose(keys -> {
                    // For each actor key, fetch its assigned node and filter by nodeId
                    List<CompletableFuture<Optional<String>>> futures = keys.stream()
                            .map(key -> metadataStore.get(key)
                                    .thenApply(optVal -> optVal
                                            .filter(nodeId::equals)
                                            .map(ignored -> key.substring(
                                                    ClusterActorSystem.ACTOR_ASSIGNMENT_PREFIX.length()))))
                            .collect(Collectors.toList());

                    return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                            .thenApply(v -> futures.stream()
                                    .map(CompletableFuture::join)
                                    .filter(Optional::isPresent)
                                    .map(Optional::get)
                                    .collect(Collectors.toSet()));
                });
    }

    @Override
    public CompletableFuture<Void> migrateActor(String actorId, String targetNodeId) {
        // Validate target against the metadata store (authoritative view) rather than
        // the in-memory knownNodes cache, which may be empty before start() is called.
        return listNodes()
                .thenCompose(knownNodes -> {
                    if (!knownNodes.contains(targetNodeId)) {
                        return CompletableFuture.failedFuture(
                                new IllegalArgumentException(
                                        "Target node '" + targetNodeId
                                                + "' is not a known cluster node"));
                    }
                    return metadataStore.get(ClusterActorSystem.ACTOR_ASSIGNMENT_PREFIX + actorId)
                            .thenCompose(currentAssignment -> {
                                String sourceNodeId = currentAssignment.orElse(null);

                                // Update the metadata store assignment
                                return metadataStore.put(
                                        ClusterActorSystem.ACTOR_ASSIGNMENT_PREFIX + actorId,
                                        targetNodeId)
                                        .thenRun(() -> {
                                            // Invalidate local TTL cache immediately
                                            system.invalidateActorAssignmentCache(actorId);

                                            // If the actor is running locally, stop it so its
                                            // state is persisted and lazily recovered on the target
                                            if (system.getSystemId().equals(sourceNodeId)) {
                                                system.shutdown(actorId);
                                            }
                                        });
                            });
                });
    }

    @Override
    public CompletableFuture<Void> drainNode(String nodeId) {
        return listNodes()
                .thenCompose(allNodes -> {
                    Set<String> remainingNodes = new HashSet<>(allNodes);
                    remainingNodes.remove(nodeId);

                    if (remainingNodes.isEmpty()) {
                        return CompletableFuture.failedFuture(
                                new IllegalStateException(
                                        "Cannot drain node '" + nodeId
                                                + "': no other nodes available to receive actors"));
                    }

                    return listActors(nodeId)
                            .thenCompose(actorIds -> {
                                if (actorIds.isEmpty()) {
                                    return CompletableFuture.completedFuture(null);
                                }

                                List<CompletableFuture<Void>> migrations = actorIds.stream()
                                        .map(actorId -> {
                                            Optional<String> targetOpt =
                                                    RendezvousHashing.assignKey(actorId, remainingNodes);
                                            if (targetOpt.isEmpty()) {
                                                return CompletableFuture.<Void>completedFuture(null);
                                            }
                                            return migrateActor(actorId, targetOpt.get())
                                                    .exceptionally(ex -> {
                                                        logger.warn(
                                                                "Failed to migrate actor '{}' during drain of '{}': {}",
                                                                actorId, nodeId, ex.getMessage());
                                                        return null;
                                                    });
                                        })
                                        .collect(Collectors.toList());

                                return CompletableFuture.allOf(
                                        migrations.toArray(new CompletableFuture[0]));
                            });
                });
    }
}
