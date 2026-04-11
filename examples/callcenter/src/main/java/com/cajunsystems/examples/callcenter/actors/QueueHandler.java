package com.cajunsystems.examples.callcenter.actors;

import com.cajunsystems.ActorContext;
import com.cajunsystems.Pid;
import com.cajunsystems.handler.StatefulHandler;
import com.cajunsystems.examples.callcenter.messages.QueueMessage;
import com.cajunsystems.examples.callcenter.messages.AgentMessage;
import com.cajunsystems.examples.callcenter.messages.WorkItemMessage;
import com.cajunsystems.examples.callcenter.states.QueueState;

public class QueueHandler implements StatefulHandler<QueueState, QueueMessage> {

    @Override
    public QueueState receive(QueueMessage message, QueueState state, ActorContext context) {
        switch (message) {
            case QueueMessage.RegisterAgent reg -> {
                context.getLogger().info("Queue: Registered agent {}", reg.agentName());
                state.registerAgentName(reg.agentId(), reg.agentName());
                state.markAgentAvailable(reg.agentId());
                tryMatch(state, context);
            }
            case QueueMessage.Enqueue enc -> {
                context.getLogger().info("Queue: Enqueued call from {}", enc.callerId());
                state.addCall(enc.workItemId(), enc.callerId());
                tryMatch(state, context);
            }
            case QueueMessage.PriorityEnqueue pri -> {
                context.getLogger().info("Queue: PRIORITY Enqueued call from {} (Transferred)", pri.callerId());
                state.addPriorityCall(pri.workItemId(), pri.callerId());
                tryMatch(state, context);
            }
            case QueueMessage.AgentAvailable avail -> {
                String agentName = state.getAgentName(avail.agentId());
                if (!state.isAgentAvailable(avail.agentId())) {
                    context.getLogger().info("Queue: Agent {} identified as Available", agentName);
                    state.markAgentAvailable(avail.agentId());
                    tryMatch(state, context);
                }
            }
            case QueueMessage.AgentBusy busy -> {
                state.markAgentBusy(busy.agentId());
            }
        }
        return state;
    }

    private void tryMatch(QueueState state, ActorContext context) {
        while (state.hasAvailableAgents() && state.hasWaitingCalls()) {
            Pid agent = state.pollAvailableAgent();
            QueueState.QueueItem call = state.pollNextCall();

            context.getLogger().info("Queue: Matching Call {} to Agent {}", call.callerId(), state.getAgentName(agent));
            
            // Assign call to Agent
            context.tell(agent, new AgentMessage.AssignCall(call.workItemId(), call.callerId()));
            
            // Notify the WorkItem
            context.tell(call.workItemId(), new WorkItemMessage.AgentAssigned(agent, state.getAgentName(agent)));
        }
    }
}
