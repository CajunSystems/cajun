package com.cajunsystems.example.agents.tool;

import com.cajunsystems.ActorContext;
import com.cajunsystems.example.agents.message.AgentRequest;
import com.cajunsystems.example.agents.message.ToolMessage;
import com.cajunsystems.handler.Handler;
import com.cajunsystems.spring.annotation.ActorComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Tool actor that simulates web search.
 *
 * <p>Receives a {@link ToolMessage.Invoke} from an agent actor, looks up the
 * query in a small in-memory knowledge base (with a simulated 50 ms network
 * delay), and sends {@link AgentRequest.ToolResult} back to the originating
 * agent.
 *
 * <p>Because this is an {@code @ActorComponent} it is spawned automatically by
 * cajun-spring and registered in the {@link com.cajunsystems.spring.CajunActorRegistry}
 * under the id {@code "web-search-tool"}.
 */
@ActorComponent(id = "web-search-tool")
public class WebSearchHandler implements Handler<ToolMessage> {

    private static final Logger log = LoggerFactory.getLogger(WebSearchHandler.class);

    private static final Map<String, String> KB = Map.of(
            "actor model",
            "The Actor Model is a mathematical model of concurrent computation introduced by " +
            "Carl Hewitt in 1973. Actors are independent units that communicate exclusively " +
            "via asynchronous message passing — no shared state, no locks.",

            "cajun",
            "Cajun is a high-performance distributed actor system for Java 21+. It provides " +
            "virtual-thread-based actors, stateful actors with persistence, backpressure " +
            "management, and Etcd-based clustering.",

            "virtual threads",
            "Java Virtual Threads (Project Loom, GA in Java 21) are lightweight user-mode " +
            "threads managed by the JVM. They enable millions of concurrent tasks with minimal " +
            "OS-thread overhead, making blocking I/O cheap.",

            "erlang otp",
            "Erlang OTP (Open Telecom Platform) is a set of libraries and design principles " +
            "for building fault-tolerant distributed systems. It popularised the actor model " +
            "with concepts like supervisors, gen_servers, and hot code reloading.",

            "backpressure",
            "Backpressure is a mechanism that prevents fast producers from overwhelming slow " +
            "consumers. In Cajun, backpressure strategies include BLOCK, DROP_NEW, and " +
            "DROP_OLDEST, configurable per actor."
    );

    @Override
    public void receive(ToolMessage message, ActorContext ctx) {
        if (!(message instanceof ToolMessage.Invoke invoke)) return;

        String query = ((String) invoke.args().getOrDefault("query", "")).toLowerCase();
        log.info("[web-search-tool] Searching for: {}", query);

        // Simulate network latency
        try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        String result = KB.entrySet().stream()
                .filter(e -> query.contains(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse("Search results for '" + query + "': Multiple sources found relevant information " +
                        "about this topic. Key details have been extracted and summarised.");

        ctx.tell(invoke.callbackPid(), new AgentRequest.ToolResult(
                invoke.requestId(), invoke.toolCallId(), "web_search", result));
    }
}
