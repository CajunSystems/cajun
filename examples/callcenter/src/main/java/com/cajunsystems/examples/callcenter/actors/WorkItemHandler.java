package com.cajunsystems.examples.callcenter.actors;

import com.cajunsystems.ActorContext;
import com.cajunsystems.Pid;
import com.cajunsystems.handler.StatefulHandler;
import com.cajunsystems.examples.callcenter.messages.WorkItemMessage;
import com.cajunsystems.examples.callcenter.messages.QueueMessage;
import com.cajunsystems.examples.callcenter.messages.AgentMessage;
import com.cajunsystems.examples.callcenter.states.WorkItemState;

import java.util.concurrent.TimeUnit;

import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Call;

public class WorkItemHandler implements StatefulHandler<WorkItemState, WorkItemMessage> {

    private final Pid queueId;

    public WorkItemHandler(Pid queueId) {
        this.queueId = queueId;
    }

    @Override
    public WorkItemState receive(WorkItemMessage message, WorkItemState state, ActorContext context) {
        switch (message) {
            case WorkItemMessage.StartCall s -> {
                state.setCallSid(s.callSid());
                state.setStatus(WorkItemState.Status.WAITING);
                context.getLogger().info("WorkItem ({} | Sid: {}): Call started. Enqueuing into Queue...", state.getCallerId(), state.getCallSid());
                context.tell(queueId, new QueueMessage.Enqueue(context.self(), state.getCallerId()));
            }
            case WorkItemMessage.AgentAssigned msg -> {
                state.setStatus(WorkItemState.Status.CONNECTED);
                state.setAssignedAgent(msg.agentId());
                context.getLogger().info("WorkItem ({}): Connected to agent {}", state.getCallerId(), msg.agentName());
                
                try {
                    // MOCK: This acts as the bridge connecting the live caller to the Agent's WebRTC device.
                    // If Twilio.init() is set up, this makes an actual outbound REST request to bridge the call.
                    if (state.getCallSid() != null && !state.getCallSid().startsWith("mock-")) {
                         context.getLogger().info(">> (Twilio Outbound) Bridging CallSid: {} to Agent: {}", state.getCallSid(), msg.agentName());
                         // Call call = Call.updater(state.getCallSid()).setUrl("https://your-server.com/agent/" + msg.agentName()).update();
                    }
                } catch (Exception e) {
                    context.getLogger().error("Twilio API failed", e);
                }
                
                // For the sake of the simulation, still trigger EndCall later if it's the internal runner
                if (state.getCallSid() != null && state.getCallSid().startsWith("mock-")) {
                     context.tellSelf(new WorkItemMessage.EndCall(), 4, TimeUnit.SECONDS);
                }
            }
            case WorkItemMessage.EndCall e -> {
                if (state.getStatus() == WorkItemState.Status.ENDED) return state;
                if (state.getStatus() == WorkItemState.Status.WAITING) {
                   context.getLogger().debug("WorkItem ({}): Ignoring delayed EndCall because call was transferred back to queue.", state.getCallerId());
                   return state;
                }
                
                state.setStatus(WorkItemState.Status.ENDED);
                context.getLogger().info("WorkItem ({}): Call Ended.", state.getCallerId());
                context.stop(); 
            }
            case WorkItemMessage.TransferToQueue transfer -> {
                context.getLogger().info("WorkItem ({}): Handling COLD transfer request. Re-enqueuing...", state.getCallerId());
                state.setStatus(WorkItemState.Status.WAITING);
                state.setAssignedAgent(null);
                
                if (transfer.priority()) {
                    context.tell(transfer.queueId(), new QueueMessage.PriorityEnqueue(context.self(), state.getCallerId()));
                } else {
                    context.tell(transfer.queueId(), new QueueMessage.Enqueue(context.self(), state.getCallerId()));
                }
            }
            case WorkItemMessage.TransferToAgent transfer -> {
                context.getLogger().info("WorkItem ({}): Handling WARM transfer request to agent {}", state.getCallerId(), transfer.targetAgent().actorId());
                state.setAssignedAgent(transfer.targetAgent());
                
                // Directly assign the call to the target agent
                context.tell(transfer.targetAgent(), new AgentMessage.AssignCall(context.self(), state.getCallerId()));
            }
        }
        return state;
    }
}
