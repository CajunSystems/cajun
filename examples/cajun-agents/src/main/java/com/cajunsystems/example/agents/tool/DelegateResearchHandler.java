package com.cajunsystems.example.agents.tool;

import com.cajunsystems.ActorContext;
import com.cajunsystems.Pid;
import com.cajunsystems.example.agents.agent.ResearcherAgent;
import com.cajunsystems.example.agents.message.AgentRequest;
import com.cajunsystems.example.agents.message.AgentResponse;
import com.cajunsystems.example.agents.message.ToolMessage;
import com.cajunsystems.handler.Handler;
import com.cajunsystems.spring.CajunActorRegistry;
import com.cajunsystems.spring.annotation.ActorComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Tool actor that bridges the coordinator and the researcher agent,
 * demonstrating <b>agent-to-agent composition</b> through ordinary actor messages.
 *
 * <h3>Flow</h3>
 * <ol>
 *   <li>The coordinator's LLM calls {@code delegate_research} →
 *       {@link com.cajunsystems.example.agents.core.BaseAgentHandler} dispatches
 *       a {@link ToolMessage.Invoke} to this actor.</li>
 *   <li>This actor spawns an ephemeral <em>reply-collector</em> actor
 *       (a one-shot {@link Handler} lambda), then tells the researcher agent
 *       to start a new run with that collector as {@code replyTo}.</li>
 *   <li>Because Cajun actors run on virtual threads, blocking
 *       {@link CompletableFuture#get} here costs no OS thread.  The call
 *       is bounded by a 60-second timeout.</li>
 *   <li>When the researcher finishes it sends {@link AgentResponse} to the
 *       collector, which completes the future and stops itself.</li>
 *   <li>This actor forwards the result back to the coordinator via
 *       {@link AgentRequest.ToolResult}.</li>
 * </ol>
 *
 * <p>From the coordinator's perspective this is indistinguishable from any other
 * tool call — it is just actor messages all the way down.
 */
@ActorComponent(id = "delegate-research-tool")
public class DelegateResearchHandler implements Handler<ToolMessage> {

    private static final Logger log = LoggerFactory.getLogger(DelegateResearchHandler.class);

    private final CajunActorRegistry registry;

    public DelegateResearchHandler(CajunActorRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void receive(ToolMessage message, ActorContext ctx) {
        if (!(message instanceof ToolMessage.Invoke invoke)) return;

        String question = (String) invoke.args().getOrDefault("question", "");
        String subRequestId = invoke.requestId() + "-delegated-" + invoke.toolCallId();
        log.info("[delegate-research-tool] Delegating to researcher: {}", question);

        // CompletableFuture that the ephemeral collector will complete.
        CompletableFuture<String> future = new CompletableFuture<>();

        // Spawn a one-shot reply-collector actor.
        // Handler<AgentResponse> lambda: receives the researcher's reply, completes the future, stops.
        Pid collectorPid = ctx.getSystem()
                .actorOf((AgentResponse resp, ActorContext c) -> {
                    future.complete(switch (resp) {
                        case AgentResponse.Success s -> s.content();
                        case AgentResponse.Error e -> "Research error: " + e.message();
                    });
                    c.stop();
                })
                .withId("collector-" + subRequestId)
                .spawn();

        // Tell the researcher agent to start a new run.
        Pid researcherPid = registry.getByActorId("researcher-agent");
        ctx.tell(researcherPid, new AgentRequest.Run(subRequestId, question, collectorPid));

        // Block this virtual thread until the researcher responds.
        String result;
        try {
            result = future.get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("[delegate-research-tool] Delegation timed out or failed", e);
            result = "Research delegation failed: " + e.getMessage();
        }

        // Return result to the coordinator agent as a normal tool result.
        ctx.tell(invoke.callbackPid(), new AgentRequest.ToolResult(
                invoke.requestId(), invoke.toolCallId(), "delegate_research", result));
    }
}
