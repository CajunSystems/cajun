# Cajun Backpressure System

The backpressure system in Cajun provides mechanisms to handle high load scenarios gracefully by controlling message flow to actors when their mailboxes approach capacity. The system has been streamlined to improve maintainability, simplify the API, and enhance performance.

## Key Components

### BackpressureManager
Core component that manages backpressure state transitions, metrics, and applies backpressure strategies. It now includes improved error handling and uses the Actor.dropOldestMessage method directly rather than relying on reflection.

### BackpressureBuilder
Enhanced unified builder for configuring backpressure on actors. Now supports both:
- Direct actor configuration with type safety
- PID-based configuration through the ActorSystem
- Preset configurations for common use cases (timeCritical, reliable, highThroughput)

### BackpressureStrategy
Defines how the system responds when an actor's mailbox approaches capacity:
- `DROP_NEW`: Reject new messages when under backpressure
- `DROP_OLDEST`: Remove oldest messages from the mailbox to make room for new ones using the new Actor.dropOldestMessage method
- `BLOCK`: Block the sender until the mailbox has room
- `CUSTOM`: Use a custom handler to implement specialized backpressure logic

### BackpressureState
Represents the current state of an actor's mailbox:
- `NORMAL`: Operating normally, no backpressure
- `WARNING`: Approaching capacity, but still accepting messages
- `CRITICAL`: At or near capacity, backpressure active
- `RECOVERY`: Transitioning from critical back to normal

### RetryEntry
Holds information about messages scheduled for retry when backpressure conditions improve.

### SystemBackpressureMonitor
Provides centralized access to backpressure functionality through the ActorSystem.

## Usage Examples

### Basic Configuration

```java
// Direct actor configuration
BackpressureBuilder<String> builder = new BackpressureBuilder<>(myActor)
    .withStrategy(BackpressureStrategy.DROP_OLDEST)
    .withWarningThreshold(0.7f)
    .withCriticalThreshold(0.9f);
builder.apply();

// PID-based configuration through ActorSystem
BackpressureBuilder<String> builder = system.getBackpressureMonitor()
    .configureBackpressure(actorPid)
    .withStrategy(BackpressureStrategy.DROP_OLDEST)
    .withWarningThreshold(0.7f)
    .withCriticalThreshold(0.9f);
builder.apply();
```

### Using Preset Configurations

```java
// Time-critical configuration - prioritizes newer messages
BackpressureBuilder<String> builder = new BackpressureBuilder<>(myActor)
    .presetTimeCritical()
    .apply();

// Reliable configuration - never drops messages
BackpressureBuilder<String> builder = new BackpressureBuilder<>(myActor)
    .presetReliable()
    .apply();

// High-throughput configuration - optimized for maximum processing
BackpressureBuilder<String> builder = new BackpressureBuilder<>(myActor)
    .presetHighThroughput()
    .apply();
```

### Sending Messages with Backpressure Options

```java
// Create options for sending messages
BackpressureSendOptions options = new BackpressureSendOptions()
    .setBlockUntilAccepted(true)
    .setTimeout(Duration.ofSeconds(5))
    .setHighPriority(false);

// Send with options
boolean accepted = system.tellWithOptions(actorPid, message, options);
```

### Custom Backpressure Handler

```java
CustomBackpressureHandler<String> handler = new CustomBackpressureHandler<>() {
    @Override
    public boolean handleMessage(Actor<String> actor, String message, BackpressureSendOptions options) {
        // Custom logic to decide whether to accept the message
        return true;
    }
    
    @Override
    public boolean makeRoom(Actor<String> actor) {
        // Custom logic to make room in the mailbox
        return true;
    }
};

// Configure with custom handler
new BackpressureBuilder<>(myActor)
    .withStrategy(BackpressureStrategy.CUSTOM)
    .withCustomHandler(handler)
    .apply();
```

### Monitoring Backpressure Events

```java
// Register a callback to be notified of backpressure events
system.setBackpressureCallback(actorPid, event -> {
    System.out.println("Backpressure state changed to: " + event.getState());
    System.out.println("Fill ratio: " + event.getFillRatio());
});
```

## Best Practices

1. **Choose the right strategy** for your use case:
   - Use `DROP_NEW` for non-critical messages where losing recent messages is acceptable
   - Use `DROP_OLDEST` when newer messages are more important than older ones
   - Use `BLOCK` when all messages must be processed and the sender can wait
   - Use `CUSTOM` for specialized requirements

2. **Set appropriate thresholds** based on your workload patterns:
   - Warning threshold: When to start monitoring more closely (typically 0.5-0.7)
   - Critical threshold: When to activate backpressure (typically 0.8-0.9)
   - Recovery threshold: When to deactivate backpressure (typically 0.3-0.5)

3. **Use backpressure callbacks** to monitor system health and take corrective actions

4. **Consider message priority** for critical messages that should bypass backpressure

5. **Use preset configurations** for common scenarios to simplify setup:
   - `presetTimeCritical()` for actors where newer messages are more important
   - `presetReliable()` for actors that must process every message
   - `presetHighThroughput()` for actors optimized for maximum processing capacity
