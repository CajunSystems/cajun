package com.cajunsystems.example.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Cajun-Spring order processing pipeline example.
 *
 * <h2>Running</h2>
 * <pre>
 * ./gradlew :order-pipeline:bootRun
 * </pre>
 * (from the repo root — the {@code settings.gradle} wires in local cajun-spring sources)
 *
 * <h2>Try it out</h2>
 * <pre>
 * # Place an order
 * curl -s -X POST http://localhost:8080/orders \
 *   -H 'Content-Type: application/json' \
 *   -d '{
 *     "customerId": "cust-42",
 *     "shippingAddress": "1 Main St, Springfield",
 *     "items": [{ "sku": "LAPTOP-001", "quantity": 1, "unitPrice": 999.99 }]
 *   }'
 *
 * # Check status (ask pattern — blocks until tracker replies)
 * curl -s http://localhost:8080/orders/{orderId}
 *
 * # Restock a SKU
 * curl -s -X POST "http://localhost:8080/orders/inventory/restock?sku=LAPTOP-001&qty=100"
 *
 * # Flood test (backpressure demo — watch the logs)
 * curl -s -X POST "http://localhost:8080/orders/flood?count=500"
 * </pre>
 */
@SpringBootApplication
public class OrderPipelineApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderPipelineApplication.class, args);
    }
}
