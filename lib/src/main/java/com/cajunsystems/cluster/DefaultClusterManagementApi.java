package com.cajunsystems.cluster;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

class DefaultClusterManagementApi implements ClusterManagementApi {

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
        throw new UnsupportedOperationException("migrateActor implemented in plan 30-2");
    }

    @Override
    public CompletableFuture<Void> drainNode(String nodeId) {
        throw new UnsupportedOperationException("drainNode implemented in plan 30-2");
    }
}
