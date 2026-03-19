# cajun-direct

Direct-style (synchronous) actor API for the Cajun actor system.

## Overview

`cajun-direct` lets you write actor-based code that reads like ordinary sequential Java — no
callbacks, no futures, no explicit request-reply wiring. Under the hood it uses Cajun's standard
ask-pattern, but all of that infrastructure is invisible to the caller.

Inspired by [ox](https://github.com/softwaremill/ox)'s direct-style actor model: actors are
plain objects with normal return values; the concurrency plumbing is hidden by the framework.
Blocking calls are safe because Java 21 virtual threads never pin a platform thread.

```
blocking call on a virtual thread
caller ─── call(msg) ──────────────▶ actor.handle(msg)
caller ◀── return value ─────────── return reply
```

## Core Concepts

| Concept | Description |
|---|---|
| `DirectPid<Message, Reply>` | Typed process identifier. The direct-style equivalent of Cajun's `Pid`. |
| `DirectHandler<M, R>` | Stateless handler interface. Implement `handle(msg, ctx)` and return a reply. |
| `StatefulDirectHandler<S, M, R>` | Stateful handler. Implement `handle(msg, state, ctx)` and return `DirectResult<S, R>`. |
| `DirectResult<S, R>` | Carries both the new actor state and the reply value from a stateful handler. |
| `DirectActorSystem` | Entry point. Wraps (or creates) an `ActorSystem` and exposes `actorOf` / `statefulActorOf`. |
| `ActorScope` | Structured-concurrency scope. Actors spawned inside are stopped when the scope closes. |
| `ActorChannel<M, R>` | Channel for generator-style actors. Lets you write actor logic as a plain loop. |
| `DirectContext` | Context passed to handlers, giving access to actor lifecycle and messaging. |
| `DirectActorException` | Unchecked exception thrown when a `call` fails, times out, or the handler throws. |

## Quick Start

### 1. Add the dependency

```gradle
dependencies {
    implementation project(':cajun-direct')
}
```

### 2. Define a message protocol

Use sealed interfaces with records for exhaustive pattern matching:

```java
sealed interface CalcMsg {
    record Add(double a, double b)      implements CalcMsg {}
    record Multiply(double a, double b) implements CalcMsg {}
}
```

### 3. Implement a handler

```java
public class CalculatorHandler implements DirectHandler<CalcMsg, Double> {

    @Override
    public Double handle(CalcMsg msg, DirectContext context) {
        return switch (msg) {
            case CalcMsg.Add(double a, double b)      -> a + b;
            case CalcMsg.Multiply(double a, double b) -> a * b;
        };
    }
}
```

### 4. Spawn and call

```java
DirectActorSystem system = new DirectActorSystem();

DirectPid<CalcMsg, Double> calc = system.actorOf(new CalculatorHandler())
    .withId("calculator")
    .spawn();

// Blocking call — reads like a normal method call; safe on virtual threads
double result = calc.call(new CalcMsg.Add(3, 4));  // 7.0

system.shutdown();
```

## Stateful Actors

Use `StatefulDirectHandler<State, Message, Reply>` when the actor needs to maintain state
across messages. Return `DirectResult.of(newState, reply)` from each invocation.

```java
sealed interface CounterMsg {
    record Increment() implements CounterMsg {}
    record GetValue() implements CounterMsg {}
}

public class CounterHandler implements StatefulDirectHandler<Integer, CounterMsg, Integer> {

    @Override
    public DirectResult<Integer, Integer> handle(
            CounterMsg msg, Integer state, DirectContext context) {
        return switch (msg) {
            case CounterMsg.Increment _ -> DirectResult.of(state + 1, state + 1);
            case CounterMsg.GetValue _  -> DirectResult.of(state, state);
        };
    }
}

// Spawn with initial state
DirectPid<CounterMsg, Integer> counter = system
    .statefulActorOf(new CounterHandler(), 0)
    .withId("counter")
    .spawn();

counter.call(new CounterMsg.Increment());          // 1
counter.call(new CounterMsg.Increment());          // 2
int value = counter.call(new CounterMsg.GetValue()); // 2
```

## Actor Scopes (Structured Concurrency)

`ActorScope` ties actor lifecycles to a lexical scope. All actors spawned inside the scope are
automatically stopped when the scope closes — no manual cleanup needed.

```java
try (ActorScope scope = ActorScope.open()) {
    DirectPid<CalcMsg, Double> calc = scope.actorOf(new CalculatorHandler())
        .withId("calculator")
        .spawn();

    double result = calc.call(new CalcMsg.Add(3, 4));  // 7.0
    // actor is stopped automatically at the end of the try block
}
```

Scopes can also wrap an existing `DirectActorSystem` (without shutting it down on close):

```java
ActorSystem existing = new ActorSystem();
DirectActorSystem direct = new DirectActorSystem(existing);

try (ActorScope scope = ActorScope.open(direct)) {
    // actors here are stopped on close; `existing` keeps running
}
```

## Generator-Style Actors

Generator-style actors let you write message handling as a plain loop — no handler interfaces
to implement, no class to define. State lives as a local variable; the framework delivers
messages one at a time via `ActorChannel`.

```java
try (ActorScope scope = ActorScope.open()) {

    // State lives as a plain local variable
    DirectPid<CounterMsg, Integer> counter = scope.actor(channel -> {
        int count = 0;
        while (channel.isOpen()) {
            channel.handle(msg -> switch (msg) {
                case CounterMsg.Increment _ -> ++count;
                case CounterMsg.GetValue _  -> count;
                case CounterMsg.Reset _     -> { count = 0; yield 0; }
            });
        }
    });

    counter.call(new CounterMsg.Increment()); // 1
    counter.call(new CounterMsg.Increment()); // 2
    int val = counter.call(new CounterMsg.GetValue()); // 2

} // counter stopped automatically
```

### `ActorChannel` API

| Method | Description |
|---|---|
| `handle(fn)` | Blocks until the next message arrives; applies `fn` to get the reply; returns `true` if the channel is still open, `false` if it's closed. |
| `isOpen()` | Returns `true` while the actor scope is active. |

Two equivalent loop styles:

```java
// Style 1: isOpen guard
while (channel.isOpen()) {
    channel.handle(msg -> process(msg));
}

// Style 2: handle as loop condition
while (channel.handle(msg -> process(msg))) { }
```

### How it works

The loop body runs in a virtual thread. The Cajun actor mailbox delivers messages to the
channel queue. Each `handle` call blocks the loop thread until a message arrives, then
sends the reply back to the caller. The actor's processing loop is held while the user
handles the message, preserving sequential processing semantics.

## Interaction Modes

### Blocking call (primary API)

```java
Reply result = pid.call(message);                          // default timeout (30 s)
Reply result = pid.call(message, Duration.ofSeconds(5));   // custom timeout
```

Blocks the calling thread. **Safe to use on virtual threads** — no platform thread is pinned.

### Async call

```java
CompletableFuture<Reply> future = pid.callAsync(message);
future.thenAccept(reply -> System.out.println("Got: " + reply));
```

For integrating with existing async or reactive pipelines.

### Fire-and-forget

```java
pid.tell(message);
```

Sends a message without waiting for a reply. The actor still processes the message and
computes a return value; that value is simply discarded.

## Error Handling

Throw from `handle` to signal failure. Override `onError` to provide a fallback reply
instead of propagating an exception to the caller:

```java
@Override
public String onError(String msg, Throwable ex, DirectContext ctx) {
    ctx.getLogger().warn("Failed to handle '{}': {}", msg, ex.getMessage());
    return "error: " + ex.getMessage();   // return a fallback reply
}
```

If `onError` itself throws (or is not overridden), the caller receives a `DirectActorException`.

For stateful handlers the error signature also includes the current state, and the return
value must be a `DirectResult` so the state can be recovered or reset:

```java
@Override
public DirectResult<State, Reply> onError(
        Msg msg, State state, Throwable ex, DirectContext ctx) {
    return DirectResult.of(state, defaultReply);  // preserve state, return fallback
}
```

## Timeouts

The default timeout for `call` is **30 seconds**. Change it per-actor at build time or
per-call at runtime:

```java
// Build-time default
DirectPid<Msg, Reply> pid = system.actorOf(handler)
    .withTimeout(Duration.ofSeconds(10))
    .spawn();

// Per-call override
Reply result = pid.call(message, Duration.ofSeconds(2));

// Derive a new DirectPid with a different default (immutable, original unchanged)
DirectPid<Msg, Reply> faster = pid.withTimeout(Duration.ofMillis(500));
```

## Interoperability

`DirectPid` wraps a standard Cajun `Pid`. Access it for interop with the base API:

```java
Pid rawPid = directPid.pid();
rawPid.tell(someMessage);
```

Similarly, `DirectActorSystem` can wrap an existing `ActorSystem`:

```java
ActorSystem existing = new ActorSystem();
DirectActorSystem direct = new DirectActorSystem(existing);

// Both APIs share the same underlying actor registry
```

## Builder Options

All standard Cajun builder options are available:

```java
DirectPid<Msg, Reply> pid = system.actorOf(new MyHandler())
    .withId("my-actor")
    .withTimeout(Duration.ofSeconds(10))
    .withBackpressureConfig(BackpressureConfig.preset())
    .withMailboxConfig(new ResizableMailboxConfig())
    .withSupervisionStrategy(SupervisionStrategy.RESUME)
    .withThreadPoolFactory(new ThreadPoolFactory()
        .optimizeFor(ThreadPoolFactory.WorkloadType.CPU_BOUND))
    .spawn();
```

## Virtual Thread Pattern

Direct-style actors pair naturally with Java 21 virtual threads for fan-out coordination:

```java
// Fan out: call multiple actors in parallel virtual threads
List<CompletableFuture<String>> futures = services.stream()
    .map(svc -> CompletableFuture.supplyAsync(
        () -> svc.call(new Request(query)),
        Thread.ofVirtual().factory()))
    .toList();

List<String> results = futures.stream()
    .map(CompletableFuture::join)
    .toList();
```

## Design Notes

- **Wire protocol**: Replies are wrapped in an internal `DirectWireReply` sealed type so
  exceptions can be propagated back to the caller immediately (no hanging until timeout).
- **Adapter pattern**: `DirectHandler` is adapted to Cajun's `Handler` interface via
  `DirectHandlerAdapter`, keeping full compatibility with all existing infrastructure
  (backpressure, persistence, cluster routing, supervision).
- **No new threads**: Virtual threads are used by the Cajun actor system; `cajun-direct`
  adds no additional threading infrastructure.
