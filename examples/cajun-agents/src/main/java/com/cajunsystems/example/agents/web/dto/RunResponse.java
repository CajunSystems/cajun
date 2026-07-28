package com.cajunsystems.example.agents.web.dto;

/** HTTP response body for POST /agents/{agentId}/run */
public record RunResponse(String requestId, String status, String content, String error) {}
