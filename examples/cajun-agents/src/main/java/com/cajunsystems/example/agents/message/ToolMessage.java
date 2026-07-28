package com.cajunsystems.example.agents.message;

import com.cajunsystems.Pid;

import java.util.Map;

/**
 * Messages sent to tool actors.
 *
 * <p>Tool actors receive an {@code Invoke}, do their work, then reply to
 * {@code callbackPid} with an {@link AgentRequest.ToolResult}.
 */
public sealed interface ToolMessage permits ToolMessage.Invoke {

    record Invoke(
            String requestId,           // propagated from the originating AgentRequest.Run
            String toolCallId,          // LLM-assigned id for this tool_use block
            Map<String, Object> args,   // tool arguments from the LLM
            Pid callbackPid             // agent actor waiting for this result
    ) implements ToolMessage {}
}
