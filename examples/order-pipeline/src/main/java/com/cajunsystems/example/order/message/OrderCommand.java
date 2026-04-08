package com.cajunsystems.example.order.message;

import com.cajunsystems.example.order.domain.OrderItem;

import java.util.List;

/**
 * Commands handled by {@code ValidationHandler}.
 */
public sealed interface OrderCommand {

    /**
     * Place a new order. Triggers the full pipeline:
     * Validation → Inventory → Payment → Fulfillment → Notification.
     */
    record Place(
            String orderId,
            String customerId,
            String shippingAddress,
            List<OrderItem> items
    ) implements OrderCommand {}
}
