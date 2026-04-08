package com.cajunsystems.example.agents.web;

import com.cajunsystems.ActorContext;
import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.example.agents.message.AgentRequest;
import com.cajunsystems.example.agents.message.AgentResponse;
import com.cajunsystems.example.agents.web.dto.RunRequest;
import com.cajunsystems.example.agents.web.dto.RunResponse;
import com.cajunsystems.spring.CajunActorRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * REST façade for the agent actors.
 *
 * <h3>Reply-actor pattern</h3>
 * Spring MVC uses platform threads, so the controller cannot receive an actor message
 * directly.  Instead, for each HTTP request we:
 * <ol>
 *   <li>Create a {@link CompletableFuture} to hold the final result.</li>
 *   <li>Spawn a one-shot <em>reply actor</em> (a {@code Handler<AgentResponse>} lambda)
 *       that completes the future and stops itself when a message arrives.</li>
 *   <li>Tell the target agent to start a run with the reply actor as {@code replyTo}.</li>
 *   <li>Block on {@code future.get()} with a 120-second timeout.</li>
 * </ol>
 *
 * <p>The reply actor is extremely lightweight — it is a virtual-thread-backed actor
 * with a single-use lifetime.
 *
 * <h3>Endpoints</h3>
 * <pre>
 * POST /agents/{agentId}/run       — run a prompt against an agent
 * GET  /agents                     — list available agent IDs
 * </pre>
 *
 * <h3>Available agents</h3>
 * <ul>
 *   <li>{@code researcher-agent} — uses web_search + calculator tools</li>
 *   <li>{@code coordinator-agent} — can also delegate to researcher via
 *       the delegate_research tool (agent-to-agent)</li>
 * </ul>
 *
 * <h3>Example</h3>
 * <pre>
 * # Researcher agent with tool use
 * curl -s -X POST http://localhost:8080/agents/researcher-agent/run \
 *      -H 'Content-Type: application/json' \
 *      -d '{"prompt":"What is the actor model?"}'
 *
 * # Coordinator delegates to researcher
 * curl -s -X POST http://localhost:8080/agents/coordinator-agent/run \
 *      -H 'Content-Type: application/json' \
 *      -d '{"prompt":"Research the Cajun actor system for me"}'
 *
 * # Calculator tool
 * curl -s -X POST http://localhost:8080/agents/researcher-agent/run \
 *      -H 'Content-Type: application/json' \
 *      -d '{"prompt":"Calculate (144 / 12) * 7"}'
 * </pre>
 */
@RestController
@RequestMapping("/agents")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final ActorSystem actorSystem;
    private final CajunActorRegistry registry;

    public AgentController(ActorSystem actorSystem, CajunActorRegistry registry) {
        this.actorSystem = actorSystem;
        this.registry = registry;
    }

    @PostMapping("/{agentId}/run")
    public RunResponse run(@PathVariable String agentId, @RequestBody RunRequest request)
            throws Exception {
        Pid agentPid = registry.getByActorId(agentId);
        if (agentPid == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown agent: " + agentId);
        }

        String requestId = UUID.randomUUID().toString();
        CompletableFuture<AgentResponse> future = new CompletableFuture<>();

        // One-shot reply actor: completes the future then self-destructs.
        Pid replyPid = actorSystem.actorOf((AgentResponse resp, ActorContext ctx) -> {
            future.complete(resp);
            ctx.stop();
        }).withId("reply-" + requestId).spawn();

        log.info("HTTP → agent [{}] requestId={} prompt={}", agentId, requestId, request.prompt());
        agentPid.tell(new AgentRequest.Run(requestId, request.prompt(), replyPid));

        AgentResponse response = future.get(120, TimeUnit.SECONDS);

        return switch (response) {
            case AgentResponse.Success s -> new RunResponse(requestId, "success", s.content(), null);
            case AgentResponse.Error e  -> new RunResponse(requestId, "error", null, e.message());
        };
    }

    @GetMapping
    public List<String> listAgents() {
        return List.of("researcher-agent", "coordinator-agent");
    }
}
