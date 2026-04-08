package com.cajunsystems.example.order.message;

import com.cajunsystems.example.order.domain.OrderItem;

import java.util.List;

/**
 * Commands handled by {@code InventoryHandler} (stateful).
 */
public sealed interface InventoryCommand {

    /** Try to reserve stock for all items in the order. */
    record Reserve(
            String orderId,
            String customerId,
            String shippingAddress,
            List<OrderItem> items,
            double total
    ) implements InventoryCommand {}

    /** Release previously reserved stock (called on payment failure). */
    record Release(
            String orderId,
            List<OrderItem> items
    ) implements InventoryCommand {}

    /** Add stock for a SKU (used by the /inventory/restock endpoint). */
    record Restock(String sku, int quantity) implements InventoryCommand {}
}
