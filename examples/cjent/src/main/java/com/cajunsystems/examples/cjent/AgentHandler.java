package com.cajunsystems.examples.cjent;

import com.cajunsystems.ActorContext;
import com.cajunsystems.Pid;
import com.cajunsystems.handler.StatefulHandler;
import com.cajunsystems.examples.cjent.messages.*;

public class AgentHandler implements StatefulHandler<AgentState, AgentMessage> {
    
    private final Pid llmActor;
    private final Pid toolExecutorActor;

    public AgentHandler(Pid llmActor, Pid toolExecutorActor) {
        this.llmActor = llmActor;
        this.toolExecutorActor = toolExecutorActor;
    }

    @Override
    public AgentState receive(AgentMessage message, AgentState state, ActorContext context) {
        switch (message) {
            case AgentMessage.Prompt p -> {
                context.getLogger().info("Agent received prompt: {}", p.text());
                state.addHistory("User: " + p.text());
                context.getSender().ifPresent(sender -> state.setCurrentReplyTo(sender));
                
                // Start thinking process
                context.tellSelf(new AgentMessage.TryThink());
            }
            case AgentMessage.TryThink t -> {
                context.getLogger().debug("Agent thinking about next step...");
                // Ask LLM what to do next based on history
                context.tell(llmActor, new LLMMessage.AskLLM(state.getFullHistory(), context.self()));
            }
            case AgentMessage.LLMResponse resp -> {
                if (resp.suggestedTool() != null) {
                    context.getLogger().info("LLM suggested tool: [{}] with input [{}]", resp.suggestedTool(), resp.toolInput());
                    state.addHistory("Agent Thinking: Needs to use tool " + resp.suggestedTool() + " with " + resp.toolInput());
                    context.tell(toolExecutorActor, new ToolMessage.ExecuteTool(resp.suggestedTool(), resp.toolInput(), context.self()));
                } else {
                    context.getLogger().info("LLM provided final response");
                    state.addHistory("Agent: " + resp.responseText());
                    if (state.getCurrentReplyTo() != null) {
                        context.tell(state.getCurrentReplyTo(), new AgentResponse(resp.responseText()));
                        state.setCurrentReplyTo(null);
                    }
                }
            }
            case AgentMessage.ToolResult tr -> {
                context.getLogger().info("Agent received tool result from {}: {}", tr.toolName(), tr.result());
                state.addHistory("Tool [" + tr.toolName() + "] Return: " + tr.result());
                
                // After getting a tool result, think again
                context.tellSelf(new AgentMessage.TryThink());
            }
        }
        return state; // State is mutated internally, but we return it as required by StatefulHandler
    }
}
