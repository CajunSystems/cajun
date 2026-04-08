package com.cajunsystems.example.agents.web.dto;

/** HTTP request body for POST /agents/{agentId}/run */
public record RunRequest(String prompt) {}
