package com.cajunsystems.spring.annotation;

import com.cajunsystems.spring.CajunAutoConfiguration;
import org.springframework.context.annotation.Import;

import java.lang.annotation.*;

/**
 * Enables the Cajun actor system Spring integration in a {@code @Configuration} class.
 *
 * <p>When using Spring Boot, the {@link CajunAutoConfiguration} is applied automatically via
 * classpath detection — you do <em>not</em> need this annotation. Use it explicitly when:
 *
 * <ul>
 *   <li>You are not using Spring Boot auto-configuration, or
 *   <li>You want to express the dependency on Cajun clearly at the entry-point of your
 *       application context.
 * </ul>
 *
 * <pre>{@code
 * @Configuration
 * @EnableCajun
 * public class AppConfig {
 *     // ActorSystem, CajunActorRegistry, etc. are now available as beans
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(CajunAutoConfiguration.class)
public @interface EnableCajun {
}
