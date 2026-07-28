package com.cajunsystems.example.order.web.dto;

import com.cajunsystems.example.order.domain.OrderItem;

import java.util.List;

public record PlaceOrderRequest(
        String customerId,
        String shippingAddress,
        List<OrderItem> items
) {}
