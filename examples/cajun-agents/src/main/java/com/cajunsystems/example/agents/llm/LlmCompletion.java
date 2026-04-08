package com.cajunsystems.example.agents.llm;

import java.util.List;

/** The outcome of one LLM call: either final text or a request to call tools. */
public sealed interface LlmCompletion permits LlmCompletion.Text, LlmCompletion.ToolUse {

    record Text(String content) implements LlmCompletion {}

    record ToolUse(List<LlmMessage.ToolCall> calls) implements LlmCompletion {}
}
