package com.cajunsystems.spring.internal;

import com.cajunsystems.Pid;
import com.cajunsystems.spring.CajunActorRegistry;
import com.cajunsystems.spring.TypedPid;
import com.cajunsystems.spring.annotation.InjectActor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link BeanPostProcessor} that resolves {@link InjectActor}-annotated fields.
 *
 * <p>During the normal bean initialization phase, this processor <em>collects</em> all fields
 * annotated with {@link InjectActor} but does not inject them yet. Injection is deferred until
 * {@link SmartInitializingSingleton#afterSingletonsInstantiated()} is called — which happens
 * after every singleton bean in the context has been created. This guarantees that all
 * {@code @ActorComponent} actors have been spawned (and thus registered in the
 * {@link CajunActorRegistry}) before any {@code @InjectActor} field is resolved.
 *
 * <p><strong>Implication:</strong> {@code @InjectActor} fields are <em>not</em> available
 * during {@code @PostConstruct} methods. If you need the {@link Pid} that early, inject
 * {@link CajunActorRegistry} directly and call
 * {@link CajunActorRegistry#getByHandlerClass(Class)} inside {@code @PostConstruct}.
 *
 * <p>Supported field types:
 * <ul>
 *   <li>{@link Pid} — the raw actor process identifier
 *   <li>{@link TypedPid} — a type-safe wrapper around {@link Pid}
 * </ul>
 */
public class InjectActorBeanPostProcessor implements BeanPostProcessor, SmartInitializingSingleton, Ordered {

    private static final Logger log = LoggerFactory.getLogger(InjectActorBeanPostProcessor.class);

    private final CajunActorRegistry registry;

    /**
     * Pending injections collected during the bean initialization phase. Each entry holds
     * the bean instance and the field to inject into.
     */
    private final List<Map.Entry<Object, Field>> pendingInjections = new ArrayList<>();

    public InjectActorBeanPostProcessor(CajunActorRegistry registry) {
        this.registry = registry;
    }

    /**
     * Scans for {@link InjectActor}-annotated fields on the bean and enqueues them for deferred
     * injection. The bean itself is returned unchanged.
     */
    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        Class<?> clazz = bean.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                if (field.isAnnotationPresent(InjectActor.class)) {
                    validateFieldType(field, beanName);
                    pendingInjections.add(Map.entry(bean, field));
                }
            }
            clazz = clazz.getSuperclass();
        }
        return bean;
    }

    /**
     * Performs the actual field injection after all singletons have been instantiated.
     * At this point, all {@code @ActorComponent} actors are guaranteed to be spawned.
     */
    @Override
    public void afterSingletonsInstantiated() {
        int count = pendingInjections.size();
        for (var entry : pendingInjections) {
            injectField(entry.getKey(), entry.getValue());
        }
        pendingInjections.clear();
        log.debug("Completed @InjectActor field injection for {} field(s).", count);
    }

    /**
     * Runs after {@link ActorComponentBeanPostProcessor} ({@code LOWEST_PRECEDENCE - 100}),
     * so actors are always spawned before injection is collected.
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    // --- Private helpers ---

    private void validateFieldType(Field field, String beanName) {
        Class<?> fieldType = field.getType();
        if (!Pid.class.isAssignableFrom(fieldType) && !TypedPid.class.isAssignableFrom(fieldType)) {
            throw new BeanCreationException(beanName,
                    "@InjectActor field '" + field.getName() + "' in " + field.getDeclaringClass().getName()
                            + " must be of type Pid or TypedPid, but was: " + fieldType.getName());
        }

        InjectActor annotation = field.getAnnotation(InjectActor.class);
        boolean hasId = annotation.id() != null && !annotation.id().isBlank();
        boolean hasClass = annotation.value() != Object.class;

        if (!hasId && !hasClass) {
            throw new BeanCreationException(beanName,
                    "@InjectActor field '" + field.getName() + "' in " + field.getDeclaringClass().getName()
                            + " must specify either a handler class (value) or an actor ID (id).");
        }
    }

    private void injectField(Object bean, Field field) {
        InjectActor annotation = field.getAnnotation(InjectActor.class);

        Pid pid;
        if (annotation.id() != null && !annotation.id().isBlank()) {
            pid = registry.getByActorId(annotation.id());
        } else {
            pid = registry.getByHandlerClass(annotation.value());
        }

        Object valueToInject;
        if (TypedPid.class.isAssignableFrom(field.getType())) {
            valueToInject = new TypedPid<>(pid);
        } else {
            valueToInject = pid;
        }

        field.setAccessible(true);
        try {
            field.set(bean, valueToInject);
            log.debug("Injected {} '{}' into field '{}' of {}",
                    field.getType().getSimpleName(),
                    pid.actorId(),
                    field.getName(),
                    bean.getClass().getSimpleName());
        } catch (IllegalAccessException e) {
            throw new BeanCreationException(
                    "@InjectActor failed to inject field '" + field.getName()
                            + "' in " + bean.getClass().getName(), e);
        }
    }
}
