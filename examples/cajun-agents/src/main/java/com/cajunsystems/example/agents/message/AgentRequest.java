package com.cajunsystems.example.agents.message;

import com.cajunsystems.Pid;

/**
 * Messages sent to agent actors.
 *
 * <p>Run kicks off a new request; ToolResult delivers a tool actor's response
 * back to the originating agent so it can continue the LLM conversation.
 */
public sealed interface AgentRequest permits AgentRequest.Run, AgentRequest.ToolResult {

    /** Start a new agent turn with a user-supplied prompt. */
    record Run(
            String requestId,
            String prompt,
            Pid replyTo          // where to deliver AgentResponse when done
    ) implements AgentRequest {}

    /** A tool actor has finished and is returning its result. */
    record ToolResult(
            String requestId,    // matches the Run that triggered the tool call
            String toolCallId,   // matches the specific tool_use block from the LLM
            String toolName,     // for logging / observability
            String result        // text result to feed back to the LLM
    ) implements AgentRequest {}
}
