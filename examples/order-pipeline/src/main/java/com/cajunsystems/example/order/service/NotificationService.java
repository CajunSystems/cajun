package com.cajunsystems.example.order.service;

import com.cajunsystems.example.order.domain.OrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Stub notification service. Replace with JavaMail / Twilio / FCM etc.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    public void send(String customerId, String orderId, OrderStatus status) {
        log.info("[notification] → customer {} | order {} | status {}", customerId, orderId, status);
    }
}
