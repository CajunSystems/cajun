package com.cajunsystems.example.order.handler;

import com.cajunsystems.ActorContext;
import com.cajunsystems.example.order.domain.OrderStatus;
import com.cajunsystems.example.order.message.InventoryCommand;
import com.cajunsystems.example.order.message.PaymentCommand;
import com.cajunsystems.example.order.message.TrackerCommand;
import com.cajunsystems.handler.StatefulHandler;
import com.cajunsystems.spring.CajunActorRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Stateful actor that owns the in-memory inventory.
 *
 * <p>State ({@link InventoryState}) is a simple immutable map of SKU → available quantity.
 * Each message handler returns a new state, keeping the actor thread-safe by design.
 *
 * <h2>cajun-spring features demonstrated</h2>
 * <ul>
 *   <li>Stateful actor created manually via a {@code @Bean} method in {@link com.cajunsystems.example.order.config.ActorConfig}
 *       with initial stock seeded at startup</li>
 *   <li>{@link CajunActorRegistry} passed via constructor so the handler can reach
 *       downstream actors lazily inside {@code receive()}</li>
 *   <li>Immutable state record — the canonical pattern for {@code StatefulHandler}</li>
 * </ul>
 */
public class InventoryHandler implements StatefulHandler<InventoryHandler.InventoryState, InventoryCommand> {

    private static final Logger log = LoggerFactory.getLogger(InventoryHandler.class);

    private final CajunActorRegistry registry;

    public InventoryHandler(CajunActorRegistry registry) {
        this.registry = registry;
    }

    // ---- State ----

    /**
     * Immutable snapshot of the warehouse stock.
     * Declared as an inner record so it stays co-located with the handler.
     */
    public record InventoryState(Map<String, Integer> stock) implements Serializable {

        // Compact constructor ensures the internal map is always unmodifiable.
        public InventoryState {
            stock = Collections.unmodifiableMap(new HashMap<>(stock));
        }

        public boolean hasStock(String sku, int required) {
            return stock.getOrDefault(sku, 0) >= required;
        }

        public InventoryState deduct(String sku, int qty) {
            Map<String, Integer> updated = new HashMap<>(stock);
            updated.merge(sku, -qty, Integer::sum);
            return new InventoryState(updated);
        }

        public InventoryState add(String sku, int qty) {
            Map<String, Integer> updated = new HashMap<>(stock);
            updated.merge(sku, qty, Integer::sum);
            return new InventoryState(updated);
        }
    }

    // ---- Message handling ----

    @Override
    public InventoryState receive(InventoryCommand cmd, InventoryState state, ActorContext ctx) {
        return switch (cmd) {

            case InventoryCommand.Reserve reserve -> {
                // Check that every item has sufficient stock
                boolean allAvailable = reserve.items().stream()
                        .allMatch(item -> state.hasStock(item.sku(), item.quantity()));

                if (!allAvailable) {
                    log.warn("[inventory] Insufficient stock for order {}", reserve.orderId());
                    registry.<TrackerCommand>getTypedPid("order-tracker")
                            .tell(new TrackerCommand.UpdateStatus(reserve.orderId(), OrderStatus.FAILED_INVENTORY));
                    yield state; // state unchanged
                }

                // Deduct stock for each item and update tracker
                InventoryState updated = state;
                for (var item : reserve.items()) {
                    updated = updated.deduct(item.sku(), item.quantity());
                }

                log.info("[inventory] Reserved stock for order {}, forwarding to payment", reserve.orderId());
                registry.<TrackerCommand>getTypedPid("order-tracker")
                        .tell(new TrackerCommand.UpdateStatus(reserve.orderId(), OrderStatus.RESERVED));
                registry.<PaymentCommand>getTypedPid("payment")
                        .tell(new PaymentCommand.Charge(
                                reserve.orderId(),
                                reserve.customerId(),
                                reserve.shippingAddress(),
                                reserve.items(),
                                reserve.total()
                        ));

                yield updated;
            }

            case InventoryCommand.Release release -> {
                // Return stock on payment failure
                InventoryState updated = state;
                for (var item : release.items()) {
                    updated = updated.add(item.sku(), item.quantity());
                }
                log.info("[inventory] Released stock for failed order {}", release.orderId());
                yield updated;
            }

            case InventoryCommand.Restock restock -> {
                InventoryState updated = state.add(restock.sku(), restock.quantity());
                log.info("[inventory] Restocked {} × {} (new qty: {})",
                        restock.quantity(), restock.sku(),
                        updated.stock().get(restock.sku()));
                yield updated;
            }
        };
    }
}
