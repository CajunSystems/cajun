package com.cajunsystems.example.order.handler;

import com.cajunsystems.ActorContext;
import com.cajunsystems.example.order.domain.Order;
import com.cajunsystems.example.order.domain.OrderStatus;
import com.cajunsystems.example.order.message.InventoryCommand;
import com.cajunsystems.example.order.message.OrderCommand;
import com.cajunsystems.example.order.message.TrackerCommand;
import com.cajunsystems.example.order.repository.OrderRepository;
import com.cajunsystems.handler.Handler;
import com.cajunsystems.spring.CajunActorRegistry;
import com.cajunsystems.spring.annotation.ActorComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * First stage of the pipeline. Validates an incoming order, persists it to the
 * database via a Spring-injected {@link OrderRepository}, then hands it off to
 * the inventory actor.
 *
 * <h2>cajun-spring features demonstrated</h2>
 * <ul>
 *   <li>{@code @ActorComponent} — this class is both a Spring bean (DI wired) and a Cajun actor</li>
 *   <li>Constructor injection of Spring-managed {@link OrderRepository}</li>
 *   <li>{@code backpressurePreset = "HIGH_THROUGHPUT"} — drops oldest messages under load
 *       so the pipeline stays responsive during traffic spikes</li>
 *   <li>Lazy {@link CajunActorRegistry} lookup inside {@code receive()} — safe because
 *       all actors are registered before HTTP traffic reaches the app</li>
 * </ul>
 */
@ActorComponent(id = "validation", backpressurePreset = "HIGH_THROUGHPUT")
public class ValidationHandler implements Handler<OrderCommand> {

    private static final Logger log = LoggerFactory.getLogger(ValidationHandler.class);

    private final OrderRepository orderRepository;
    private final CajunActorRegistry registry;

    // Spring injects both dependencies at construction time.
    public ValidationHandler(OrderRepository orderRepository, CajunActorRegistry registry) {
        this.orderRepository = orderRepository;
        this.registry = registry;
    }

    @Override
    public void receive(OrderCommand cmd, ActorContext ctx) {
        switch (cmd) {
            case OrderCommand.Place place -> {
                log.info("[validation] Received order {}", place.orderId());

                // 1. Basic validation
                if (place.items() == null || place.items().isEmpty()) {
                    log.warn("[validation] Order {} rejected — no items", place.orderId());
                    registry.<TrackerCommand>getTypedPid("order-tracker")
                            .tell(new TrackerCommand.UpdateStatus(place.orderId(), OrderStatus.FAILED_INVENTORY));
                    return;
                }

                // 2. Persist the order (Spring Data JPA, H2 in this example)
                double total = place.items().stream().mapToDouble(i -> i.quantity() * i.unitPrice()).sum();
                orderRepository.save(new Order(place.orderId(), place.customerId(), place.shippingAddress(), total));

                log.info("[validation] Order {} saved (total: ${%.2f}), forwarding to inventory".formatted(place.orderId(), total));

                // 3. Hand off to the inventory actor
                registry.<InventoryCommand>getTypedPid("inventory")
                        .tell(new InventoryCommand.Reserve(
                                place.orderId(),
                                place.customerId(),
                                place.shippingAddress(),
                                place.items(),
                                total
                        ));
            }
        }
    }
}
