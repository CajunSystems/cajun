package com.cajunsystems.example.agents.core;

import com.cajunsystems.ActorContext;
import com.cajunsystems.Pid;
import com.cajunsystems.example.agents.llm.*;
import com.cajunsystems.example.agents.message.AgentRequest;
import com.cajunsystems.example.agents.message.AgentResponse;
import com.cajunsystems.example.agents.message.ToolMessage;
import com.cajunsystems.handler.StatefulHandler;
import com.cajunsystems.spring.CajunActorRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Core of the Cajun AI agent framework.
 *
 * <h3>How it works</h3>
 * <ol>
 *   <li>Agent receives {@link AgentRequest.Run} → calls LLM with the prompt.</li>
 *   <li>If LLM responds with {@code tool_use} the agent dispatches an actor message
 *       ({@link ToolMessage.Invoke}) to each tool actor and saves a
 *       {@link PendingRun} in state.</li>
 *   <li>Tool actors reply with {@link AgentRequest.ToolResult} messages.</li>
 *   <li>When all results for a request arrive the agent calls the LLM again with
 *       the tool results appended to the conversation history.</li>
 *   <li>When the LLM returns {@code end_turn} the agent sends
 *       {@link AgentResponse.Success} to the original {@code replyTo} Pid.</li>
 * </ol>
 *
 * <p><b>Key property:</b> tool calls are actor messages, not blocking function calls.
 * Multiple tool actors run in parallel; backpressure and clustering apply naturally.
 * The agent itself never blocks — it simply parks the {@link PendingRun} in state
 * and continues processing other messages until results arrive.
 *
 * <p>Because Cajun actors default to virtual threads, the LLM HTTP call
 * (in {@link LlmProvider#complete}) is allowed to block the virtual thread without
 * consuming a platform thread.
 */
public abstract class BaseAgentHandler implements StatefulHandler<AgentState, AgentRequest> {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected final LlmProvider llm;
    protected final CajunActorRegistry registry;

    protected BaseAgentHandler(LlmProvider llm, CajunActorRegistry registry) {
        this.llm = llm;
        this.registry = registry;
    }

    // ------------------------------------------------------------------ subclass contract

    /** System prompt injected into every LLM call. */
    protected abstract String systemPrompt();

    /** Tool definitions exposed to the LLM for this agent. */
    protected abstract List<ToolDef> tools();

    /**
     * Maps tool name → actor ID in the {@link CajunActorRegistry}.
     * Looked up lazily inside {@code receive()} so all tool actors are guaranteed registered.
     */
    protected abstract Map<String, String> toolActorIds();

    // ------------------------------------------------------------------ StatefulHandler

    @Override
    public AgentState receive(AgentRequest message, AgentState state, ActorContext ctx) {
        return switch (message) {
            case AgentRequest.Run run -> {
                log.info("[{}] Starting run {} — prompt: {}", ctx.getActorId(), run.requestId(), run.prompt());
                var history = new ArrayList<LlmMessage>();
                history.add(new LlmMessage.User(run.prompt()));
                yield callLlmAndDispatch(state, run.requestId(), run.replyTo(), history, ctx);
            }
            case AgentRequest.ToolResult result -> {
                log.debug("[{}] Tool result for {} / {} = {}", ctx.getActorId(),
                        result.requestId(), result.toolName(), result.result());
                PendingRun pending = state.pendingRuns().get(result.requestId());
                if (pending == null) {
                    log.warn("[{}] Stale tool result for requestId {}, ignoring", ctx.getActorId(), result.requestId());
                    yield state;
                }

                PendingRun updated = pending.withResult(result.toolCallId(), result.result());
                if (updated.isComplete()) {
                    log.info("[{}] All tool results received for {}, continuing LLM turn",
                            ctx.getActorId(), result.requestId());
                    var history = new ArrayList<>(updated.history());
                    history.add(new LlmMessage.ToolResults(updated.toToolResults()));
                    yield callLlmAndDispatch(
                            state.removePending(result.requestId()),
                            result.requestId(), updated.replyTo(), history, ctx);
                }
                yield state.updatePending(updated);
            }
        };
    }

    // ------------------------------------------------------------------ private

    private AgentState callLlmAndDispatch(AgentState state, String requestId, Pid replyTo,
                                          List<LlmMessage> history, ActorContext ctx) {
        LlmCompletion completion;
        try {
            completion = llm.complete(systemPrompt(), history, tools());
        } catch (Exception e) {
            log.error("[{}] LLM call failed for {}: {}", ctx.getActorId(), requestId, e.getMessage(), e);
            ctx.tell(replyTo, new AgentResponse.Error(requestId, "LLM error: " + e.getMessage()));
            return state;
        }

        return switch (completion) {
            case LlmCompletion.Text text -> {
                log.info("[{}] Run {} complete", ctx.getActorId(), requestId);
                ctx.tell(replyTo, new AgentResponse.Success(requestId, text.content()));
                yield state;
            }
            case LlmCompletion.ToolUse toolUse -> {
                var calls = toolUse.calls();
                log.info("[{}] LLM requested {} tool call(s) for {}", ctx.getActorId(), calls.size(), requestId);

                // Append the assistant's tool-use turn to history before dispatching.
                var newHistory = new ArrayList<>(history);
                newHistory.add(new LlmMessage.Assistant(null, calls));

                // Dispatch each call to its tool actor — they run in parallel.
                var toolIds = toolActorIds();
                for (var call : calls) {
                    String actorId = toolIds.get(call.name());
                    if (actorId != null) {
                        Pid toolPid = registry.getByActorId(actorId);
                        log.debug("[{}] Dispatching tool '{}' (call {}) to actor '{}'",
                                ctx.getActorId(), call.name(), call.id(), actorId);
                        ctx.tell(toolPid, new ToolMessage.Invoke(
                                requestId, call.id(), call.input(), ctx.self()));
                    } else {
                        // Unknown tool: immediately feed back an error result.
                        log.warn("[{}] Unknown tool '{}', returning error", ctx.getActorId(), call.name());
                        ctx.tellSelf(new AgentRequest.ToolResult(
                                requestId, call.id(), call.name(), "Unknown tool: " + call.name()));
                    }
                }

                yield state.addPending(PendingRun.of(requestId, replyTo, List.copyOf(newHistory), calls));
            }
        };
    }
}
