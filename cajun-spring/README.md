# cajun-spring

Spring Boot integration for the [Cajun](https://github.com/cajunsystems/cajun) actor system. Provides auto-configuration, dependency injection, and Spring-idiomatic wrappers so you can use Cajun actors as first-class citizens in a Spring Boot application.

## Installation

Add the module to your project alongside `cajun` (Spring Boot 3.x / Spring 6.x required):

```groovy
// Gradle
dependencies {
    implementation 'com.cajunsystems:cajun:0.7.0'
    implementation 'com.cajunsystems:cajun-spring:0.7.0'
    implementation 'org.springframework.boot:spring-boot-starter'
}
```

```xml
<!-- Maven -->
<dependency>
    <groupId>com.cajunsystems</groupId>
    <artifactId>cajun-spring</artifactId>
    <version>0.7.0</version>
</dependency>
```

No extra setup needed — the `ActorSystem` bean is created automatically via Spring Boot's auto-configuration.

---

## Quick Start

### 1. Inject the ActorSystem

The simplest integration: autowire `ActorSystem` and spawn actors manually, exactly as you would outside of Spring.

```java
@Service
public class OrderService {

    private final ActorSystem actorSystem;
    private final Pid orderPid;

    public OrderService(ActorSystem actorSystem) {
        this.actorSystem = actorSystem;
        this.orderPid = actorSystem.actorOf(OrderHandler.class)
                .withId("order-processor")
                .spawn();
    }

    public void placeOrder(Order order) {
        orderPid.tell(new OrderMessage.Place(order));
    }
}
```

### 2. Declare actors with `@ActorComponent`

Mark a `Handler<Message>` implementation with `@ActorComponent` to make it both a Spring bean (with full dependency injection) and a Cajun actor (auto-spawned on startup).

```java
@ActorComponent(id = "order-processor")
public class OrderHandler implements Handler<OrderMessage> {

    private final OrderRepository repository;   // injected by Spring
    private final PaymentService payments;

    public OrderHandler(OrderRepository repository, PaymentService payments) {
        this.repository = repository;
        this.payments = payments;
    }

    @Override
    public void receive(OrderMessage message, ActorContext context) {
        switch (message) {
            case OrderMessage.Place place   -> repository.save(place.order());
            case OrderMessage.Cancel cancel -> repository.cancel(cancel.orderId());
            case OrderMessage.Pay pay       -> payments.charge(pay.orderId());
        }
    }
}
```

The handler's Spring-injected dependencies are fully wired before the actor starts processing messages.

### 3. Inject actor references with `@InjectActor`

Use `@InjectActor` to inject a `Pid` or `ActorRef<T>` for any registered actor into another Spring bean.

```java
@Service
public class CheckoutService {

    @InjectActor(OrderHandler.class)
    private Pid orderPid;                         // raw Pid

    @InjectActor(OrderHandler.class)
    private ActorRef<OrderMessage> orderActor;    // type-safe wrapper

    public void checkout(Order order) {
        orderActor.tell(new OrderMessage.Place(order));
    }
}
```

> **Note:** `@InjectActor` fields are resolved after all singleton beans have been created. They are **not** available during `@PostConstruct`. If you need the `Pid` that early, use `CajunActorRegistry` directly.

### 4. Look up actors via `CajunActorRegistry`

`CajunActorRegistry` is a Spring bean that tracks every actor spawned through the integration. Use it to look up `Pid`s programmatically — useful in `@PostConstruct` methods or when `@InjectActor` isn't convenient.

```java
@Service
public class NotificationService {

    private final CajunActorRegistry registry;

    public NotificationService(CajunActorRegistry registry) {
        this.registry = registry;
    }

    @PostConstruct
    public void init() {
        Pid pid = registry.getByHandlerClass(OrderHandler.class);
        // or: registry.getByActorId("order-processor")
        // or: registry.getActorRef("order-processor")  -> ActorRef<T>
    }
}
```

---

## ActorRef\<T\>

`ActorRef<T>` is a type-safe wrapper around `Pid` that ties the actor's message type to the reference at compile time.

```java
ActorRef<OrderMessage> ref = registry.getActorRef(OrderHandler.class);

// Tell (fire-and-forget)
ref.tell(new OrderMessage.Place(order));

// Tell with delay
ref.tell(new OrderMessage.Remind(orderId), 30, TimeUnit.SECONDS);

// Ask (request-response)
OrderStatus status = ref.<OrderStatus>ask(
        new OrderMessage.GetStatus(orderId),
        Duration.ofSeconds(5)
).get();

// Access the underlying Pid if needed
Pid pid = ref.getPid();
```

> `ActorRef<T>` is optional convenience. Everywhere you see `ActorRef<T>` you can use the underlying `Pid` instead.

---

## Stateful Actors

`@ActorComponent` only supports stateless `Handler<Message>` implementations. For `StatefulHandler` actors, spawn them in a `@Bean` method and register them with the `CajunActorRegistry`:

```java
@Configuration
public class ActorConfig {

    @Bean
    public Pid counterActor(ActorSystem system, CajunActorRegistry registry) {
        Pid pid = system.statefulActorOf(CounterHandler.class, 0)
                .withId("counter")
                .spawn();
        registry.register(CounterHandler.class, "counter", pid);
        return pid;
    }
}
```

---

## Configuration

All settings live under the `cajun` prefix in `application.yml` / `application.properties`.

```yaml
cajun:
  thread-pool:
    # Workload preset — overrides executor-type when set.
    # One of: IO_BOUND, CPU_BOUND, MIXED
    workload: IO_BOUND

    # Executor type when no workload preset is set.
    # One of: VIRTUAL (default), FIXED, WORK_STEALING
    executor-type: VIRTUAL

    # Thread count for FIXED pools (default: available processors)
    fixed-pool-size: 8

    # Parallelism for WORK_STEALING pools (default: available processors)
    work-stealing-parallelism: 8

    # Number of scheduler threads for delayed messages (default: cpu/2, min 2)
    scheduler-threads: 4

    # Whether actors share a single executor (default: true)
    use-shared-executor: true

    # Prefer virtual threads when possible (default: true)
    prefer-virtual-threads: true

  backpressure:
    # Enable a default backpressure config on all actors (default: false)
    enabled: true

    # Strategy when an actor's mailbox is full.
    # One of: BLOCK (default), DROP_NEW, DROP_OLDEST
    strategy: DROP_OLDEST

    # Mailbox fill ratio thresholds (0.0–1.0)
    warning-threshold: 0.7
    critical-threshold: 0.9
    recovery-threshold: 0.5

    # Adaptive mailbox resizing watermarks
    high-watermark: 0.8
    low-watermark: 0.2

    # Mailbox capacity bounds
    min-capacity: 16
    max-capacity: 10000   # 0 = unbounded
```

---

## Customizing the ActorSystem

For programmatic customization (e.g. registering a persistence provider or setting the default ID strategy), implement `CajunActorSystemCustomizer` and register it as a Spring bean:

```java
@Bean
public CajunActorSystemCustomizer persistenceCustomizer(PersistenceProvider provider) {
    return system -> system.setPersistenceProvider(provider);
}
```

Multiple customizers are applied in `@Order` order. They run after the `ActorSystem` is built from properties but before it is exposed as a bean.

---

## Explicit Opt-in (without Spring Boot)

If you're not using Spring Boot's auto-configuration, add `@EnableCajun` to a `@Configuration` class:

```java
@Configuration
@EnableCajun
public class AppConfig {
    // ActorSystem, CajunActorRegistry, etc. are now available
}
```

---

## Graceful Shutdown

The `ActorSystem` is shut down automatically when the Spring application context closes — no manual wiring required.

---

## Summary of Beans

| Bean | Type | Description |
|------|------|-------------|
| `actorSystem` | `ActorSystem` | The Cajun actor system |
| `cajunActorRegistry` | `CajunActorRegistry` | Registry of all spawned actors |
| `actorComponentBeanPostProcessor` | `BeanPostProcessor` | Spawns `@ActorComponent` beans |
| `injectActorBeanPostProcessor` | `BeanPostProcessor` | Resolves `@InjectActor` fields |

All beans are `@ConditionalOnMissingBean` — declare your own to override any of them.
