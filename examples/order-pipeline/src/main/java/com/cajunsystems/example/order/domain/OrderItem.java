package com.cajunsystems.example.order.domain;

import java.io.Serializable;

/**
 * A single line item in an order. Immutable record, Serializable so it can
 * flow through stateful actor state without issues.
 */
public record OrderItem(String sku, int quantity, double unitPrice) implements Serializable {

    public double subtotal() {
        return quantity * unitPrice;
    }
}
