package com.cajunsystems.example.agents.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Calls the Anthropic Messages API using Java's built-in {@link HttpClient}.
 *
 * <p>No extra SDK dependency is required — Jackson (bundled with Spring Boot)
 * handles JSON serialization / deserialization.
 *
 * <p>Active when {@code ANTHROPIC_API_KEY} is set to a real key.
 */
public class AnthropicLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(AnthropicLlmProvider.class);
    private static final String API_URL = "https://api.anthropic.com/v1/messages";

    private final String apiKey;
    private final String model;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public AnthropicLlmProvider(String apiKey, String model, ObjectMapper mapper) {
        this.apiKey = apiKey;
        this.model = model;
        this.httpClient = HttpClient.newHttpClient();
        this.mapper = mapper;
    }

    @Override
    public LlmCompletion complete(String systemPrompt, List<LlmMessage> history, List<ToolDef> tools) {
        try {
            var body = buildRequestBody(systemPrompt, history, tools);
            log.debug("Anthropic request: {}", body);

            var request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            log.debug("Anthropic response ({}): {}", response.statusCode(), response.body());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Anthropic API error " + response.statusCode() + ": " + response.body());
            }

            return parseResponse(response.body());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("LLM call failed", e);
        }
    }

    // ------------------------------------------------------------------ serialization

    private String buildRequestBody(String systemPrompt, List<LlmMessage> history, List<ToolDef> tools)
            throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", 4096);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            root.put("system", systemPrompt);
        }

        ArrayNode messages = root.putArray("messages");
        for (LlmMessage msg : history) {
            ObjectNode msgNode = messages.addObject();
            switch (msg) {
                case LlmMessage.User user -> {
                    msgNode.put("role", "user");
                    msgNode.put("content", user.text());
                }
                case LlmMessage.Assistant assistant -> {
                    msgNode.put("role", "assistant");
                    if (assistant.toolCalls() == null || assistant.toolCalls().isEmpty()) {
                        msgNode.put("content", assistant.text() != null ? assistant.text() : "");
                    } else {
                        ArrayNode content = msgNode.putArray("content");
                        if (assistant.text() != null && !assistant.text().isBlank()) {
                            content.addObject().put("type", "text").put("text", assistant.text());
                        }
                        for (LlmMessage.ToolCall tc : assistant.toolCalls()) {
                            ObjectNode tcNode = content.addObject();
                            tcNode.put("type", "tool_use");
                            tcNode.put("id", tc.id());
                            tcNode.put("name", tc.name());
                            tcNode.set("input", mapper.valueToTree(tc.input()));
                        }
                    }
                }
                case LlmMessage.ToolResults toolResults -> {
                    msgNode.put("role", "user");
                    ArrayNode content = msgNode.putArray("content");
                    for (LlmMessage.ToolResult tr : toolResults.results()) {
                        ObjectNode trNode = content.addObject();
                        trNode.put("type", "tool_result");
                        trNode.put("tool_use_id", tr.toolCallId());
                        trNode.put("content", tr.content());
                    }
                }
            }
        }

        if (!tools.isEmpty()) {
            ArrayNode toolsArray = root.putArray("tools");
            for (ToolDef tool : tools) {
                ObjectNode toolNode = toolsArray.addObject();
                toolNode.put("name", tool.name());
                toolNode.put("description", tool.description());
                toolNode.set("input_schema", mapper.valueToTree(tool.inputSchema()));
            }
        }

        return mapper.writeValueAsString(root);
    }

    // ------------------------------------------------------------------ deserialization

    @SuppressWarnings("unchecked")
    private LlmCompletion parseResponse(String body) throws Exception {
        JsonNode root = mapper.readTree(body);
        String stopReason = root.path("stop_reason").asText("end_turn");
        JsonNode content = root.path("content");

        if ("tool_use".equals(stopReason)) {
            List<LlmMessage.ToolCall> calls = new ArrayList<>();
            for (JsonNode block : content) {
                if ("tool_use".equals(block.path("type").asText())) {
                    Map<String, Object> input = mapper.convertValue(block.get("input"), Map.class);
                    calls.add(new LlmMessage.ToolCall(
                            block.get("id").asText(),
                            block.get("name").asText(),
                            input));
                }
            }
            return new LlmCompletion.ToolUse(calls);
        }

        // end_turn — collect all text blocks
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : content) {
            if ("text".equals(block.path("type").asText())) {
                sb.append(block.path("text").asText());
            }
        }
        return new LlmCompletion.Text(sb.toString());
    }
}
