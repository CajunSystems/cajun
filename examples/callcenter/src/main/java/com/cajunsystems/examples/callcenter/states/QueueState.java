package com.cajunsystems.examples.callcenter.states;

import com.cajunsystems.Pid;
import java.util.LinkedList;
import java.util.Queue;
import java.util.HashMap;
import java.util.Map;

import java.io.Serializable;

public class QueueState implements Serializable {
    private final java.util.LinkedList<QueueItem> waitingCalls = new java.util.LinkedList<>();
    private final java.util.Queue<Pid> availableAgents = new java.util.LinkedList<>();
    private final Map<Pid, String> agentNames = new HashMap<>();

    public void addCall(Pid workItemId, String callerId) {
        waitingCalls.add(new QueueItem(workItemId, callerId));
    }

    public void addPriorityCall(Pid workItemId, String callerId) {
        waitingCalls.addFirst(new QueueItem(workItemId, callerId));
    }

    public QueueItem pollNextCall() {
        return waitingCalls.poll();
    }

    public void markAgentAvailable(Pid agentId) {
        if (!availableAgents.contains(agentId)) {
            availableAgents.add(agentId);
        }
    }

    public void markAgentBusy(Pid agentId) {
        availableAgents.remove(agentId);
    }

    public Pid pollAvailableAgent() {
        return availableAgents.poll();
    }

    public boolean hasAvailableAgents() {
        return !availableAgents.isEmpty();
    }

    public boolean hasWaitingCalls() {
        return !waitingCalls.isEmpty();
    }

    public boolean isAgentAvailable(Pid agentId) {
        return availableAgents.contains(agentId);
    }
    
    public void registerAgentName(Pid agentId, String name) {
        agentNames.put(agentId, name);
    }
    
    public String getAgentName(Pid agentId) {
        return agentNames.getOrDefault(agentId, "Unknown Agent");
    }

    public record QueueItem(Pid workItemId, String callerId) implements java.io.Serializable {}
}
