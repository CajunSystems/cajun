# Backpressure System Enhancements

## Overview

The Cajun backpressure system has been significantly enhanced to improve thread safety, error handling, and overall stability. These improvements ensure that the system can handle high-load scenarios and state transitions without crashing or exhibiting unexpected behavior.

## Key Enhancements

### 1. Thread Safety Improvements

- **Synchronized Collections**: All shared collections (recentEvents, stateTransitions) are now properly synchronized to prevent concurrent modification exceptions.
- **Atomic State Updates**: State transitions are now atomic operations, ensuring consistent state across threads.
- **Defensive Copying**: Defensive copies are made when returning collections to prevent external modification.
- **Double-Checked Locking**: Implemented where appropriate to minimize synchronization overhead while maintaining thread safety.

### 2. Error Handling Enhancements

- **Comprehensive Exception Handling**: All methods now catch and handle exceptions appropriately, preventing crashes.
- **Graceful Degradation**: The system degrades gracefully when errors occur, maintaining basic functionality.
- **Detailed Logging**: Enhanced logging provides better diagnostics for troubleshooting.
- **Default Values**: Safe default values are used when errors occur or invalid parameters are provided.

### 3. New Features

#### Retry With Timeout

The backpressure system now supports the `RETRY_WITH_TIMEOUT` action for custom handlers. This feature allows messages to be retried after a specified timeout period, improving system resilience under load.

**How it works:**

1. When a custom handler returns `RETRY_WITH_TIMEOUT`, the message is queued for retry.
2. After the specified timeout, the system checks if backpressure conditions have improved.
3. If conditions have improved or backpressure is no longer active, the message is sent.
4. If conditions have not improved, the message is dropped.

**Configuration:**

```java
// Set the default retry timeout (in milliseconds)
backpressureManager.setDefaultRetryTimeoutMs(2000); // 2 seconds

// Get the current retry timeout
long timeout = backpressureManager.getDefaultRetryTimeoutMs();
```

**Custom Handler Implementation:**

```java
public class MyCustomHandler implements CustomBackpressureHandler<MyMessage> {
    @Override
    public BackpressureAction handleMessage(MyMessage message, BackpressureEvent event) {
        // Implement your custom logic
        if (message.isPriority()) {
            return BackpressureAction.ACCEPT; // Always accept priority messages
        } else if (event.getFillRatio() > 0.95) {
            return BackpressureAction.REJECT; // Reject when extremely full
        } else {
            return BackpressureAction.RETRY_WITH_TIMEOUT; // Retry others
        }
    }
    
    @Override
    public boolean shouldAccept(int mailboxSize, int capacity, BackpressureSendOptions options) {
        // Implement your custom acceptance logic
        return options.isPriority() || mailboxSize < capacity * 0.8;
    }
}
```

#### Resource Management

The backpressure system now includes proper resource management with a `shutdown()` method that should be called when the actor is shutting down:

```java
// Clean up resources when actor is shutting down
backpressureManager.shutdown();
```

### 4. Improved State Transitions

- **Consistent State Transitions**: State transitions now follow a clear and consistent pattern.
- **Hysteresis**: Proper hysteresis is implemented to prevent rapid state oscillations.
- **Detailed Transition Records**: State transitions include detailed information about the cause and timing.
- **Human-Readable Durations**: Time durations are formatted in a human-readable way for better logging.

### 5. Input Validation

- **Parameter Validation**: All public methods validate their inputs and provide reasonable defaults.
- **Threshold Validation**: Thresholds are validated to ensure they are logical (e.g., warning < critical, recovery < warning).
- **Null Checks**: Comprehensive null checks prevent NullPointerExceptions.

## Usage Guidelines

### Basic Configuration

```java
// Create a backpressure manager for an actor
BackpressureManager<MyMessage> manager = new BackpressureManager<>(myActor);

// Configure thresholds
BackpressureConfig config = new BackpressureConfig()
    .setHighWatermark(0.8f)  // Critical threshold at 80% capacity
    .setLowWatermark(0.5f);  // Recovery threshold at 50% capacity

// Enable backpressure with the config
manager.enable(config);

// Set the strategy
manager.setStrategy(BackpressureStrategy.DROP_NEW);

// Register a callback for state transitions
manager.setCallback(event -> {
    System.out.println("Backpressure state changed: " + event.getCurrentState());
});
```

### Custom Handling

```java
// Create and set a custom handler
CustomBackpressureHandler<MyMessage> handler = new MyCustomHandler();
manager.setStrategy(BackpressureStrategy.CUSTOM);
manager.setCustomHandler(handler);
```

### Monitoring

```java
// Get current status
BackpressureStatus status = manager.getStatus();
System.out.println("Current state: " + status.getCurrentState());
System.out.println("Fill ratio: " + status.getFillRatio());
System.out.println("Messages processed: " + status.getMessagesProcessedSinceLastStateChange());

// Check if backpressure is active
boolean isActive = manager.isBackpressureActive();
```

## Best Practices

1. **Always Enable Backpressure**: Enable backpressure for actors that process messages at varying rates.
2. **Choose Appropriate Thresholds**: Set thresholds based on your application's characteristics.
3. **Use Custom Handlers for Complex Logic**: Implement custom handlers for fine-grained control over message handling.
4. **Register Callbacks**: Register callbacks to react to backpressure state changes.
5. **Shutdown Properly**: Call `shutdown()` when the actor is shutting down to release resources.
6. **Monitor Backpressure Status**: Regularly monitor backpressure status to identify potential bottlenecks.
7. **Tune Retry Timeouts**: Adjust retry timeouts based on your application's expected recovery times.

## Thread Safety Considerations

The backpressure system is designed to be thread-safe, but users should be aware of the following:

1. **Callback Thread Safety**: Callbacks are executed on the thread that updates the metrics, so they should be thread-safe.
2. **Custom Handler Thread Safety**: Custom handlers should be thread-safe as they may be called from multiple threads.
3. **Concurrent Configuration Changes**: Avoid changing configuration (thresholds, strategies) while the system is under heavy load.

## Error Handling

The backpressure system handles errors internally to prevent crashes, but users should still:

1. **Monitor Logs**: Watch for warning and error messages in the logs.
2. **Handle Rejected Messages**: Have a strategy for handling messages that are rejected due to backpressure.
3. **Test Edge Cases**: Test your system under extreme conditions to ensure it behaves as expected.

## Performance Considerations

1. **Minimize Callback Overhead**: Keep callback processing lightweight to avoid adding latency.
2. **Tune Collection Sizes**: Adjust `maxEventsToKeep` if you need more or less historical data.
3. **Consider Memory Usage**: Be aware that storing many events in history can increase memory usage.
