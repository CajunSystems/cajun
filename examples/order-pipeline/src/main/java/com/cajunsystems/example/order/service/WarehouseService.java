package com.cajunsystems.example.order.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Stub warehouse integration. Replace with a real WMS HTTP client.
 */
@Service
public class WarehouseService {

    private static final Logger log = LoggerFactory.getLogger(WarehouseService.class);

    public void dispatch(String orderId, String shippingAddress, double total) {
        log.info("[warehouse] Dispatching order {} to '{}' (value: ${%.2f})"
                .formatted(orderId, shippingAddress, total));
    }
}
