package com.cajunsystems.examples.callcenter.messages;

import com.cajunsystems.Pid;

import java.io.Serializable;

public sealed interface QueueMessage extends Serializable {
    record RegisterAgent(Pid agentId, String agentName) implements QueueMessage {}
    record Enqueue(Pid workItemId, String callerId) implements QueueMessage {}
    record PriorityEnqueue(Pid workItemId, String callerId) implements QueueMessage {}
    record AgentAvailable(Pid agentId) implements QueueMessage {}
    record AgentBusy(Pid agentId) implements QueueMessage {}
}
