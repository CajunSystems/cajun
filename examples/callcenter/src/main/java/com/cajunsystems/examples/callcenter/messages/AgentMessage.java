package com.cajunsystems.examples.callcenter.messages;

import com.cajunsystems.Pid;

import java.io.Serializable;

public sealed interface AgentMessage extends Serializable {
    record AssignCall(Pid workItemId, String callerId) implements AgentMessage {}
    record FinishCall() implements AgentMessage {}
    record WrapUpComplete() implements AgentMessage {}
    record TransferCold() implements AgentMessage {}
    record TransferWarm(Pid targetAgent) implements AgentMessage {}
}
