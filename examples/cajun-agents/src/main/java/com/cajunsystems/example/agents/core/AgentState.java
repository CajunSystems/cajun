package com.cajunsystems.example.agents.core;

import java.util.HashMap;
import java.util.Map;

/**
 * Persistent state of an agent actor.
 *
 * <p>An agent can handle multiple concurrent requests (each identified by a
 * {@code requestId}).  The actor processes messages sequentially, but tool
 * calls fan out to tool actors in parallel, so several requests can each be
 * waiting for tool results at the same time.
 */
public record AgentState(Map<String, PendingRun> pendingRuns) {

    public static AgentState initial() {
        return new AgentState(Map.of());
    }

    public AgentState addPending(PendingRun run) {
        var map = new HashMap<>(pendingRuns);
        map.put(run.requestId(), run);
        return new AgentState(Map.copyOf(map));
    }

    public AgentState updatePending(PendingRun run) {
        return addPending(run);   // same operation
    }

    public AgentState removePending(String requestId) {
        var map = new HashMap<>(pendingRuns);
        map.remove(requestId);
        return new AgentState(Map.copyOf(map));
    }
}
