# Conventions

## Naming

### Classes
- Standard PascalCase throughout
- Suffix conventions:
  - `*Test` — test classes
  - `*Builder` — builder classes
  - `*Strategy` — strategy pattern
  - `*Exception` — exceptions
  - `*Actor` — actor implementations
  - `*Provider` — provider pattern
  - `*Factory` — factory classes
  - `*Monitor` — monitoring components
  - `*Handler` — message handlers

### Methods and Variables
- camelCase throughout
- Getters: `getX()` convention (e.g., `getActorId()`, `getHelloCount()`)
- Booleans: `isX()` prefix (e.g., `isReadOnly()`)
- Builder methods: `withX()` prefix (e.g., `withId()`, `withBackpressureConfig()`)
- Preset builder methods: `presetX()` (e.g., `presetTimeCritical()`, `presetReliable()`)
- Test methods: `testX()` or `shouldX()` (e.g., `testBasicMessageProcessing()`, `shouldBeAbleToCreateAGreetingActor()`)

### Packages
Deep hierarchy by functionality:
- `handler/`, `backpressure/`, `cluster/`, `persistence/`, `config/`, `builder/`, `internal/`, `functional/`, `metrics/`, `runtime/`
- Test utilities: `com.cajunsystems.test`
- Nested subpackages for implementations: `impl/`, `filesystem/`, `lmdb/`, `capabilities/`

## Java Features in Use

### Sealed Interfaces
Used extensively for exhaustive message type discrimination:
```java
public sealed interface CounterProtocol {
    record CountUp() implements CounterProtocol {}
    record GetCount(Pid replyTo) implements CounterProtocol {}
}
```

### Records
Used for immutable message types, data classes, and Pid:
```java
public record MessageAdapter<T extends Serializable>(
    T originalMessage, boolean isReadOnly) implements OperationAwareMessage {}
```

### Pattern Matching (switch expressions)
```java
switch (message) {
    case HelloMessage ignored -> helloCount++;
    case GetHelloCount ghc -> ghc.replyTo().tell(new HelloCount(helloCount));
}
```

### var
Used selectively in test and implementation code:
```java
var actor = new GreetingActor(actorSystem, "Greeting-Actor-1");
```

### Generics
Extensive bounded type parameters, generics maintained through builder chains:
```java
public class ActorBuilder<Message> { ... }
public class BackpressureBuilder<T> { ... }
ConcurrentHashMap<String, Actor<?>> actors;
```

### Enums
Used for fixed value sets — each value has JavaDoc:
- `SupervisionStrategy`: RESUME, RESTART, STOP, ESCALATE
- `BackpressureState`: NORMAL, WARNING, CRITICAL, RECOVERY
- `DeliveryGuarantee`: AT_MOST_ONCE, AT_LEAST_ONCE, EXACTLY_ONCE
- `ThreadPoolFactory.WorkloadType`: IO_BOUND, CPU_BOUND, MIXED

## Code Style

- **Indentation**: 4 spaces
- **Braces**: Same-line (Java standard)
- **Blank lines**: Logical grouping between methods and sections
- **Line length**: No strict enforcement, generally 80–120 chars

## Immutability Patterns

- `final` fields extensively used:
  ```java
  private final String actorId;
  private final ActorSystem system;
  ```
- Records are implicitly immutable
- Stateful actors use immutable state pattern — handler returns new state:
  ```java
  State receive(Message msg, State state, ActorContext ctx) {
      return new State(state.count + 1);  // new instance
  }
  ```
- Thread safety via `ConcurrentHashMap`, `AtomicReference`, `AtomicInteger`, etc.

## Error Handling

### Custom Exceptions
- `ActorException` — base exception with optional actor ID tracking
- `JournalException` — persistence errors
- Multiple constructor overloads for flexibility

### Handler Error Contract
```java
// Optional override — return true to reprocess message
default boolean onError(Message message, Throwable exception, ActorContext context) {
    return false;
}
```

### Supervision
Hierarchical: RESUME → RESTART → STOP → ESCALATE

## Builder Pattern

All fluent builders:
- Methods return `this` for chaining
- `withX()` prefix for all setter-style methods
- Preset configurations for common scenarios (e.g., `presetTimeCritical()`)
- Type parameters maintained through chain

```java
system.actorOf(MyHandler.class)
    .withId("my-actor")
    .withBackpressureConfig(config)
    .withThreadPoolFactory(factory)
    .spawn();
```

## Documentation

- Comprehensive JavaDoc on all public classes and methods
- Standard format: description → `@param` → `@return` → `@throws`
- Code examples in `{@code}` and `<pre>` blocks
- `@SuppressWarnings` used judiciously, only for safe casts

## Message Types

The preferred pattern for defining actor messages:
```java
// Sealed interface groups all messages for one actor
public sealed interface MyProtocol {
    record DoSomething(String data) implements MyProtocol {}
    record GetResult(Pid replyTo) implements MyProtocol {}
}
```

## Serialization

State and message classes used with stateful actors must:
- Implement `java.io.Serializable`
- All fields must be serializable or marked `transient`
- `serialVersionUID` recommended
