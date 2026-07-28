package com.cajunsystems.example.agents.llm;

import java.util.List;

/**
 * Abstraction over an LLM API.
 *
 * <p>Two implementations ship with this example:
 * <ul>
 *   <li>{@link AnthropicLlmProvider} – calls the real Anthropic Messages API</li>
 *   <li>{@link DemoLlmProvider} – deterministic stub that exercises the full
 *       tool-use flow without needing an API key</li>
 * </ul>
 *
 * The active implementation is selected in {@code AgentConfig} based on whether
 * {@code ANTHROPIC_API_KEY} is set.
 */
public interface LlmProvider {

    /**
     * Synchronously completes a conversation turn.
     *
     * <p>Implementations are expected to be called from a virtual-thread
     * context (Cajun actors default to virtual threads), so blocking HTTP
     * calls are fine.
     *
     * @param systemPrompt the system prompt for the model
     * @param history      the conversation so far
     * @param tools        tool definitions available for this turn
     * @return {@link LlmCompletion.Text} with the final answer, or
     *         {@link LlmCompletion.ToolUse} with tool calls to dispatch
     */
    LlmCompletion complete(String systemPrompt, List<LlmMessage> history, List<ToolDef> tools);
}
