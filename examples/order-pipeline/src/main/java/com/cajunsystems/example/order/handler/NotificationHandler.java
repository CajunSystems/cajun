package com.cajunsystems.example.order.handler;

import com.cajunsystems.ActorContext;
import com.cajunsystems.example.order.message.NotificationCommand;
import com.cajunsystems.example.order.service.NotificationService;
import com.cajunsystems.handler.Handler;
import com.cajunsystems.spring.annotation.ActorComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Terminal stage of the pipeline. Dispatches customer notifications via the
 * Spring-injected {@link NotificationService}.
 *
 * <p>This handler has no downstream actors — it is a sink. Because it never
 * back-pressures the pipeline, it uses the default (no preset) backpressure config.
 */
@ActorComponent(id = "notification")
public class NotificationHandler implements Handler<NotificationCommand> {

    private static final Logger log = LoggerFactory.getLogger(NotificationHandler.class);

    private final NotificationService notificationService;

    public NotificationHandler(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Override
    public void receive(NotificationCommand cmd, ActorContext ctx) {
        switch (cmd) {
            case NotificationCommand.OrderUpdate update ->
                notificationService.send(update.customerId(), update.orderId(), update.status());
        }
    }
}
