package com.cajunsystems.example.agents.agent;

import com.cajunsystems.example.agents.core.BaseAgentHandler;
import com.cajunsystems.example.agents.llm.LlmProvider;
import com.cajunsystems.example.agents.llm.ToolDef;
import com.cajunsystems.spring.CajunActorRegistry;

import java.util.List;
import java.util.Map;

/**
 * A general-purpose coordinator agent that can:
 * <ul>
 *   <li>Search the web directly ({@code web_search})</li>
 *   <li>Evaluate math ({@code calculator})</li>
 *   <li>Delegate deep research to the {@link ResearcherAgent} via
 *       {@code delegate_research} — powered by
 *       {@link com.cajunsystems.example.agents.tool.DelegateResearchHandler}</li>
 * </ul>
 *
 * <p>The {@code delegate_research} tool demonstrates <b>agent-to-agent composition</b>:
 * from the coordinator's perspective it is just another tool actor; under the hood
 * that actor spawns an ephemeral reply-collector, tells the researcher agent, and
 * blocks the virtual thread until the response arrives.  No shared state, no callbacks,
 * no complex orchestration framework needed.
 */
public class CoordinatorAgent extends BaseAgentHandler {

    public CoordinatorAgent(LlmProvider llm, CajunActorRegistry registry) {
        super(llm, registry);
    }

    @Override
    protected String systemPrompt() {
        return """
                You are a general-purpose coordinator.
                - For complex research questions use delegate_research to get a thorough answer
                  from the specialist researcher agent.
                - For quick facts or lookups use web_search directly.
                - For arithmetic use calculator.
                - Synthesise all information into a comprehensive, well-structured answer.
                """;
    }

    @Override
    protected List<ToolDef> tools() {
        return List.of(
                new ToolDef(
                        "delegate_research",
                        "Delegate a complex research question to the specialist researcher agent.",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "question", Map.of("type", "string",
                                                "description", "The research question to investigate")),
                                "required", List.of("question"))),
                new ToolDef(
                        "web_search",
                        "Quick web search for facts or current information.",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "query", Map.of("type", "string",
                                                "description", "The search query")),
                                "required", List.of("query"))),
                new ToolDef(
                        "calculator",
                        "Evaluate a mathematical expression.",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "expression", Map.of("type", "string",
                                                "description", "A math expression")),
                                "required", List.of("expression")))
        );
    }

    @Override
    protected Map<String, String> toolActorIds() {
        return Map.of(
                "delegate_research", "delegate-research-tool",
                "web_search", "web-search-tool",
                "calculator", "calculator-tool"
        );
    }
}
