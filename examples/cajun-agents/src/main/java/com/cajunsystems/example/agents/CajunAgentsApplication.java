package com.cajunsystems.example.agents;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * AI Agentic Framework on Cajun — entry point.
 *
 * <p>This example shows how Cajun actors map naturally onto AI agents:
 * <ul>
 *   <li>Each agent is a <b>stateful actor</b> — isolated state, sequential message
 *       processing, no shared memory.</li>
 *   <li>LLM tool calls become <b>actor messages</b> to tool actors — multiple tools
 *       run in parallel, backpressure is automatic, and tools can be on remote nodes.</li>
 *   <li><b>Agent-to-agent</b> delegation is just one actor sending a message to another
 *       — the coordinator delegates deep research to the researcher agent transparently.</li>
 *   <li>The reply-actor pattern bridges the async actor world with synchronous HTTP
 *       without callbacks or reactive plumbing.</li>
 * </ul>
 *
 * <h3>Quick start (demo mode — no API key needed)</h3>
 * <pre>
 * cd examples/cajun-agents
 * ../../gradlew bootRun
 *
 * # Researcher uses web_search tool
 * curl -s -X POST localhost:8080/agents/researcher-agent/run \
 *      -H 'Content-Type: application/json' \
 *      -d '{"prompt":"What is the actor model?"}' | jq .
 *
 * # Coordinator delegates to researcher (agent-to-agent)
 * curl -s -X POST localhost:8080/agents/coordinator-agent/run \
 *      -H 'Content-Type: application/json' \
 *      -d '{"prompt":"Research the Cajun actor system for me"}' | jq .
 *
 * # Calculator tool
 * curl -s -X POST localhost:8080/agents/researcher-agent/run \
 *      -H 'Content-Type: application/json' \
 *      -d '{"prompt":"Calculate (144 / 12) * 7"}' | jq .
 * </pre>
 *
 * <h3>With real Claude</h3>
 * <pre>
 * ANTHROPIC_API_KEY=sk-ant-... ../../gradlew bootRun
 * </pre>
 */
@SpringBootApplication
public class CajunAgentsApplication {

    public static void main(String[] args) {
        SpringApplication.run(CajunAgentsApplication.class, args);
    }
}
