package com.cajunsystems.example.agents.llm;

import java.util.List;
import java.util.Map;

/**
 * Typed representation of the Anthropic messages array.
 *
 * <ul>
 *   <li>{@code User} – a user turn (plain text prompt or follow-up)</li>
 *   <li>{@code Assistant} – an assistant turn, optionally containing tool_use blocks</li>
 *   <li>{@code ToolResults} – a synthetic user turn that carries tool_result blocks</li>
 * </ul>
 */
public sealed interface LlmMessage permits LlmMessage.User, LlmMessage.Assistant, LlmMessage.ToolResults {

    record User(String text) implements LlmMessage {}

    record Assistant(String text, List<ToolCall> toolCalls) implements LlmMessage {}

    record ToolResults(List<ToolResult> results) implements LlmMessage {}

    // --------------- nested value types ---------------

    record ToolCall(String id, String name, Map<String, Object> input) {}

    record ToolResult(String toolCallId, String content) {}
}
