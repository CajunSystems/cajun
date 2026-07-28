package com.cajunsystems.example.agents.agent;

import com.cajunsystems.example.agents.core.BaseAgentHandler;
import com.cajunsystems.example.agents.llm.LlmProvider;
import com.cajunsystems.example.agents.llm.ToolDef;
import com.cajunsystems.spring.CajunActorRegistry;

import java.util.List;
import java.util.Map;

/**
 * A focused research agent with two tools:
 * <ul>
 *   <li>{@code web_search} — dispatches to {@link com.cajunsystems.example.agents.tool.WebSearchHandler}</li>
 *   <li>{@code calculator} — dispatches to {@link com.cajunsystems.example.agents.tool.CalculatorHandler}</li>
 * </ul>
 *
 * <p>The coordinator agent can delegate to this agent via the
 * {@code delegate_research} tool, demonstrating agent-to-agent composition.
 */
public class ResearcherAgent extends BaseAgentHandler {

    public ResearcherAgent(LlmProvider llm, CajunActorRegistry registry) {
        super(llm, registry);
    }

    @Override
    protected String systemPrompt() {
        return """
                You are a precise research assistant.
                - Use web_search to look up facts, current information, or background context.
                - Use calculator for any arithmetic or mathematical computations.
                - Synthesise results into a concise, factual answer citing your tool outputs.
                """;
    }

    @Override
    protected List<ToolDef> tools() {
        return List.of(
                new ToolDef(
                        "web_search",
                        "Search the web for current information or factual background.",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "query", Map.of("type", "string",
                                                "description", "The search query")),
                                "required", List.of("query"))),
                new ToolDef(
                        "calculator",
                        "Evaluate a mathematical expression and return the result.",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "expression", Map.of("type", "string",
                                                "description", "A math expression, e.g. '(4 + 5) * 12'")),
                                "required", List.of("expression")))
        );
    }

    @Override
    protected Map<String, String> toolActorIds() {
        return Map.of(
                "web_search", "web-search-tool",
                "calculator", "calculator-tool"
        );
    }
}
