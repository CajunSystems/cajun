package com.cajunsystems.examples.cjent.llm;

import com.cajunsystems.ActorContext;
import com.cajunsystems.handler.Handler;
import com.cajunsystems.examples.cjent.messages.*;

public class MockLLMHandler implements Handler<LLMMessage> {

    @Override
    public void receive(LLMMessage msg, ActorContext ctx) {
        if (msg instanceof LLMMessage.AskLLM ask) {
            ctx.getLogger().info("LLM reasoning Engine starting analysis...");
            
            // Simulate LLM inference time
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {}
            
            String history = ask.context().toLowerCase();
            AgentMessage.LLMResponse response;
            
            // Simple deterministic mocking logic based on context
            if (history.contains("calculate") && !history.contains("return: 42")) {
                response = new AgentMessage.LLMResponse(null, "calculator", "equation");
            } else if (history.contains("weather") && !history.contains("sunny")) {
                response = new AgentMessage.LLMResponse(null, "weather", "London");
            } else {
                // If tools were used, provide a summary answer
                if (history.contains("return: 42")) {
                    response = new AgentMessage.LLMResponse("I have computed the result. It is 42.", null, null);
                } else if (history.contains("return: sunny")) {
                    response = new AgentMessage.LLMResponse("The weather is currently Sunny and 75F.", null, null);
                } else {
                    response = new AgentMessage.LLMResponse("Hello! I am ready to help you with calculations and weather.", null, null);
                }
            }
            
            // Send back the strategy to the Agent
            ctx.tell(ask.replyToAgent(), response);
        }
    }
}
