package com.cajunsystems.examples.cjent;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.examples.cjent.llm.MockLLMHandler;
import com.cajunsystems.examples.cjent.messages.*;
import com.cajunsystems.examples.cjent.tools.ToolExecutorHandler;

public class ExampleRunner {
    public static void main(String[] args) throws Exception {
        ActorSystem system = new ActorSystem();

        System.out.println("====== Starting Cajun Agentic AI Framework (cjent) ======");
        
        // 1. Spawn Worker Actors
        Pid llmActor = system.actorOf(MockLLMHandler.class)
                .withId("llm-brain")
                .spawn();
                
        Pid toolActor = system.actorOf(ToolExecutorHandler.class)
                .withId("tool-executor")
                .spawn();
                
        // 2. Spawn the Stateful Agent Actor
        AgentHandler agentHandler = new AgentHandler(llmActor, toolActor);
        Pid agent = system.statefulActorOf(agentHandler, new AgentState())
                .withId("cajun-ai-agent")
                .spawn();
                
        // 3. Send prompts to the Agent and WAIT for them synchronously using the unified Ask Pattern
        try {
            System.out.println("-> Sending prompt 1: Hello!");
            java.util.concurrent.CompletableFuture<AgentResponse> f1 = system.ask(agent, new AgentMessage.Prompt("Hello!"), java.time.Duration.ofSeconds(10));
            System.out.println("\n*** Final Answer from Agent: " + f1.join().text() + " ***\n");
            
            System.out.println("-> Sending prompt 2: calculate the meaning of life.");
            java.util.concurrent.CompletableFuture<AgentResponse> f2 = system.ask(agent, new AgentMessage.Prompt("calculate the meaning of life."), java.time.Duration.ofSeconds(10));
            System.out.println("\n*** Final Answer from Agent: " + f2.join().text() + " ***\n");

            System.out.println("-> Sending prompt 3: what is the weather today?");
            java.util.concurrent.CompletableFuture<AgentResponse> f3 = system.ask(agent, new AgentMessage.Prompt("what is the weather today?"), java.time.Duration.ofSeconds(10));
            System.out.println("\n*** Final Answer from Agent: " + f3.join().text() + " ***\n");
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        // 5. Shutdown the actor system
        system.shutdown();
        System.out.println("====== Shutdown Complete ======");
    }
}
