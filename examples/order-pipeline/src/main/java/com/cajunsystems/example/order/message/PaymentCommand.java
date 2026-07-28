package com.cajunsystems.example.order.message;

import com.cajunsystems.example.order.domain.OrderItem;

import java.util.List;

/**
 * Commands handled by {@code PaymentHandler}.
 */
public sealed interface PaymentCommand {

    /** Charge the customer for the given order. */
    record Charge(
            String orderId,
            String customerId,
            String shippingAddress,
            List<OrderItem> items,
            double total
    ) implements PaymentCommand {}
}
