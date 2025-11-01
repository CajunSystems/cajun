# Sender Context Propagation in Actor Hierarchies

## Overview

The actor system now supports consistent sender context propagation through actor hierarchies, making it work seamlessly with the ask pattern.

## Two Approaches

### 1. **Using `tell()` - No Sender Propagation (Default)**

When you use `tell()`, the sender context is NOT propagated:

```java
// Grandparent asks Parent
Pid response = grandparent.ask(parentPid, new Request());

// Inside Parent's receive method:
@Override
protected void receive(Message msg) {
    Optional<Pid> grandparent = getSender(); // ✓ Returns Optional with grandparent
    
    // Send to child using tell - sender context is lost
    childPid.tell(new ChildMessage());
    
    // Inside Child's receive method, getSender() returns Optional.empty()
}
```

### 2. **Using `forward()` - Preserves Original Sender (New!)**

When you use `forward()`, the original sender context is preserved:

```java
// Grandparent asks Parent
Pid response = grandparent.ask(parentPid, new Request());

// Inside Parent's receive method:
@Override
protected void receive(Message msg) {
    Optional<Pid> grandparent = getSender(); // ✓ Returns Optional with grandparent
    
    // Forward to child - preserves grandparent as sender
    forward(childPid, new ChildMessage());
    
    // Inside Child's receive method:
    // getSender() returns Optional with grandparent (not parent!)
    // Child can reply directly to grandparent
}
```

## Use Cases

### Use Case 1: Request Forwarding

When an actor acts as a router/proxy and wants the final handler to reply to the original requester:

```java
public class RouterActor extends Actor<Message> {
    @Override
    protected void receive(Message msg) {
        if (msg instanceof RoutableRequest req) {
            Pid handler = selectHandler(req);
            
            // Forward preserves original sender for reply
            forward(handler, req);
        }
    }
}

public class HandlerActor extends Actor<Message> {
    @Override
    protected void receive(Message msg) {
        if (msg instanceof RoutableRequest req) {
            // Process request
            Response response = process(req);
            
            // Reply goes directly to original requester, not router
            getSender().ifPresent(requester -> requester.tell(response));
        }
    }
}
```

### Use Case 2: Multi-Level Processing Pipeline

```java
// Grandparent initiates request
Pid result = grandparent.ask(parentPid, new ProcessRequest());

// Parent forwards to child
public class ParentActor extends Actor<Message> {
    @Override
    protected void receive(Message msg) {
        if (msg instanceof ProcessRequest req) {
            // Do some preprocessing
            ProcessRequest enhanced = preprocess(req);
            
            // Forward to child - grandparent remains the sender
            forward(childPid, enhanced);
        }
    }
}

// Child processes and replies to grandparent
public class ChildActor extends Actor<Message> {
    @Override
    protected void receive(Message msg) {
        if (msg instanceof ProcessRequest req) {
            ProcessResult result = process(req);
            
            // Reply goes to grandparent (original requester)
            getSender().ifPresent(requester -> requester.tell(result));
        }
    }
}
```

### Use Case 3: Handler-Based API

The `forward()` method is also available in the handler-based API through `ActorContext`:

```java
public class MyHandler implements Handler<Message> {
    @Override
    public void handle(Message msg, ActorContext context) {
        if (msg instanceof ForwardableRequest req) {
            Pid nextActor = selectNextActor(req);
            
            // Forward preserves sender context
            context.forward(nextActor, req);
        }
    }
}
```

## When to Use Each

| Method | Use When | Sender Context |
|--------|----------|----------------|
| `tell()` | Normal message passing, no reply expected | Lost (Optional.empty()) |
| `forward()` | Acting as intermediary, want final actor to reply to original sender | Preserved |
| `ask()` | Request-response pattern, you are the requester | You become the sender |

## Implementation Details

- Sender context is stored in a `ThreadLocal` variable
- `forward()` wraps the message with `MessageWithSender` to preserve context
- Context is automatically cleared after message processing
- Works seamlessly with the ask pattern
- Compatible with both traditional Actor subclassing and Handler-based API

## Best Practices

1. **Use `forward()` for request routing**: When your actor is a proxy/router
2. **Use `tell()` for fire-and-forget**: When no reply is needed
3. **Use `ask()` for request-response**: When you need a reply
4. **Use Optional methods**: `getSender()` returns `Optional<Pid>` - use `ifPresent()`, `map()`, or `orElse()` for clean handling
