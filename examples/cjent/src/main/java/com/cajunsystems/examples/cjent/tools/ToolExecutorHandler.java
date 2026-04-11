package com.cajunsystems.examples.cjent.tools;

import com.cajunsystems.ActorContext;
import com.cajunsystems.handler.Handler;
import com.cajunsystems.examples.cjent.messages.*;

public class ToolExecutorHandler implements Handler<ToolMessage> {

    @Override
    public void receive(ToolMessage msg, ActorContext ctx) {
        if (msg instanceof ToolMessage.ExecuteTool req) {
            ctx.getLogger().info("Executing tool [{}] with input [{}]", req.toolName(), req.input());
            
            // Simulate external blocking I/O (Virtual threads handle this efficiently!)
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {}
            
            String result = "Tool Execution Failed";
            if ("calculator".equalsIgnoreCase(req.toolName())) {
                result = "42"; // Mocked computation result
            } else if ("weather".equalsIgnoreCase(req.toolName())) {
                result = "Sunny, 75F";
            }
            
            // Send the result back to the Agent
            ctx.tell(req.replyToAgent(), new AgentMessage.ToolResult(req.toolName(), result));
        }
    }
}
