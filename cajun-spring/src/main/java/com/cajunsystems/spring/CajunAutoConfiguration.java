package com.cajunsystems.spring;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.backpressure.BackpressureStrategy;
import com.cajunsystems.config.BackpressureConfig;
import com.cajunsystems.config.ThreadPoolFactory;
import com.cajunsystems.spring.internal.ActorComponentBeanPostProcessor;
import com.cajunsystems.spring.internal.InjectActorBeanPostProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;

import java.util.List;

/**
 * Spring Boot auto-configuration for the Cajun actor system.
 *
 * <p>This configuration is activated automatically when {@code cajun} (the {@link ActorSystem}
 * class) is on the classpath. It creates and manages the following beans:
 *
 * <ul>
 *   <li>{@link ActorSystem} — the central actor system, configured via {@link CajunProperties}
 *   <li>{@link CajunActorRegistry} — registry for looking up spawned actors
 *   <li>{@link ActorComponentBeanPostProcessor} — auto-spawns {@code @ActorComponent} handlers
 *   <li>{@link InjectActorBeanPostProcessor} — processes {@code @InjectActor} field injection
 * </ul>
 *
 * <h2>Customization</h2>
 *
 * <p>To take full control of the {@link ActorSystem} creation, declare your own
 * {@code @Bean ActorSystem} — this auto-configuration will back off. For finer-grained
 * customization without replacing the whole bean, register one or more
 * {@link CajunActorSystemCustomizer} beans.
 *
 * <h2>Configuration</h2>
 *
 * <p>All settings are exposed through the {@code cajun.*} namespace in
 * {@code application.yml}/{@code application.properties}:
 *
 * <pre>{@code
 * cajun:
 *   thread-pool:
 *     workload: IO_BOUND           # IO_BOUND | CPU_BOUND | MIXED
 *   backpressure:
 *     enabled: true
 *     strategy: DROP_OLDEST
 *     warning-threshold: 0.7
 *     critical-threshold: 0.9
 * }</pre>
 */
@AutoConfiguration
@ConditionalOnClass(ActorSystem.class)
@EnableConfigurationProperties(CajunProperties.class)
public class CajunAutoConfiguration implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(CajunAutoConfiguration.class);

    private ActorSystem actorSystem;

    /**
     * Creates the {@link ActorSystem} bean, configured from {@link CajunProperties}.
     *
     * <p>All registered {@link CajunActorSystemCustomizer} beans are applied in order after
     * the system is built.
     *
     * @param properties  Cajun configuration properties
     * @param customizers optional customizers for the actor system
     * @return the configured {@link ActorSystem}
     */
    @Bean
    @ConditionalOnMissingBean
    public ActorSystem actorSystem(CajunProperties properties,
                                   ObjectProvider<CajunActorSystemCustomizer> customizers) {
        ThreadPoolFactory threadPoolFactory = buildThreadPoolFactory(properties.getThreadPool());
        BackpressureConfig backpressureConfig = buildBackpressureConfig(properties.getBackpressure());

        ActorSystem system;
        if (properties.getBackpressure().isEnabled()) {
            system = new ActorSystem(threadPoolFactory, backpressureConfig);
            log.info("Cajun ActorSystem created with thread-pool type '{}' and backpressure strategy '{}'",
                    properties.getThreadPool().getExecutorType(),
                    properties.getBackpressure().getStrategy());
        } else {
            system = new ActorSystem(threadPoolFactory);
            log.info("Cajun ActorSystem created with thread-pool type '{}'",
                    properties.getThreadPool().getExecutorType());
        }

        // Apply customizers in order
        List<CajunActorSystemCustomizer> customizerList = customizers.orderedStream()
                .sorted(AnnotationAwareOrderComparator.INSTANCE)
                .toList();
        for (CajunActorSystemCustomizer customizer : customizerList) {
            customizer.customize(system);
        }

        this.actorSystem = system;
        return system;
    }

    /**
     * Creates the {@link CajunActorRegistry} bean used to look up spawned actor {@link com.cajunsystems.Pid}s.
     *
     * @return the actor registry
     */
    @Bean
    @ConditionalOnMissingBean
    public CajunActorRegistry cajunActorRegistry() {
        return new CajunActorRegistry();
    }

    /**
     * Creates the {@link ActorComponentBeanPostProcessor} that auto-spawns handlers annotated
     * with {@code @ActorComponent}.
     *
     * @param actorSystem the actor system used to spawn actors
     * @param registry    the registry where spawned actors are recorded
     * @return the bean post-processor
     */
    @Bean
    @ConditionalOnMissingBean
    public ActorComponentBeanPostProcessor actorComponentBeanPostProcessor(ActorSystem actorSystem,
                                                                            CajunActorRegistry registry) {
        return new ActorComponentBeanPostProcessor(actorSystem, registry);
    }

    /**
     * Creates the {@link InjectActorBeanPostProcessor} that resolves {@code @InjectActor}
     * field annotations.
     *
     * @param registry the registry used to look up actor {@link com.cajunsystems.Pid}s
     * @return the bean post-processor
     */
    @Bean
    @ConditionalOnMissingBean
    public InjectActorBeanPostProcessor injectActorBeanPostProcessor(CajunActorRegistry registry) {
        return new InjectActorBeanPostProcessor(registry);
    }

    /**
     * Shuts down the actor system gracefully when the Spring application context is closed.
     */
    @Override
    public void destroy() {
        if (actorSystem != null) {
            log.info("Shutting down Cajun ActorSystem...");
            actorSystem.shutdown();
            log.info("Cajun ActorSystem shut down.");
        }
    }

    // --- Private factory helpers ---

    private ThreadPoolFactory buildThreadPoolFactory(CajunProperties.ThreadPool config) {
        ThreadPoolFactory factory = new ThreadPoolFactory()
                .setSchedulerThreads(config.getSchedulerThreads())
                .setUseSharedExecutor(config.isUseSharedExecutor())
                .setPreferVirtualThreads(config.isPreferVirtualThreads());

        // Workload preset takes priority over explicit executor-type
        if (config.getWorkload() != null && !config.getWorkload().isBlank()) {
            ThreadPoolFactory.WorkloadType workloadType;
            try {
                workloadType = ThreadPoolFactory.WorkloadType.valueOf(config.getWorkload().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Invalid cajun.thread-pool.workload value: '" + config.getWorkload()
                                + "'. Valid values are: IO_BOUND, CPU_BOUND, MIXED");
            }
            factory.optimizeFor(workloadType);
        } else {
            ThreadPoolFactory.ThreadPoolType executorType;
            try {
                executorType = ThreadPoolFactory.ThreadPoolType.valueOf(
                        config.getExecutorType().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Invalid cajun.thread-pool.executor-type value: '" + config.getExecutorType()
                                + "'. Valid values are: VIRTUAL, FIXED, WORK_STEALING");
            }
            factory.setExecutorType(executorType);

            if (executorType == ThreadPoolFactory.ThreadPoolType.FIXED) {
                factory.setFixedPoolSize(config.getFixedPoolSize());
            } else if (executorType == ThreadPoolFactory.ThreadPoolType.WORK_STEALING) {
                factory.setWorkStealingParallelism(config.getWorkStealingParallelism());
            }
        }

        return factory;
    }

    private BackpressureConfig buildBackpressureConfig(CajunProperties.Backpressure config) {
        BackpressureStrategy strategy;
        try {
            strategy = BackpressureStrategy.valueOf(config.getStrategy().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid cajun.backpressure.strategy value: '" + config.getStrategy()
                            + "'. Valid values are: BLOCK, DROP_NEW, DROP_OLDEST");
        }

        BackpressureConfig bpConfig = new BackpressureConfig()
                .setStrategy(strategy)
                .setWarningThreshold(config.getWarningThreshold())
                .setCriticalThreshold(config.getCriticalThreshold())
                .setRecoveryThreshold(config.getRecoveryThreshold())
                .setHighWatermark(config.getHighWatermark())
                .setLowWatermark(config.getLowWatermark())
                .setMinCapacity(config.getMinCapacity());

        if (config.getMaxCapacity() > 0) {
            bpConfig.setMaxCapacity(config.getMaxCapacity());
        }

        return bpConfig;
    }
}
