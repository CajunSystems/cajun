package com.cajunsystems.example.order.handler;

import com.cajunsystems.ActorContext;
import com.cajunsystems.example.order.domain.OrderStatus;
import com.cajunsystems.example.order.message.TrackerCommand;
import com.cajunsystems.handler.StatefulHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Maintains a live view of every order's current status.
 *
 * <h2>Ask pattern</h2>
 * The REST controller queries this actor using the ask pattern:
 * <pre>{@code
 * Reply<OrderStatus> reply = trackerPid.ask(new TrackerCommand.GetStatus(orderId), Duration.ofSeconds(5));
 * OrderStatus status = reply.get();
 * }</pre>
 *
 * When Cajun delivers a message sent via {@code ask()}, the sender context is set
 * automatically. The handler replies by calling {@code ctx.getSender().ifPresent(...)},
 * completing the {@code Reply} on the caller's side.
 *
 * <h2>cajun-spring features demonstrated</h2>
 * <ul>
 *   <li>Stateful actor registered manually in {@code ActorConfig}</li>
 *   <li>Ask pattern via {@code ctx.getSender()}</li>
 *   <li>Immutable state record returned from each {@code receive()} call</li>
 * </ul>
 */
public class OrderTrackerHandler implements StatefulHandler<OrderTrackerHandler.TrackerState, TrackerCommand> {

    private static final Logger log = LoggerFactory.getLogger(OrderTrackerHandler.class);

    // ---- State ----

    public record TrackerState(Map<String, OrderStatus> statuses) implements Serializable {

        public TrackerState {
            statuses = Collections.unmodifiableMap(new HashMap<>(statuses));
        }

        public TrackerState with(String orderId, OrderStatus status) {
            Map<String, OrderStatus> updated = new HashMap<>(statuses);
            updated.put(orderId, status);
            return new TrackerState(updated);
        }
    }

    // ---- Message handling ----

    @Override
    public TrackerState receive(TrackerCommand cmd, TrackerState state, ActorContext ctx) {
        return switch (cmd) {

            case TrackerCommand.UpdateStatus update -> {
                log.info("[tracker] Order {} → {}", update.orderId(), update.status());
                yield state.with(update.orderId(), update.status());
            }

            case TrackerCommand.GetStatus query -> {
                // Reply to the ask() caller with the current status.
                // getSender() is populated by Cajun when the message was sent via ask().
                OrderStatus status = state.statuses().getOrDefault(query.orderId(), OrderStatus.UNKNOWN);
                ctx.getSender().ifPresent(sender -> ctx.tell(sender, status));
                yield state; // read-only — state unchanged
            }
        };
    }
}
