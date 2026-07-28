package com.cajunsystems.example.order.domain;

public enum OrderStatus {
    PENDING,
    RESERVED,       // inventory reserved, awaiting payment
    PAID,           // payment captured, awaiting fulfillment
    SHIPPED,        // handed to warehouse
    FAILED_INVENTORY,
    FAILED_PAYMENT,
    UNKNOWN
}
