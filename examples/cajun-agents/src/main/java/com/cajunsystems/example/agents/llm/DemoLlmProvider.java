package com.cajunsystems.example.agents.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Deterministic stub LLM provider used when no {@code ANTHROPIC_API_KEY} is configured.
 *
 * <p>Exercises the full tool-use actor flow without hitting the network:
 * <ol>
 *   <li>On the first user turn it inspects keywords and returns a {@code ToolUse}
 *       completion if a matching tool is available.</li>
 *   <li>On the follow-up turn (after tool results arrive) it returns a
 *       {@code Text} completion synthesised from those results.</li>
 * </ol>
 *
 * <p>This means you can run the example and see all actors communicate without
 * an API key. Set {@code ANTHROPIC_API_KEY} in the environment to switch to
 * the real Claude model automatically.
 */
public class DemoLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(DemoLlmProvider.class);

    @Override
    public LlmCompletion complete(String systemPrompt, List<LlmMessage> history, List<ToolDef> tools) {
        LlmMessage last = history.get(history.size() - 1);
        log.debug("DemoLlmProvider: last message type = {}", last.getClass().getSimpleName());

        // After tool results come back, synthesise a final answer.
        if (last instanceof LlmMessage.ToolResults toolResults) {
            var sb = new StringBuilder();
            sb.append("[Demo] Synthesised from tool results:\n\n");
            for (var r : toolResults.results()) {
                sb.append("  • ").append(r.content()).append("\n");
            }
            sb.append("\nThis response was produced by the Cajun actor system: each tool call ")
              .append("was dispatched as an actor message and returned asynchronously.");
            return new LlmCompletion.Text(sb.toString());
        }

        // First user turn: choose a tool based on keywords (if tools available).
        if (last instanceof LlmMessage.User user && !tools.isEmpty()) {
            String prompt = user.text().toLowerCase();

            // Delegation tool takes priority to show agent-to-agent routing.
            if (hasTool(tools, "delegate_research") &&
                    (prompt.contains("research") || prompt.contains("investigate") || prompt.contains("study"))) {
                log.info("DemoLlm: choosing delegate_research");
                return new LlmCompletion.ToolUse(List.of(new LlmMessage.ToolCall(
                        "call_" + System.nanoTime(), "delegate_research",
                        Map.of("question", user.text()))));
            }

            if (hasTool(tools, "web_search") &&
                    (prompt.contains("search") || prompt.contains("find") || prompt.contains("what") ||
                     prompt.contains("who") || prompt.contains("how") || prompt.contains("tell me"))) {
                log.info("DemoLlm: choosing web_search");
                return new LlmCompletion.ToolUse(List.of(new LlmMessage.ToolCall(
                        "call_" + System.nanoTime(), "web_search",
                        Map.of("query", user.text()))));
            }

            if (hasTool(tools, "calculator") &&
                    (prompt.contains("calculat") || prompt.contains("comput") ||
                     Pattern.compile("[0-9]\\s*[+\\-*/]\\s*[0-9]").matcher(prompt).find())) {
                String expr = extractExpression(user.text());
                log.info("DemoLlm: choosing calculator with expr={}", expr);
                return new LlmCompletion.ToolUse(List.of(new LlmMessage.ToolCall(
                        "call_" + System.nanoTime(), "calculator",
                        Map.of("expression", expr))));
            }
        }

        // Default: direct response with no tool use.
        return new LlmCompletion.Text(
                "[Demo mode — set ANTHROPIC_API_KEY for real Claude responses]\n\n" +
                "I received your prompt and processed it through the Cajun actor pipeline. " +
                "Each agent is a stateful actor; tool calls are dispatched as actor messages " +
                "so multiple tools can run in parallel with natural backpressure.");
    }

    private static boolean hasTool(List<ToolDef> tools, String name) {
        return tools.stream().anyMatch(t -> t.name().equals(name));
    }

    private static String extractExpression(String text) {
        var m = Pattern.compile("[0-9][0-9+\\-*/.\\s()]*[0-9]").matcher(text);
        return m.find() ? m.group().trim() : "6 * 7";
    }
}
