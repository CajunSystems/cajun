# Effect Actor Patterns

A catalogue of common patterns drawn from the Milestone 2 examples.

---

## 1. Error Handling

### Recover with catchAll

`catchAll(fn)` catches any failure and returns a new effect. The actor never throws regardless
of input:

```java
Pid parser = spawnEffectActor(system,
    (String input) ->
        Effect.<RuntimeException, Integer>suspend(() -> Integer.parseInt(input))
            .catchAll(err -> Effect.succeed(-1))    // bad input → -1
            .flatMap(n -> Effect.suspend(() -> {
                process(n);
                return Unit.unit();
            })));

parser.tell("42");           // succeeds → 42
parser.tell("not-a-number"); // fails → recovered as -1
```

### Fallback with orElse

`orElse(fallback)` ignores the error value. Use when any failure should trigger the same backup:

```java
Effect<RuntimeException, String> result =
    primaryEffect.orElse(Effect.succeed("fallback"));
```

### Transform the error type with mapError

`mapError(fn)` translates one exception type into another at pipeline boundaries:

```java
Effect<NumberFormatException, Integer> parse =
    Effect.suspend(() -> Integer.parseInt(input));

Effect<IllegalArgumentException, Integer> domain = parse.mapError(nfe ->
    new IllegalArgumentException("Invalid number input: " + nfe.getMessage(), nfe));
```

### Materialise errors with attempt

`attempt()` converts a failed effect into `Either.Left` instead of throwing. Widens the error
type to `Throwable`, so the calling method must declare `throws Throwable`:

```java
@Test
@SuppressWarnings("unchecked")
void exampleTest() throws Throwable {
    Effect<RuntimeException, Integer> failing = Effect.fail(new RuntimeException("oops"));

    Either<RuntimeException, Integer> result = runtime.unsafeRun(failing.attempt());

    if (result instanceof Either.Left<RuntimeException, Integer> left) {
        // left.value() is the RuntimeException
    } else if (result instanceof Either.Right<RuntimeException, Integer> right) {
        // right.value() is the Integer
    }
}
```

### Retry with backoff

Build a retry chain with recursive `catchAll`. `Effect.suspend(supplier)` re-evaluates the
supplier on each attempt, so each call invokes the operation afresh:

```java
static Effect<RuntimeException, String> withRetry(Supplier<String> op, int remaining) {
    return Effect.<RuntimeException, String>suspend(op)
        .catchAll(err -> remaining > 0
            ? withRetry(op, remaining - 1)
            : Effect.fail(err));
}

// Usage:
Pid actor = spawnEffectActor(system,
    (String url) -> withRetry(() -> fetch(url), 3)
        .flatMap(body -> Effect.suspend(() -> { handle(body); return Unit.unit(); }))
        .catchAll(err -> Effect.suspend(() -> {
            log("All retries exhausted: " + err.getMessage());
            return Unit.unit();
        })));
```

**See**: `EffectErrorHandlingExample.java`, `EffectRetryExample.java`

---

## 2. Request-Response (Ask Pattern)

`EffectActorBuilder` actors do not expose `ActorContext`. Embed a `Pid replyTo` field in the
request record to receive a reply:

```java
record ComputeRequest(String input, Pid replyTo) {}
record ComputeResult(String upper, int length) {}

Pid worker = new EffectActorBuilder<>(system,
    (ComputeRequest req) -> Effect.suspend(() -> {
        req.replyTo().tell(new ComputeResult(
                req.input().toUpperCase(),
                req.input().length()));
        return Unit.unit();
    })).withId("worker").spawn();

// Caller: spawn a one-shot collector, pass its Pid in the request
Pid collector = spawnEffectActor(system,
    (ComputeResult r) -> Effect.suspend(() -> {
        captured.set(r);
        latch.countDown();
        return Unit.unit();
    }));

worker.tell(new ComputeRequest("hello", collector));
// await latch, then read captured.get()
```

For `StatefulHandler` actors, use `system.ask(pid, msg, Duration.ofSeconds(5)).get()` instead.

**See**: `EffectAskPatternExample.java`

---

## 3. Stateful + Effect Actor Composition

A `StatefulHandler` holds domain state; an `EffectActorBuilder` actor performs side effects
(logging, enrichment). Wire them so the stateful actor fires messages to the effect actor:

```
test ──ask──► StatefulActor ──tell──► EffectActor (side-car)
       ◄──reply──┘
```

**Critical constraints:**

- `StatefulActor` journals messages **before** processing → all message and state types must
  implement `java.io.Serializable`
- Use UUID-based actor IDs to prevent cross-run journal accumulation:
  ```java
  String id = UUID.randomUUID().toString().substring(0, 8);
  Pid cart = system.statefulActorOf(new CartHandler(auditPid), new CartState())
      .withId("cart-" + id).spawn();
  ```
- `system.statefulActorOf(handlerInstance, initialState)` is required when the handler has
  constructor arguments

```java
sealed interface CartMessage extends Serializable
        permits CartMessage.AddItem, CartMessage.GetTotal {
    record AddItem(String name, double price) implements CartMessage {}
    record GetTotal() implements CartMessage {}
}

static class CartHandler implements StatefulHandler<CartState, CartMessage> {
    private final Pid auditPid;

    CartHandler(Pid auditPid) { this.auditPid = auditPid; }

    @Override
    public CartState receive(CartMessage message, CartState state, ActorContext context) {
        if (message instanceof CartMessage.AddItem add) {
            CartState newState = state.add(add.name(), add.price());
            context.tell(auditPid, new AuditMessage.ItemAdded(add.name(), add.price()));
            return newState;
        } else if (message instanceof CartMessage.GetTotal) {
            context.getSender().ifPresent(s -> context.tell(s, state.total()));
            return state;
        }
        return state;
    }
}

// Spawn effect side-car first, then stateful actor
Pid auditActor = spawnEffectActor(system,
    (AuditMessage msg) -> Effect.generate(ctx -> {
        ctx.perform(new LogCapability.Info("[audit] " + msg));
        return Unit.unit();
    }, logHandler));

String id = UUID.randomUUID().toString().substring(0, 8);
Pid cart = system.statefulActorOf(new CartHandler(auditActor), new CartState())
    .withId("cart-" + id).spawn();

// Ask from test:
Double total = (Double) system.ask(cart, new CartMessage.GetTotal(),
        Duration.ofSeconds(5)).get();
```

**See**: `EffectStatefulCompositionExample.java`

---

## 4. Linear Pipeline

Wire a multi-stage pipeline **sink-first**: each upstream stage captures the downstream `Pid`
in its lambda closure. No constructors or setters needed.

```
test → enricher → validator → transformer → sink
```

```java
// Stage 4 — sink (built first, no downstream Pid to capture)
Pid sinkActor = spawnEffectActor(system,
    (ProcessedRecord rec) -> Effect.suspend(() -> {
        sink.add(rec);
        latch.countDown();
        return Unit.unit();
    }));

// Stage 3 — transformer captures sinkActor
Pid transformer = new EffectActorBuilder<>(system,
    (ValidatedRecord vr) -> Effect.suspend(() -> {
        String result = vr.valid()
            ? new StringBuilder(vr.normalized()).reverse().toString()
            : vr.normalized();
        sinkActor.tell(new ProcessedRecord(vr.normalized(), result, vr.valid()));
        return Unit.unit();
    })).withId("transformer").spawn();

// Stage 2 — validator captures transformer
Pid validator = new EffectActorBuilder<>(system,
    (EnrichedRecord er) -> Effect.suspend(() -> {
        boolean valid = er.normalized().length() >= 5;
        transformer.tell(new ValidatedRecord(er.text(), er.normalized(), valid));
        return Unit.unit();
    })).withId("validator").spawn();

// Stage 1 — enricher captures validator
Pid enricher = new EffectActorBuilder<>(system,
    (RawRecord raw) -> Effect.suspend(() -> {
        validator.tell(new EnrichedRecord(raw.text(), raw.text().trim().toLowerCase()));
        return Unit.unit();
    })).withId("enricher").spawn();

enricher.tell(new RawRecord("  Hello World  "));
```

Notes:
- `EffectActorBuilder` message types do **not** need `Serializable` (no journaling)
- Mix `Effect.generate(ctx -> ..., handler)` (with logging) and `Effect.suspend(() -> ...)`
  (without) freely across stages

**See**: `EffectPipelineExample.java`

---

## 5. Fan-Out Dispatcher

A dispatcher forwards work items to a pool of worker actors using round-robin assignment:

```java
// Wrap List in a concrete record — raw List<String> breaks EffectActorBuilder type inference
record Batch(List<String> items) {}
record WorkItem(String text, Pid replyTo) {}
record WorkResult(String original, String processed) {}

// Build worker pool
Pid[] workers = new Pid[3];
for (int i = 0; i < 3; i++) {
    final int id = i;
    workers[i] = new EffectActorBuilder<>(system,
        (WorkItem item) -> Effect.generate(ctx -> {
            ctx.perform(new LogCapability.Debug("[worker-" + id + "] " + item.text()));
            item.replyTo().tell(new WorkResult(item.text(), item.text().toUpperCase()));
            return Unit.unit();
        }, logHandler)).withId("worker-" + id).spawn();
}

// Dispatcher with AtomicInteger cursor for round-robin
// AtomicInteger is safe inside the lambda: actors process one batch at a time
AtomicInteger cursor = new AtomicInteger(0);
Pid dispatcher = new EffectActorBuilder<>(system,
    (Batch batch) -> Effect.generate(ctx -> {
        ctx.perform(new LogCapability.Info(
                "[dispatcher] fanning out " + batch.items().size() + " items"));
        for (String item : batch.items()) {
            int idx = cursor.getAndIncrement() % workers.length;
            workers[idx].tell(new WorkItem(item, collector));
        }
        return Unit.unit();
    }, logHandler)).withId("dispatcher").spawn();

dispatcher.tell(new Batch(List.of("hello", "world", "cajun")));
```

**See**: `EffectFanOutExample.java`

---

## Example Index

| Example file | Patterns covered |
|---|---|
| `EffectActorExample.java` | First actor, `Effect.suspend`, `Effect.succeed`, `flatMap` |
| `EffectErrorHandlingExample.java` | `catchAll`, `orElse`, `mapError`, `attempt` |
| `EffectRetryExample.java` | Retry-with-backoff via recursive `catchAll` |
| `EffectAskPatternExample.java` | Reply-via-Pid, request-response with effect actor |
| `EffectStatefulCompositionExample.java` | `StatefulHandler` + `EffectActorBuilder` side-by-side |
| `EffectPipelineExample.java` | 4-stage linear pipeline, sink-first wiring |
| `EffectFanOutExample.java` | Dispatcher + worker pool, round-robin fan-out |
| `EffectCapabilityExample.java` | Custom capabilities, stateful handler, `compose()` |
| `EffectTestableCapabilityExample.java` | Swappable handlers, test double pattern |
