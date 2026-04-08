package com.cajunsystems.spring.annotation;

import java.lang.annotation.*;

/**
 * Injects a {@link com.cajunsystems.Pid} or {@link com.cajunsystems.spring.ActorRef} for a
 * registered Cajun actor into the annotated field.
 *
 * <p>The actor must already be registered in the {@link com.cajunsystems.spring.CajunActorRegistry}
 * — either automatically via {@link ActorComponent} or manually via
 * {@link com.cajunsystems.spring.CajunActorRegistry#register}. Injection is performed after all
 * singleton beans have been instantiated (i.e., via {@code SmartInitializingSingleton}), so the
 * field is <em>not</em> available during {@code @PostConstruct} methods.
 *
 * <p>Specify the actor by handler class (recommended) or by actor ID:
 *
 * <pre>{@code
 * // By handler class (type-safe):
 * @InjectActor(OrderHandler.class)
 * private Pid orderPid;
 *
 * // With ActorRef for type-safe messaging:
 * @InjectActor(OrderHandler.class)
 * private ActorRef<OrderMessage> orderActor;
 *
 * // By explicit actor ID:
 * @InjectActor(id = "order-processor")
 * private Pid orderPid;
 * }</pre>
 *
 * <p>If both {@link #value()} and {@link #id()} are specified, {@link #id()} takes priority.
 *
 * <p><strong>Note:</strong> If you need the actor available during {@code @PostConstruct},
 * inject {@link com.cajunsystems.spring.CajunActorRegistry} instead and call
 * {@code registry.getByHandlerClass()} there.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface InjectActor {

    /**
     * The handler class whose actor should be injected. Must be registered in the
     * {@link com.cajunsystems.spring.CajunActorRegistry}.
     *
     * @return the handler class, or {@code Object.class} when using {@link #id()} instead
     */
    Class<?> value() default Object.class;

    /**
     * The explicit actor ID to look up in the {@link com.cajunsystems.spring.CajunActorRegistry}.
     * Takes priority over {@link #value()} when both are specified.
     *
     * @return the actor ID, or empty string to use {@link #value()}
     */
    String id() default "";
}
