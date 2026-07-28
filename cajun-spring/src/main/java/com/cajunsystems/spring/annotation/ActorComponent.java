package com.cajunsystems.spring.annotation;

import org.springframework.stereotype.Component;

import java.lang.annotation.*;

/**
 * Marks a {@code Handler<Message>} implementation as both a Spring bean and a Cajun actor.
 *
 * <p>When the application context starts, the {@code ActorComponentBeanPostProcessor} detects
 * all beans annotated with {@code @ActorComponent}, spawns an actor for each one using the
 * handler instance (with its Spring-injected dependencies fully wired), and registers the
 * resulting {@link com.cajunsystems.Pid} in the {@link com.cajunsystems.spring.CajunActorRegistry}.
 *
 * <p>This lets you write actor handlers as ordinary Spring beans with full dependency injection:
 *
 * <pre>{@code
 * @ActorComponent(id = "order-processor")
 * public class OrderHandler implements Handler<OrderMessage> {
 *
 *     private final OrderRepository repository;
 *
 *     public OrderHandler(OrderRepository repository) {
 *         this.repository = repository;    // injected by Spring
 *     }
 *
 *     @Override
 *     public void receive(OrderMessage message, ActorContext context) {
 *         switch (message) {
 *             case OrderMessage.Place place -> repository.save(place.order());
 *             case OrderMessage.Cancel cancel -> repository.cancel(cancel.orderId());
 *         }
 *     }
 * }
 * }</pre>
 *
 * <p>Then look up the actor anywhere:
 *
 * <pre>{@code
 * // Via registry
 * Pid pid = registry.getByHandlerClass(OrderHandler.class);
 *
 * // Via field injection
 * @InjectActor(OrderHandler.class)
 * private Pid orderPid;
 * }</pre>
 *
 * <h2>Stateful actors</h2>
 *
 * <p>{@code @ActorComponent} only supports stateless {@code Handler<Message>} implementations.
 * For {@code StatefulHandler} actors, spawn them manually in a {@code @Bean} method and register
 * them with the {@link com.cajunsystems.spring.CajunActorRegistry}:
 *
 * <pre>{@code
 * @Bean
 * public Pid counterActor(ActorSystem system, CajunActorRegistry registry) {
 *     Pid pid = system.statefulActorOf(CounterHandler.class, 0)
 *         .withId("counter")
 *         .spawn();
 *     registry.register(CounterHandler.class, "counter", pid);
 *     return pid;
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface ActorComponent {

    /**
     * The actor's identifier within the actor system.
     *
     * <p>If left blank, the simple class name of the handler (with the first letter lower-cased)
     * is used as the ID. For example, {@code OrderHandler} becomes {@code "orderHandler"}.
     *
     * @return the actor ID, or empty string to derive from the class name
     */
    String id() default "";

    /**
     * Optional backpressure preset to apply when spawning this actor.
     *
     * <p>Accepted values: {@code ""} (none), {@code "TIME_CRITICAL"}, {@code "RELIABLE"},
     * {@code "HIGH_THROUGHPUT"}.
     *
     * @return the backpressure preset name, or empty string for none
     */
    String backpressurePreset() default "";
}
