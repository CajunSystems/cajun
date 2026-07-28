package com.cajunsystems.example.order.message;

import com.cajunsystems.example.order.domain.OrderStatus;

/**
 * Commands handled by {@code NotificationHandler}.
 */
public sealed interface NotificationCommand {

    /** Notify a customer of an order status change. */
    record OrderUpdate(
            String customerId,
            String orderId,
            OrderStatus status
    ) implements NotificationCommand {}
}
