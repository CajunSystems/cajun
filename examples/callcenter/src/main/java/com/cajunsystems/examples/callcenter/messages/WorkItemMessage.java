package com.cajunsystems.examples.callcenter.messages;

import com.cajunsystems.Pid;

import java.io.Serializable;

public sealed interface WorkItemMessage extends Serializable {
    record StartCall(String callSid) implements WorkItemMessage {}
    record AgentAssigned(Pid agentId, String agentName) implements WorkItemMessage {}
    record EndCall() implements WorkItemMessage {}
    record TransferToQueue(Pid queueId, boolean priority) implements WorkItemMessage {}
    record TransferToAgent(Pid targetAgent) implements WorkItemMessage {}
}
