package com.cajunsystems.example.order.handler;

import com.cajunsystems.ActorContext;
import com.cajunsystems.example.order.domain.OrderStatus;
import com.cajunsystems.example.order.message.FulfillmentCommand;
import com.cajunsystems.example.order.message.InventoryCommand;
import com.cajunsystems.example.order.message.PaymentCommand;
import com.cajunsystems.example.order.message.TrackerCommand;
import com.cajunsystems.example.order.service.PaymentGatewayService;
import com.cajunsystems.handler.Handler;
import com.cajunsystems.spring.CajunActorRegistry;
import com.cajunsystems.spring.annotation.ActorComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Charges the customer via the {@link PaymentGatewayService} (a Spring-managed service bean).
 * On success, forwards to fulfillment. On failure, releases the reserved inventory
 * and marks the order as failed.
 *
 * <h2>cajun-spring features demonstrated</h2>
 * <ul>
 *   <li>{@code @ActorComponent} with constructor-injected Spring services</li>
 *   <li>Actor-to-actor fanout: on failure, two actors ({@code inventory} and
 *       {@code order-tracker}) are messaged in a single handler invocation</li>
 * </ul>
 */
@ActorComponent(id = "payment")
public class PaymentHandler implements Handler<PaymentCommand> {

    private static final Logger log = LoggerFactory.getLogger(PaymentHandler.class);

    private final PaymentGatewayService gateway;
    private final CajunActorRegistry registry;

    public PaymentHandler(PaymentGatewayService gateway, CajunActorRegistry registry) {
        this.gateway = gateway;
        this.registry = registry;
    }

    @Override
    public void receive(PaymentCommand cmd, ActorContext ctx) {
        switch (cmd) {
            case PaymentCommand.Charge charge -> {
                log.info("[payment] Charging order {} (${%.2f})".formatted(charge.orderId(), charge.total()));

                boolean charged = gateway.charge(charge.orderId(), charge.customerId(), charge.total());

                if (charged) {
                    log.info("[payment] Payment successful for order {}", charge.orderId());
                    registry.<TrackerCommand>getTypedPid("order-tracker")
                            .tell(new TrackerCommand.UpdateStatus(charge.orderId(), OrderStatus.PAID));
                    registry.<FulfillmentCommand>getTypedPid("fulfillment")
                            .tell(new FulfillmentCommand.Ship(
                                    charge.orderId(),
                                    charge.customerId(),
                                    charge.shippingAddress(),
                                    charge.total()
                            ));
                } else {
                    log.warn("[payment] Payment failed for order {}", charge.orderId());
                    // Release the reserved stock so other orders can use it
                    registry.<InventoryCommand>getTypedPid("inventory")
                            .tell(new InventoryCommand.Release(charge.orderId(), charge.items()));
                    registry.<TrackerCommand>getTypedPid("order-tracker")
                            .tell(new TrackerCommand.UpdateStatus(charge.orderId(), OrderStatus.FAILED_PAYMENT));
                }
            }
        }
    }
}
