# Cajun Backpressure System

## Overview

The backpressure system in Cajun provides a sophisticated mechanism for actors to handle high load scenarios gracefully. It allows actors to control their message processing rate and apply different strategies when receiving messages faster than they can process them.

This document describes the design, components, and usage of the backpressure system.

## Core Components

### BackpressureManager

The `BackpressureManager` is the central component of the backpressure system. It handles:

- Monitoring mailbox size and processing rates
- Determining the current backpressure state
- Managing state transitions
- Applying backpressure strategies
- Collecting metrics and events

### BackpressureState

The backpressure system operates with four distinct states:

1. **NORMAL**: The actor is operating with sufficient capacity
2. **WARNING**: The actor is approaching capacity limits but not yet applying backpressure
3. **CRITICAL**: The actor is at or above its high watermark and actively applying backpressure
4. **RECOVERY**: The actor was recently in a CRITICAL state but is now recovering (below high watermark but still above low watermark)

### BackpressureStrategy

Cajun supports multiple strategies for handling backpressure:

1. **BLOCK**: Block the sender until space is available in the mailbox (default behavior)
2. **DROP_NEW**: Drop new messages when the mailbox is full, prioritizing older messages
3. **DROP_OLDEST**: Drop the oldest messages when the mailbox is full, prioritizing newer messages
4. **CUSTOM**: Use a custom strategy by implementing a `CustomBackpressureHandler`

### BackpressureConfig

Configuration for the backpressure system, including:

- Enable/disable backpressure
- High watermark threshold (when to enter CRITICAL state)
- Low watermark threshold (when to exit RECOVERY state)
- Strategy selection
- Custom handler configuration

### BackpressureSendOptions

Options for sending messages with specific backpressure behavior:

- High priority flag (to bypass backpressure in certain states)
- Retry configuration
- Timeout settings
- Fallback strategy

## State Transitions

The backpressure system uses a state machine to manage transitions between different states:

```
                 fill ratio < recoveryThreshold
                 ┌─────────────────────────────┐
                 │                             │
                 ▼                             │
┌─────────┐    ┌─────────┐    ┌───────────┐   │   ┌──────────┐
│ NORMAL  │───►│ WARNING │───►│ CRITICAL  │◄──┴───┤ RECOVERY │
└─────────┘    └─────────┘    └───────────┘       └──────────┘
                                    │                  ▲
                                    └──────────────────┘
                                  fill ratio < recoveryThreshold
```

- **NORMAL → WARNING**: Fill ratio exceeds warning threshold
- **WARNING → CRITICAL**: Fill ratio exceeds critical threshold
- **CRITICAL → RECOVERY**: Fill ratio drops below recovery threshold
- **RECOVERY → NORMAL**: Fill ratio drops below recovery threshold
- **RECOVERY → CRITICAL**: Fill ratio rises above critical threshold again

## Monitoring and Metrics

The backpressure system provides comprehensive monitoring capabilities:

- **Event History**: Records recent backpressure events
- **State Transitions**: Tracks when and why state changes occurred
- **Metrics**: Collects data on mailbox size, fill ratio, and processing rates
- **Callbacks**: Notifies registered listeners of backpressure events

## Thread Safety

The backpressure system is designed to be thread-safe:

- Synchronized access to shared collections
- Atomic operations for state updates
- Safe handling of concurrent event processing

## Usage Examples

### Basic Configuration

```java
// Create a backpressure configuration
BackpressureConfig config = new BackpressureConfig.Builder()
    .setEnabled(true)
    .setHighWatermark(0.8f)  // 80% capacity triggers CRITICAL state
    .setLowWatermark(0.5f)   // 50% capacity triggers RECOVERY state
    .setStrategy(BackpressureStrategy.BLOCK)
    .build();

// Create a stateful actor with backpressure
StatefulActor<MyState, MyMessage> actor = 
    new MyStatefulActor(system, initialState, config);
```

### Custom Backpressure Handler

```java
// Create a custom handler
CustomBackpressureHandler<MyMessage> handler = new CustomBackpressureHandler<>() {
    @Override
    public boolean shouldAccept(int currentSize, int capacity, BackpressureSendOptions options) {
        // Custom logic to decide if message should be accepted
        return currentSize < capacity * 0.9;
    }
    
    @Override
    public BackpressureAction handleMessage(MyMessage message, BackpressureEvent event) {
        // Custom handling based on message content and current backpressure event
        if (message.isPriority()) {
            return BackpressureAction.ACCEPT;
        } else if (event.getFillRatio() > 0.95) {
            return BackpressureAction.REJECT;
        }
        return BackpressureAction.ACCEPT;
    }
};

// Set the custom handler
actor.getBackpressureManager().setCustomHandler(handler);
```

### Monitoring Backpressure

```java
// Register for backpressure event notifications
actor.getBackpressureManager().setCallback(event -> {
    logger.info("Backpressure event: {} state, fill ratio: {}", 
                event.getState(), event.getFillRatio());
    
    // Take action based on backpressure events
    if (event.isBackpressureActive()) {
        // Notify monitoring system, scale resources, etc.
    }
});

// Access detailed backpressure metrics and history
BackpressureStatus status = actor.getBackpressureStatus();
List<BackpressureEvent> recentEvents = status.getRecentEvents();
List<StateTransition> stateTransitions = status.getStateTransitions();
```

### High Priority Messages

```java
// Create options for high priority messages that bypass backpressure
BackpressureSendOptions highPriority = BackpressureSendOptions.highPriority();

// Send with high priority
actor.tell(urgentMessage, highPriority);
```

## Best Practices

1. **Tune thresholds carefully**: Set appropriate warning, critical, and recovery thresholds based on your workload characteristics.

2. **Choose the right strategy**: Different workloads benefit from different backpressure strategies:
   - Use BLOCK for cases where all messages must be processed eventually
   - Use DROP_NEW for cases where newer messages are less important than older ones
   - Use DROP_OLDEST for cases where newer messages should take priority

3. **Monitor backpressure events**: Set up callbacks to be notified of backpressure events and take appropriate action.

4. **Use high priority sparingly**: Reserve high priority messages for truly critical operations.

5. **Test with realistic loads**: Validate your backpressure configuration under realistic load conditions.

## Implementation Notes

The backpressure system was designed with the following considerations:

1. **Thread safety**: All operations are thread-safe to handle concurrent access.

2. **Low overhead**: The system is designed to add minimal overhead during normal operation.

3. **Flexibility**: Multiple strategies and configuration options allow adaptation to different use cases.

4. **Observability**: Comprehensive metrics and event history enable effective monitoring.
