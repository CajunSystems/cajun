package com.cajunsystems.examples.cjent.messages;

import com.cajunsystems.Pid;
import java.io.Serializable;

public sealed interface AgentMessage extends Serializable {
    record Prompt(String text) implements AgentMessage {}
    record LLMResponse(String responseText, String suggestedTool, String toolInput) implements AgentMessage {}
    record ToolResult(String toolName, String result) implements AgentMessage {}
    record TryThink() implements AgentMessage {}
}
