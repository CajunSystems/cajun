package com.cajunsystems.example.order.handler;

import com.cajunsystems.ActorContext;
import com.cajunsystems.example.order.domain.OrderStatus;
import com.cajunsystems.example.order.message.FulfillmentCommand;
import com.cajunsystems.example.order.message.NotificationCommand;
import com.cajunsystems.example.order.message.TrackerCommand;
import com.cajunsystems.example.order.service.WarehouseService;
import com.cajunsystems.handler.Handler;
import com.cajunsystems.spring.CajunActorRegistry;
import com.cajunsystems.spring.annotation.ActorComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hands a paid order to the warehouse, then broadcasts the final status to
 * both the tracker and notification actors.
 */
@ActorComponent(id = "fulfillment")
public class FulfillmentHandler implements Handler<FulfillmentCommand> {

    private static final Logger log = LoggerFactory.getLogger(FulfillmentHandler.class);

    private final WarehouseService warehouse;
    private final CajunActorRegistry registry;

    public FulfillmentHandler(WarehouseService warehouse, CajunActorRegistry registry) {
        this.warehouse = warehouse;
        this.registry = registry;
    }

    @Override
    public void receive(FulfillmentCommand cmd, ActorContext ctx) {
        switch (cmd) {
            case FulfillmentCommand.Ship ship -> {
                log.info("[fulfillment] Shipping order {} to {}", ship.orderId(), ship.shippingAddress());
                warehouse.dispatch(ship.orderId(), ship.shippingAddress(), ship.total());

                registry.<TrackerCommand>getTypedPid("order-tracker")
                        .tell(new TrackerCommand.UpdateStatus(ship.orderId(), OrderStatus.SHIPPED));

                registry.<NotificationCommand>getTypedPid("notification")
                        .tell(new NotificationCommand.OrderUpdate(
                                ship.customerId(), ship.orderId(), OrderStatus.SHIPPED));
            }
        }
    }
}
