package com.cajunsystems.example.order.message;

/**
 * Commands handled by {@code FulfillmentHandler}.
 */
public sealed interface FulfillmentCommand {

    /** Hand the paid order to the warehouse for shipping. */
    record Ship(
            String orderId,
            String customerId,
            String shippingAddress,
            double total
    ) implements FulfillmentCommand {}
}
