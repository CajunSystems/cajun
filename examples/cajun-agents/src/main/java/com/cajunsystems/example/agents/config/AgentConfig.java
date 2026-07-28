package com.cajunsystems.example.agents.config;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.example.agents.agent.CoordinatorAgent;
import com.cajunsystems.example.agents.agent.ResearcherAgent;
import com.cajunsystems.example.agents.core.AgentState;
import com.cajunsystems.example.agents.llm.AnthropicLlmProvider;
import com.cajunsystems.example.agents.llm.DemoLlmProvider;
import com.cajunsystems.example.agents.llm.LlmProvider;
import com.cajunsystems.spring.CajunActorRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires together the LLM provider and spawns the two stateful agent actors.
 *
 * <h3>LLM provider selection</h3>
 * Set {@code ANTHROPIC_API_KEY} in the environment (or {@code anthropic.api-key}
 * in {@code application.yml}) to use real Claude.  Without it the built-in
 * {@link DemoLlmProvider} runs a scripted tool-use flow so you can exercise every
 * actor without an API key.
 *
 * <h3>Why @Bean instead of @ActorComponent for agents?</h3>
 * {@code @ActorComponent} is for <em>stateless</em> {@code Handler<M>} beans.
 * Agents are <em>stateful</em> actors — they need an initial {@link AgentState}
 * and constructor-injected dependencies ({@link LlmProvider}, {@link CajunActorRegistry}).
 * Manual {@code @Bean} spawn gives full control over these parameters.
 */
@Configuration
public class AgentConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentConfig.class);

    @Bean
    public LlmProvider llmProvider(
            @Value("${anthropic.api-key:demo}") String apiKey,
            ObjectMapper objectMapper) {
        if ("demo".equals(apiKey)) {
            log.warn("ANTHROPIC_API_KEY not set — using DemoLlmProvider (no real LLM calls)");
            return new DemoLlmProvider();
        }
        String model = "claude-opus-4-6";
        log.info("Using AnthropicLlmProvider with model {}", model);
        return new AnthropicLlmProvider(apiKey, model, objectMapper);
    }

    @Bean
    public Pid researcherAgent(ActorSystem system, LlmProvider llm, CajunActorRegistry registry) {
        var handler = new ResearcherAgent(llm, registry);
        Pid pid = system.statefulActorOf(handler, AgentState.initial())
                .withId("researcher-agent")
                .spawn();
        registry.register(ResearcherAgent.class, "researcher-agent", pid);
        log.info("Spawned researcher-agent");
        return pid;
    }

    @Bean
    public Pid coordinatorAgent(ActorSystem system, LlmProvider llm, CajunActorRegistry registry) {
        var handler = new CoordinatorAgent(llm, registry);
        Pid pid = system.statefulActorOf(handler, AgentState.initial())
                .withId("coordinator-agent")
                .spawn();
        registry.register(CoordinatorAgent.class, "coordinator-agent", pid);
        log.info("Spawned coordinator-agent");
        return pid;
    }
}
