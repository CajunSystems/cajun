package com.cajunsystems.example.agents.core;

import com.cajunsystems.Pid;
import com.cajunsystems.example.agents.llm.LlmMessage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Tracks a single in-flight agent request that is waiting for one or more tool results.
 *
 * <p>Agents are stateful actors that process one message at a time.  When the LLM
 * returns a {@code tool_use} stop reason the agent dispatches messages to tool actors
 * and records the outstanding calls here.  As results arrive they are accumulated;
 * when {@link #isComplete()} the agent resumes the LLM conversation.
 */
public record PendingRun(
        String requestId,
        Pid replyTo,
        List<LlmMessage> history,                       // conversation up to (and including) the assistant tool-use turn
        Map<String, LlmMessage.ToolCall> pendingCalls,  // toolCallId -> call metadata
        Map<String, String> receivedResults             // toolCallId -> result text
) {

    /** Create a new pending run from a list of outstanding tool calls. */
    public static PendingRun of(String requestId, Pid replyTo,
                                List<LlmMessage> history,
                                List<LlmMessage.ToolCall> calls) {
        Map<String, LlmMessage.ToolCall> callsById = calls.stream()
                .collect(Collectors.toMap(LlmMessage.ToolCall::id, c -> c));
        return new PendingRun(requestId, replyTo, history,
                Map.copyOf(callsById), Map.of());
    }

    /** True when every outstanding tool call has a result. */
    public boolean isComplete() {
        return receivedResults.size() == pendingCalls.size();
    }

    /** Return a copy with one more tool result recorded. */
    public PendingRun withResult(String toolCallId, String result) {
        var updated = new HashMap<>(receivedResults);
        updated.put(toolCallId, result);
        return new PendingRun(requestId, replyTo, history, pendingCalls, Map.copyOf(updated));
    }

    /** Convert accumulated results into the format the LLM expects for the next turn. */
    public List<LlmMessage.ToolResult> toToolResults() {
        return pendingCalls.keySet().stream()
                .map(id -> new LlmMessage.ToolResult(id, receivedResults.getOrDefault(id, "")))
                .toList();
    }
}
