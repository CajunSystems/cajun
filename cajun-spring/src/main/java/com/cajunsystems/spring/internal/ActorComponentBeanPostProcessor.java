package com.cajunsystems.spring.internal;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.backpressure.BackpressureBuilder;
import com.cajunsystems.builder.ActorBuilder;
import com.cajunsystems.handler.Handler;
import com.cajunsystems.spring.CajunActorRegistry;
import com.cajunsystems.spring.annotation.ActorComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;

import java.beans.Introspector;

/**
 * {@link BeanPostProcessor} that detects {@link ActorComponent}-annotated Spring beans and
 * spawns a Cajun actor for each one.
 *
 * <p>After every bean is fully initialized (constructor + field injection + {@code @PostConstruct}),
 * this processor checks whether it carries an {@link ActorComponent} annotation. If it does and
 * it implements {@link Handler}, an actor is spawned using the live bean instance — meaning it
 * has all its Spring-injected dependencies available when messages are processed.
 *
 * <p>The resulting {@link Pid} is recorded in the {@link CajunActorRegistry} keyed by both the
 * handler class and the actor ID so that other beans can look it up.
 */
public class ActorComponentBeanPostProcessor implements BeanPostProcessor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(ActorComponentBeanPostProcessor.class);

    private final ActorSystem actorSystem;
    private final CajunActorRegistry registry;

    public ActorComponentBeanPostProcessor(ActorSystem actorSystem, CajunActorRegistry registry) {
        this.actorSystem = actorSystem;
        this.registry = registry;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        ActorComponent annotation = AnnotationUtils.findAnnotation(bean.getClass(), ActorComponent.class);
        if (annotation == null) {
            return bean;
        }

        if (!(bean instanceof Handler)) {
            throw new org.springframework.beans.factory.BeanCreationException(beanName,
                    "@ActorComponent is only supported on Handler<Message> implementations. "
                            + bean.getClass().getName() + " does not implement Handler. "
                            + "For StatefulHandler actors, spawn them manually in a @Bean method.");
        }

        String actorId = resolveActorId(annotation, bean.getClass());

        @SuppressWarnings("unchecked")
        Handler<Object> handler = (Handler<Object>) bean;

        @SuppressWarnings("unchecked")
        ActorBuilder<Object> builder = (ActorBuilder<Object>) actorSystem.actorOf(handler);
        builder.withId(actorId);

        Pid pid = spawnWithPreset(builder, annotation.backpressurePreset());
        registry.register(bean.getClass(), actorId, pid);

        log.info("Spawned actor '{}' for handler {}", actorId, bean.getClass().getSimpleName());
        return bean;
    }

    /**
     * Run before most other post-processors so actors are available for {@code @InjectActor}
     * processing (which uses {@code SmartInitializingSingleton} and therefore runs later).
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 100;
    }

    // --- Helpers ---

    private String resolveActorId(ActorComponent annotation, Class<?> handlerClass) {
        String id = annotation.id();
        if (id != null && !id.isBlank()) {
            return id;
        }
        // Default: simple class name with first letter lower-cased
        return Introspector.decapitalize(handlerClass.getSimpleName());
    }

    /**
     * Spawns the actor and, if a backpressure preset is specified, applies it via
     * {@link ActorSystem#configureBackpressure(Pid)}.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Pid spawnWithPreset(ActorBuilder<Object> builder, String preset) {
        Pid pid = builder.spawn();
        if (preset != null && !preset.isBlank()) {
            BackpressureBuilder<?> bpBuilder = actorSystem.configureBackpressure(pid);
            switch (preset.toUpperCase()) {
                case "TIME_CRITICAL" -> bpBuilder.presetTimeCritical();
                case "RELIABLE" -> bpBuilder.presetReliable();
                case "HIGH_THROUGHPUT" -> bpBuilder.presetHighThroughput();
                default -> log.warn("Unknown backpressure preset '{}' on actor '{}' — ignoring.",
                        preset, pid.actorId());
            }
            bpBuilder.apply();
        }
        return pid;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        return bean;
    }
}
