package com.cajunsystems.example.order.config;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.example.order.handler.InventoryHandler;
import com.cajunsystems.example.order.handler.InventoryHandler.InventoryState;
import com.cajunsystems.example.order.handler.OrderTrackerHandler;
import com.cajunsystems.example.order.handler.OrderTrackerHandler.TrackerState;
import com.cajunsystems.spring.CajunActorRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * Registers the two <em>stateful</em> actors that cannot use {@code @ActorComponent}
 * because they require an initial state value.
 *
 * <h2>Pattern</h2>
 * <ol>
 *   <li>Create the handler with its Spring-managed dependencies.</li>
 *   <li>Spawn via {@code system.statefulActorOf(handler, initialState)}.</li>
 *   <li>Register the resulting {@link Pid} in {@link CajunActorRegistry} so other
 *       actors and controllers can look it up by ID.</li>
 * </ol>
 */
@Configuration
public class ActorConfig {

    /**
     * Inventory actor — owns all stock levels.
     *
     * <p>Initial stock is seeded here; in a real system you would load it from
     * a database or an external inventory service.
     */
    @Bean
    public Pid inventoryActor(ActorSystem system, CajunActorRegistry registry) {
        Map<String, Integer> initialStock = new HashMap<>();
        initialStock.put("LAPTOP-001",   50);
        initialStock.put("MOUSE-001",   200);
        initialStock.put("KEYBOARD-001", 150);
        initialStock.put("MONITOR-001",  30);

        InventoryHandler handler = new InventoryHandler(registry);
        Pid pid = system.statefulActorOf(handler, new InventoryState(initialStock))
                .withId("inventory")
                .spawn();

        registry.register(InventoryHandler.class, "inventory", pid);
        return pid;
    }

    /**
     * Order tracker actor — maintains a live status map for every order.
     * Used by the REST layer via the ask pattern.
     */
    @Bean
    public Pid orderTrackerActor(ActorSystem system, CajunActorRegistry registry) {
        OrderTrackerHandler handler = new OrderTrackerHandler();
        Pid pid = system.statefulActorOf(handler, new TrackerState(new HashMap<>()))
                .withId("order-tracker")
                .spawn();

        registry.register(OrderTrackerHandler.class, "order-tracker", pid);
        return pid;
    }
}
