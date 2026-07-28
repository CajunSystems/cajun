package com.cajunsystems.example.order.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Stub payment gateway. Simulates a 90 % success rate so failure paths are
 * visible in the logs without any extra setup.
 *
 * <p>Replace the body of {@link #charge} with a real HTTP call to Stripe /
 * Braintree / etc. — the actor pipeline doesn't change at all.
 */
@Service
public class PaymentGatewayService {

    private static final Logger log = LoggerFactory.getLogger(PaymentGatewayService.class);

    public boolean charge(String orderId, String customerId, double amount) {
        // 90 % success rate — tweak to test failure paths
        boolean success = Math.random() > 0.10;
        if (success) {
            log.info("[gateway] Charged ${%.2f} for order {} (customer {})".formatted(amount, orderId, customerId));
        } else {
            log.warn("[gateway] Payment declined for order {} (customer {})", orderId, customerId);
        }
        return success;
    }
}
