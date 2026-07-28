package com.cajunsystems.examples.callcenter.actors;

import com.cajunsystems.ActorContext;
import com.cajunsystems.Pid;
import com.cajunsystems.handler.StatefulHandler;
import com.cajunsystems.examples.callcenter.messages.AgentMessage;
import com.cajunsystems.examples.callcenter.messages.QueueMessage;
import com.cajunsystems.examples.callcenter.messages.WorkItemMessage;
import com.cajunsystems.examples.callcenter.states.AgentState;

import java.util.concurrent.TimeUnit;

public class AgentHandler implements StatefulHandler<AgentState, AgentMessage> {

    private final Pid queueId;

    public AgentHandler(Pid queueId) {
        this.queueId = queueId;
    }

    @Override
    public AgentState receive(AgentMessage message, AgentState state, ActorContext context) {
        switch (message) {
            case AgentMessage.AssignCall assign -> {
                if (state.getStatus() != AgentState.Status.BUSY) {
                    context.getLogger().info("Agent {}: Picked up call from {}", state.getName(), assign.callerId());
                    state.setStatus(AgentState.Status.BUSY);
                    state.setActiveWorkItem(assign.workItemId());
                    // Tell the work item we are connected
                    context.tell(assign.workItemId(), new WorkItemMessage.AgentAssigned(context.self(), state.getName()));
                } else {
                    context.getLogger().warn("Agent {}: Received call but already BUSY!", state.getName());
                }
            }
            case AgentMessage.FinishCall finish -> {
                state.incrementCallsHandled();
                context.getLogger().info("Agent {}: Finished call. Handling wrap up... Total calls handled: {}", state.getName(), state.getCallsHandled());
                state.setStatus(AgentState.Status.WRAP_UP);
                state.setActiveWorkItem(null);
                // Simulate wrap up time
                context.tellSelf(new AgentMessage.WrapUpComplete(), 2, TimeUnit.SECONDS);
            }
            case AgentMessage.WrapUpComplete w -> {
                context.getLogger().info("Agent {}: Wrap up complete. Ready for next call.", state.getName());
                state.setStatus(AgentState.Status.AVAILABLE);
                context.tell(queueId, new QueueMessage.AgentAvailable(context.self()));
            }
            case AgentMessage.TransferCold transfer -> {
                Pid workItem = state.getActiveWorkItem();
                if (workItem != null) {
                    context.getLogger().info("Agent {}: Initiating COLD transfer (re-queue)", state.getName());
                    // Notify WorkItem to re-queue itself with priority
                    context.tell(workItem, new WorkItemMessage.TransferToQueue(queueId, true));
                    state.setStatus(AgentState.Status.WRAP_UP);
                    state.setActiveWorkItem(null);
                    context.tellSelf(new AgentMessage.WrapUpComplete(), 1, TimeUnit.SECONDS);
                }
            }
            case AgentMessage.TransferWarm transfer -> {
                Pid workItem = state.getActiveWorkItem();
                if (workItem != null) {
                    context.getLogger().info("Agent {}: Initiating WARM transfer to another agent", state.getName());
                    // Notify WorkItem to hand off directly to the target agent
                    context.tell(workItem, new WorkItemMessage.TransferToAgent(transfer.targetAgent()));
                    state.setStatus(AgentState.Status.WRAP_UP);
                    state.setActiveWorkItem(null);
                    context.tellSelf(new AgentMessage.WrapUpComplete(), 1, TimeUnit.SECONDS);
                }
            }
        }
        return state;
    }
}
