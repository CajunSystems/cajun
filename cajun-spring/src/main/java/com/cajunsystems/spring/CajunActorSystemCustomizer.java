package com.cajunsystems.spring;

import com.cajunsystems.ActorSystem;

/**
 * Callback interface for customizing the {@link ActorSystem} after it has been created by the
 * auto-configuration but before it is exposed as a Spring bean.
 *
 * <p>Implement this interface and register your implementation as a Spring bean to perform
 * additional configuration such as registering persistence providers, setting the default ID
 * strategy, or configuring cluster settings:
 *
 * <pre>{@code
 * @Bean
 * public CajunActorSystemCustomizer persistenceCustomizer(PersistenceProvider provider) {
 *     return system -> system.setPersistenceProvider(provider);
 * }
 * }</pre>
 *
 * <p>Multiple customizers are applied in the order determined by Spring's {@code @Order} annotation
 * or the {@code Ordered} interface.
 */
@FunctionalInterface
public interface CajunActorSystemCustomizer {

    /**
     * Customize the given {@link ActorSystem}.
     *
     * @param actorSystem the actor system to customize; never {@code null}
     */
    void customize(ActorSystem actorSystem);
}
