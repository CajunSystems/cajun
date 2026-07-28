package com.cajunsystems.example.agents.llm;

import java.util.Map;

/**
 * Describes a tool that the LLM may call.
 *
 * <p>{@code inputSchema} is a JSON-Schema object (as a plain Java {@code Map})
 * that Anthropic uses to validate the model's tool_use input.
 */
public record ToolDef(String name, String description, Map<String, Object> inputSchema) {}
