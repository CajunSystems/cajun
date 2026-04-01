package com.cajunsystems.loop.middleware;

import com.cajunsystems.loop.ActorBehavior;
import com.cajunsystems.loop.BehaviorMiddleware;
import com.cajunsystems.loop.LoopStep;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * A {@link BehaviorMiddleware} that records per-step latency and outcome counts.
 *
 * <p>By default metrics are kept in-process as atomic counters accessible via
 * {@link MetricsHandle}.  Callers that want to forward metrics to an external
 * system (Micrometer, Dropwizard, OpenTelemetry, etc.) can supply a
 * {@link Consumer}{@code <StepEvent>} callback at construction time.
 *
 * <pre>{@code
 * MetricsMiddleware<E, State, Msg> metrics = new MetricsMiddleware<>("checkout");
 *
 * ActorBehavior<E, State, Msg> observed =
 *     baseBehavior.withMiddleware(metrics);
 *
 * // Later, read counters:
 * long processed = metrics.handle().processedCount();
 * long errors    = metrics.handle().errorCount();
 * long avgNs     = metrics.handle().averageLatencyNanos();
 * }</pre>
 *
 * @param <E>       error type
 * @param <State>   actor state type
 * @param <Message> message type
 */
public final class MetricsMiddleware<E extends Throwable, State, Message>
        implements BehaviorMiddleware<E, State, Message> {

    /**
     * Snapshot of a single step's outcome, forwarded to external metric consumers.
     *
     * @param actorId      actor that processed the message
     * @param messageType  simple class name of the message
     * @param latencyNanos wall-clock time taken to execute the step
     * @param outcome      the LoopStep variant: "Continue", "Stop", "Restart", or "Error"
     */
    public record StepEvent(
            String actorId,
            String messageType,
            long latencyNanos,
            String outcome) {}

    /**
     * In-process metric counters for a single actor behavior pipeline.
     */
    public static final class MetricsHandle {
        private final AtomicLong processedCount = new AtomicLong();
        private final AtomicLong errorCount     = new AtomicLong();
        private final AtomicLong stopCount      = new AtomicLong();
        private final AtomicLong restartCount   = new AtomicLong();
        private final AtomicLong totalNanos     = new AtomicLong();

        public long processedCount()       { return processedCount.get(); }
        public long errorCount()           { return errorCount.get(); }
        public long stopCount()            { return stopCount.get(); }
        public long restartCount()         { return restartCount.get(); }
        public long totalLatencyNanos()    { return totalNanos.get(); }

        public long averageLatencyNanos() {
            long count = processedCount.get() + errorCount.get();
            return count == 0 ? 0 : totalNanos.get() / count;
        }

        void recordSuccess(LoopStep<?> step, long nanos) {
            processedCount.incrementAndGet();
            totalNanos.addAndGet(nanos);
            if (step instanceof LoopStep.Stop<?>)    stopCount.incrementAndGet();
            if (step instanceof LoopStep.Restart<?>) restartCount.incrementAndGet();
        }

        void recordError(long nanos) {
            errorCount.incrementAndGet();
            totalNanos.addAndGet(nanos);
        }
    }

    private final String actorName;
    private final MetricsHandle handle;
    private final Consumer<StepEvent> eventConsumer;

    /**
     * Creates a middleware with in-process counters only.
     *
     * @param actorName human-readable name used in {@link StepEvent#actorId}
     */
    public MetricsMiddleware(String actorName) {
        this(actorName, ignored -> {});
    }

    /**
     * Creates a middleware that also forwards {@link StepEvent}s to
     * {@code eventConsumer} (e.g., a Micrometer registry adapter).
     */
    public MetricsMiddleware(String actorName, Consumer<StepEvent> eventConsumer) {
        this.actorName = actorName;
        this.handle = new MetricsHandle();
        this.eventConsumer = eventConsumer;
    }

    /** Returns the in-process counter handle for this middleware instance. */
    public MetricsHandle handle() {
        return handle;
    }

    @Override
    public ActorBehavior<E, State, Message> wrap(ActorBehavior<E, State, Message> next) {
        return (msg, state, ctx) -> {
            long start = System.nanoTime();
            return next.step(msg, state, ctx)
                .tap(step -> {
                    long nanos = System.nanoTime() - start;
                    handle.recordSuccess(step, nanos);
                    eventConsumer.accept(new StepEvent(
                        actorName,
                        msg.getClass().getSimpleName(),
                        nanos,
                        outcomeLabel(step)));
                })
                .tapError(err -> {
                    long nanos = System.nanoTime() - start;
                    handle.recordError(nanos);
                    eventConsumer.accept(new StepEvent(
                        actorName,
                        msg.getClass().getSimpleName(),
                        nanos,
                        "Error"));
                });
        };
    }

    private String outcomeLabel(LoopStep<State> step) {
        return switch (step) {
            case LoopStep.Continue<State> ignored -> "Continue";
            case LoopStep.Stop<State>    ignored -> "Stop";
            case LoopStep.Restart<State> ignored -> "Restart";
        };
    }
}
