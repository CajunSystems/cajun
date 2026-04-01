package examples;

import com.cajunsystems.ActorContext;
import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.functional.EffectActorBuilder;
import com.cajunsystems.functional.capabilities.ConsoleLogHandler;
import com.cajunsystems.functional.capabilities.LogCapability;
import com.cajunsystems.handler.StatefulHandler;
import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.capability.Capability;
import com.cajunsystems.roux.capability.CapabilityHandler;
import com.cajunsystems.roux.data.Unit;
import org.junit.jupiter.api.*;

import java.io.Serializable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates {@link StatefulHandler} and {@link EffectActorBuilder} actors
 * working side-by-side in the same ActorSystem.
 *
 * <p>A shopping cart actor ({@code StatefulHandler}) manages cart state immutably.
 * An audit actor ({@code EffectActorBuilder} + {@code LogCapability}) logs each
 * cart mutation. The cart notifies the audit actor after every change (fire-and-forget),
 * and the test queries the cart total via the ask pattern.
 *
 * <p>Key patterns shown:
 * <ul>
 *   <li>Wiring a StatefulHandler to an EffectActorBuilder via constructor injection</li>
 *   <li>Fire-and-forget from a StatefulHandler to an effect actor via {@code context.tell}</li>
 *   <li>Ask-pattern query on a StatefulHandler using {@code system.ask()}</li>
 * </ul>
 */
class EffectStatefulCompositionExample {

    // --- Domain model ---

    record CartItem(String name, double price) implements Serializable {}

    record CartState(List<CartItem> items) implements Serializable {
        CartState { items = List.copyOf(items); }

        static CartState empty() { return new CartState(List.of()); }

        CartState add(CartItem item) {
            var next = new ArrayList<>(items);
            next.add(item);
            return new CartState(next);
        }

        double total() { return items.stream().mapToDouble(CartItem::price).sum(); }
    }

    // --- Cart messages ---

    sealed interface CartMessage extends Serializable
            permits CartMessage.AddItem, CartMessage.GetTotal {
        record AddItem(String name, double price) implements CartMessage {}
        record GetTotal() implements CartMessage {}
    }

    // --- Audit messages (sent to the effect actor) ---

    sealed interface AuditMessage extends Serializable permits AuditMessage.ItemAdded {
        record ItemAdded(String name, double price, double runningTotal) implements AuditMessage {}
    }

    // --- StatefulHandler: the shopping cart ---

    /**
     * Manages an immutable shopping cart. After each AddItem mutation, notifies
     * the audit effect actor. Replies to GetTotal queries via the ask pattern.
     */
    static class CartHandler implements StatefulHandler<CartState, CartMessage> {
        private final Pid auditPid;

        CartHandler(Pid auditPid) { this.auditPid = auditPid; }

        @Override
        public CartState receive(CartMessage message, CartState state, ActorContext context) {
            if (message instanceof CartMessage.AddItem add) {
                CartState newState = state.add(new CartItem(add.name(), add.price()));
                // Fire-and-forget to the effect actor — never blocks the cart
                context.tell(auditPid,
                        new AuditMessage.ItemAdded(add.name(), add.price(), newState.total()));
                return newState;
            } else if (message instanceof CartMessage.GetTotal) {
                // Reply to the ask-pattern caller with the current total
                context.getSender().ifPresent(s -> context.tell(s, state.total()));
                return state;
            }
            return state;
        }
    }

    private ActorSystem system;

    @BeforeEach
    void setUp() { system = new ActorSystem(); }

    @AfterEach
    void tearDown() { system.shutdown(); }

    /**
     * Shopping cart accumulates items and notifies the audit effect actor.
     * After processing, the test queries the cart total via ask pattern.
     */
    @Test
    void shoppingCartWithEffectAuditActor() throws Exception {
        String id = UUID.randomUUID().toString().substring(0, 8);
        CountDownLatch auditLatch = new CountDownLatch(3);
        CapabilityHandler<Capability<?>> logHandler = new ConsoleLogHandler().widen();

        // Effect actor: logs each cart mutation via LogCapability
        Pid auditPid = new EffectActorBuilder<>(
            system,
            (AuditMessage msg) -> {
                if (msg instanceof AuditMessage.ItemAdded added) {
                    return Effect.<RuntimeException, Unit>generate(ctx -> {
                        ctx.perform(new LogCapability.Info(
                                "Cart audit: added '" + added.name() +
                                "' $" + added.price() +
                                " | running total: $" + added.runningTotal()));
                        auditLatch.countDown();
                        return Unit.unit();
                    }, logHandler);
                }
                return Effect.succeed(Unit.unit());
            }
        ).withId("audit-" + id).spawn();

        // Stateful cart actor — passes the audit Pid via constructor
        Pid cartPid = system.statefulActorOf(new CartHandler(auditPid), CartState.empty())
                .withId("cart-" + id)
                .spawn();

        // Mutate cart
        cartPid.tell(new CartMessage.AddItem("Widget",   29.99));
        cartPid.tell(new CartMessage.AddItem("Gadget",   49.99));
        cartPid.tell(new CartMessage.AddItem("Doohickey", 9.99));

        // Verify the effect actor processed all audit events
        assertTrue(auditLatch.await(5, TimeUnit.SECONDS),
                "Effect actor should have logged 3 audit events");

        // Query the stateful actor for total via ask pattern
        double total = system.<CartMessage, Double>ask(
                cartPid, new CartMessage.GetTotal(), Duration.ofSeconds(5)).get();

        assertEquals(89.97, total, 0.01);
    }

    /**
     * Verifies that state accumulates correctly across multiple mutations and that
     * the StatefulHandler actor and EffectActorBuilder actor run independently.
     */
    @Test
    void cartStateAccumulatesCorrectlyWithEffectAuditLogging() throws Exception {
        String id = UUID.randomUUID().toString().substring(0, 8);
        CountDownLatch auditLatch = new CountDownLatch(2);
        CapabilityHandler<Capability<?>> logHandler = new ConsoleLogHandler().widen();

        Pid auditPid = new EffectActorBuilder<>(
            system,
            (AuditMessage msg) -> Effect.<RuntimeException, Unit>generate(ctx -> {
                if (msg instanceof AuditMessage.ItemAdded added) {
                    ctx.perform(new LogCapability.Debug("audit: " + added.name()));
                }
                auditLatch.countDown();
                return Unit.unit();
            }, logHandler)
        ).withId("audit-b-" + id).spawn();

        Pid cartPid = system.statefulActorOf(new CartHandler(auditPid), CartState.empty())
                .withId("cart-b-" + id)
                .spawn();

        cartPid.tell(new CartMessage.AddItem("Alpha", 10.00));
        cartPid.tell(new CartMessage.AddItem("Beta",  20.00));

        assertTrue(auditLatch.await(5, TimeUnit.SECONDS));

        double total = system.<CartMessage, Double>ask(
                cartPid, new CartMessage.GetTotal(), Duration.ofSeconds(5)).get();

        assertEquals(30.0, total, 0.001);
    }
}
