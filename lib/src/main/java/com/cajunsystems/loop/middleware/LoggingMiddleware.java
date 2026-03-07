package com.cajunsystems.loop.middleware;

import com.cajunsystems.loop.ActorBehavior;
import com.cajunsystems.loop.BehaviorMiddleware;
import com.cajunsystems.loop.LoopStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link BehaviorMiddleware} that emits a structured log entry before and
 * after every message-processing step.
 *
 * <p>Usage:
 * <pre>{@code
 * ActorBehavior<E, State, Msg> logged =
 *     baseBehavior.withMiddleware(new LoggingMiddleware<>("payment-processor"));
 * }</pre>
 *
 * <p>Log output (SLF4J DEBUG):
 * <pre>
 * [payment-processor] RECV  Deposit[amount=100]
 * [payment-processor] DONE  Deposit[amount=100] → Continue state=PaymentState{...}
 * [payment-processor] ERROR Deposit[amount=100] → RuntimeException: insufficient funds
 * </pre>
 *
 * @param <E>       error type
 * @param <State>   actor state type
 * @param <Message> message type
 */
public final class LoggingMiddleware<E extends Throwable, State, Message>
        implements BehaviorMiddleware<E, State, Message> {

    private final Logger logger;

    /**
     * Creates a middleware that logs using a logger named after {@code actorName}.
     */
    public LoggingMiddleware(String actorName) {
        this.logger = LoggerFactory.getLogger("cajun.actor." + actorName);
    }

    /**
     * Creates a middleware that logs using the supplied SLF4J {@link Logger}.
     */
    public LoggingMiddleware(Logger logger) {
        this.logger = logger;
    }

    @Override
    public ActorBehavior<E, State, Message> wrap(ActorBehavior<E, State, Message> next) {
        return (msg, state, ctx) -> {
            if (logger.isDebugEnabled()) {
                logger.debug("[{}] RECV  {}", ctx.getActorId(), msg);
            }
            return next.step(msg, state, ctx)
                .tap(step -> {
                    if (logger.isDebugEnabled()) {
                        logger.debug("[{}] DONE  {} → {} state={}",
                            ctx.getActorId(), msg, stepLabel(step), step.state());
                    }
                })
                .tapError(err ->
                    logger.error("[{}] ERROR {} → {}: {}",
                        ctx.getActorId(), msg, err.getClass().getSimpleName(), err.getMessage(), err)
                );
        };
    }

    private String stepLabel(LoopStep<State> step) {
        return switch (step) {
            case LoopStep.Continue<State> ignored -> "Continue";
            case LoopStep.Stop<State>    ignored -> "Stop";
            case LoopStep.Restart<State> ignored -> "Restart";
        };
    }
}
