package com.cajunsystems.example.agents.message;

/** Final outcome sent by an agent back to the caller's reply actor. */
public sealed interface AgentResponse permits AgentResponse.Success, AgentResponse.Error {

    record Success(String requestId, String content) implements AgentResponse {}

    record Error(String requestId, String message) implements AgentResponse {}
}
