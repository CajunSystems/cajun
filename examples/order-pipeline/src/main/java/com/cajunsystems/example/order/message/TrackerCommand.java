package com.cajunsystems.example.order.message;

import com.cajunsystems.example.order.domain.OrderStatus;

/**
 * Commands handled by {@code OrderTrackerHandler} (stateful).
 *
 * <p>{@link GetStatus} demonstrates the <em>ask pattern</em>: the REST controller
 * sends this message via {@code typedPid.ask(...)} and blocks for the reply.
 * The handler responds via {@code ctx.getSender().ifPresent(s -> ctx.tell(s, status))}.
 */
public sealed interface TrackerCommand {

    /** Record or update an order's status in the tracker's state. */
    record UpdateStatus(String orderId, OrderStatus status) implements TrackerCommand {}

    /**
     * Query the current status of an order.
     * Use with {@code TypedPid.ask()} — the handler replies with an {@link OrderStatus}.
     */
    record GetStatus(String orderId) implements TrackerCommand {}
}
