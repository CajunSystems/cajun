package com.cajunsystems.example.order.web;

import com.cajunsystems.example.order.domain.OrderStatus;
import com.cajunsystems.example.order.message.InventoryCommand;
import com.cajunsystems.example.order.message.OrderCommand;
import com.cajunsystems.example.order.message.TrackerCommand;
import com.cajunsystems.example.order.web.dto.OrderStatusResponse;
import com.cajunsystems.example.order.web.dto.PlaceOrderRequest;
import com.cajunsystems.spring.CajunActorRegistry;
import com.cajunsystems.spring.TypedPid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * REST entry points into the actor pipeline.
 *
 * <h2>cajun-spring features demonstrated</h2>
 * <ul>
 *   <li>{@link TypedPid} injected from the {@link CajunActorRegistry} — type-safe,
 *       no raw casting</li>
 *   <li><strong>Ask pattern</strong> on {@code GET /orders/{id}}: the controller
 *       blocks on a {@code TypedPid.ask()} call and the tracker actor replies
 *       via {@code ctx.getSender()}</li>
 *   <li><strong>Backpressure demo</strong> on {@code POST /orders/flood}: fires N
 *       orders simultaneously to show the HIGH_THROUGHPUT preset in action</li>
 * </ul>
 */
@RestController
@RequestMapping("/orders")
public class OrderController {

    private final TypedPid<OrderCommand> validationPid;
    private final TypedPid<TrackerCommand> trackerPid;
    private final TypedPid<InventoryCommand> inventoryPid;

    // CajunActorRegistry is the canonical way to get TypedPids in Spring components.
    public OrderController(CajunActorRegistry registry) {
        this.validationPid = registry.getTypedPid("validation");
        this.trackerPid    = registry.getTypedPid("order-tracker");
        this.inventoryPid  = registry.getTypedPid("inventory");
    }

    /**
     * Place a new order. Returns immediately (fire-and-forget into the actor pipeline).
     *
     * <pre>
     * POST /orders
     * {
     *   "customerId": "cust-42",
     *   "shippingAddress": "1 Main St, Springfield",
     *   "items": [
     *     { "sku": "LAPTOP-001", "quantity": 1, "unitPrice": 999.99 }
     *   ]
     * }
     * </pre>
     */
    @PostMapping
    public ResponseEntity<Map<String, String>> placeOrder(@RequestBody PlaceOrderRequest req) {
        String orderId = "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        validationPid.tell(new OrderCommand.Place(
                orderId,
                req.customerId(),
                req.shippingAddress(),
                req.items()
        ));

        return ResponseEntity.accepted().body(Map.of(
                "orderId", orderId,
                "message", "Order accepted — processing asynchronously"
        ));
    }

    /**
     * Get the current status of an order using the <strong>ask pattern</strong>.
     *
     * <p>This call blocks (up to 5 s) until the {@code OrderTrackerHandler} replies.
     * The tracker actor's {@code receive()} method detects the sender via
     * {@code ctx.getSender()} and sends the {@link OrderStatus} back.
     *
     * <pre>GET /orders/{orderId}</pre>
     */
    @GetMapping("/{orderId}")
    public ResponseEntity<OrderStatusResponse> getStatus(@PathVariable String orderId) {
        try {
            // ask() returns Reply<OrderStatus>; .get() blocks until the actor replies.
            OrderStatus status = trackerPid
                    .<OrderStatus>ask(new TrackerCommand.GetStatus(orderId), Duration.ofSeconds(5))
                    .get();

            return ResponseEntity.ok(new OrderStatusResponse(orderId, status.name()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(new OrderStatusResponse(orderId, "ERROR: " + e.getMessage()));
        }
    }

    /**
     * Restock a SKU. Forwarded directly to the inventory actor.
     *
     * <pre>POST /orders/inventory/restock?sku=LAPTOP-001&amp;qty=100</pre>
     */
    @PostMapping("/inventory/restock")
    public ResponseEntity<Map<String, String>> restock(
            @RequestParam String sku,
            @RequestParam int qty) {
        inventoryPid.tell(new InventoryCommand.Restock(sku, qty));
        return ResponseEntity.accepted().body(Map.of("message", "Restock queued for " + sku));
    }

    /**
     * Fire {@code count} orders in rapid succession to demonstrate backpressure.
     * Watch the logs — with {@code HIGH_THROUGHPUT} preset, the validation actor
     * will drop oldest messages when its mailbox fills rather than blocking callers.
     *
     * <pre>POST /orders/flood?count=500</pre>
     */
    @PostMapping("/flood")
    public ResponseEntity<Map<String, Object>> flood(@RequestParam(defaultValue = "100") int count) {
        for (int i = 0; i < count; i++) {
            String orderId = "FLOOD-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
            validationPid.tell(new OrderCommand.Place(
                    orderId,
                    "flood-customer",
                    "1 Flood St",
                    java.util.List.of(
                            new com.cajunsystems.example.order.domain.OrderItem("MOUSE-001", 1, 29.99)
                    )
            ));
        }
        return ResponseEntity.accepted().body(Map.of(
                "sent", count,
                "note", "Check logs for backpressure events"
        ));
    }
}
