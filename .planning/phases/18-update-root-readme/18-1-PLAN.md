# Phase 18, Plan 1: Update Root README

## Objective

Replace the stale "Functional Actors with Effects" section in `README.md` (lines 944–1045)
with current Roux-native API examples. Remove all `Effect<State,Error,Result>` code and
fix the three stale documentation links at the bottom of that section.

---

## Context

### What needs to change

`README.md` lines 944–1045 contain the old Effect Actor section. Stale content confirmed by audit:

| Lines | Stale content |
|-------|--------------|
| 953 | `import com.cajunsystems.functional.ActorSystemEffectExtensions` (old import path) |
| 962–975 | `Effect<Integer, Throwable, Void>` 3-param match builder with `Effect.modify()` / `Effect.tell()` |
| 978–980 | `fromEffect(system, counterBehavior, 0)` — deleted method |
| 1004 | `Effect.modify(count -> count + 1)` |
| 1005 | `Effect.setState(0)` |
| 1010–1012 | `Effect.tell()` / `Effect.tellSelf()` / `Effect.ask()` — deleted static methods |
| 1017–1019 | `Effect.modify()` + `.andThen(Effect.tell(...))` |
| 1022–1027 | `Effect.attempt()` / `.recover()` / `.orElse()` — old combinators |
| 1032–1037 | `Effect.attempt(() -> ...)` blocking I/O pattern — old API |
| 1042 | Link to `docs/effect_monad_guide.md` (redirect doc, not primary) |
| 1043 | Link to `docs/effect_monad_api.md` (stale archive) |
| 1044 | Link to `docs/functional_actor_evolution.md` (stale archive) |

### Current API (Roux-native, what to use instead)

- `EffectActorBuilder<E, Message, A>` — spawns an actor that handles messages as `Effect<E,A>` pipelines
- `Effect.suspend(supplier)` — lazy computation; wraps blocking/side-effecting code
- `Effect.succeed(value)` + `.flatMap()` — chain steps
- `Effect.generate(ctx -> ..., handler.widen())` — for capability-based effects (logging etc.)
- `spawnEffectActor(system, msg -> Effect.suspend(...))` — convenience one-liner
- Import: `com.cajunsystems.functional.EffectActorBuilder`
- Import: `com.cajunsystems.roux.Effect`

---

## Tasks

### Task 1 — Replace the "Functional Actors with Effects" section

In `README.md`, replace lines 944–1045 (from `### Functional Actors with Effects` through the
"Learn More" block and its 3 bullet links) with the following new content.

**old_string** (exact match — use the full block from line 944 to 1045):

```
### Functional Actors with Effects

**New in Cajun**: Build actors using composable Effects for a more functional programming style.

Effects provide a powerful way to build actor behaviors by composing simple operations into complex workflows. Think of Effects as recipes that describe what your actor should do.

#### Quick Example

```java
import static com.cajunsystems.functional.ActorSystemEffectExtensions.*;

// Define messages
sealed interface CounterMsg {}
record Increment(int amount) implements CounterMsg {}
record Decrement(int amount) implements CounterMsg {}
record GetCount(Pid replyTo) implements CounterMsg {}

// Build behavior using effects - Note: Message type is at match level
Effect<Integer, Throwable, Void> counterBehavior = 
    Effect.<Integer, Throwable, Void, CounterMsg>match()
        .when(Increment.class, (state, msg, ctx) -> 
            Effect.modify(s -> s + msg.amount())
                .andThen(Effect.logState(s -> "Count: " + s)))
        
        .when(Decrement.class, (state, msg, ctx) ->
            Effect.modify(s -> s - msg.amount())
                .andThen(Effect.logState(s -> "Count: " + s)))
        
        .when(GetCount.class, (state, msg, ctx) ->
            Effect.tell(msg.replyTo(), state))
        
        .build();

// Create actor from effect
Pid counter = fromEffect(system, counterBehavior, 0)
    .withId("counter")
    .spawn();

// Use like any actor
counter.tell(new Increment(5));
```

#### Why Use Effects?

- **Composable**: Build complex behaviors from simple building blocks
- **Stack-Safe**: Prevents stack overflow on deep compositions (chain thousands of operations safely)
- **🚀 Blocking is Safe**: Cajun runs on Java 21+ Virtual Threads - write normal blocking code without fear!
  - No `CompletableFuture` chains or async/await complexity
  - Database calls, HTTP requests, file I/O - just write them naturally
  - Virtual threads handle suspension efficiently - you never block an OS thread
- **Type-safe**: Compile-time checking of state and error types
- **Testable**: Pure functions that are easy to test without spawning actors
- **Error handling**: Explicit error recovery with `.recover()`, `.orElse()`, and rich error combinators
- **Parallel Execution**: Built-in support for `parZip`, `parSequence`, `race`, and `withTimeout`
- **Readable**: Declarative style makes intent clear

#### Common Effect Patterns

**State Modification:**
```java
Effect.modify(count -> count + 1)              // Update state
Effect.setState(0)                             // Set to specific value
```

**Messaging:**
```java
Effect.tell(otherActor, message)               // Send to another actor
Effect.tellSelf(message)                       // Send to self
Effect.ask(actor, request, Duration.ofSeconds(5))  // Request-response
```

**Composition:**
```java
Effect.modify(s -> s + 1)
    .andThen(Effect.logState(s -> "New state: " + s))
    .andThen(Effect.tell(monitor, new StateUpdate(s)))
```

**Error Handling:**
```java
Effect.attempt(() -> riskyOperation())
    .recover(error -> defaultValue)
    .orElse(Effect.of(fallbackValue))
```

**Blocking I/O (Safe with Virtual Threads!):**
```java
// Write natural blocking code - Virtual Threads make it efficient!
Effect.attempt(() -> {
    var user = database.findUser(userId);           // Blocking - totally fine!
    var profile = httpClient.get("/api/profile");   // Also blocking - great!
    return new UserData(user, profile);
})
.recover(error -> UserData.empty());
```

#### Learn More

- **[Effect Monad Guide](docs/effect_monad_guide.md)** - Beginner-friendly introduction with examples
- **[Effect API Reference](docs/effect_monad_api.md)** - Complete API documentation
- **[Functional Actor Evolution](docs/functional_actor_evolution.md)** - Advanced patterns and best practices
```

**new_string** (exact replacement):

```
### Functional Actors with Effects

Build actors using composable [Roux](https://github.com/cajunsystems/roux) `Effect` pipelines —
a functional, type-safe style backed by the actor system's executor.

#### Quick Example

```java
import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.functional.EffectActorBuilder;
import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.data.Unit;

ActorSystem system = new ActorSystem();

// Define messages
sealed interface CounterMsg permits Increment, Decrement {}
record Increment(int amount) implements CounterMsg {}
record Decrement(int amount) implements CounterMsg {}

// Spawn an effect actor — handler returns Effect<E, A> per message
Pid counter = new EffectActorBuilder<>(
    system,
    (CounterMsg msg) -> switch (msg) {
        case Increment(var n) -> Effect.suspend(() -> {
            System.out.println("Incrementing by " + n);
            return Unit.unit();
        });
        case Decrement(var n) -> Effect.suspend(() -> {
            System.out.println("Decrementing by " + n);
            return Unit.unit();
        });
    }
).withId("counter").spawn();

counter.tell(new Increment(5));
system.shutdown();
```

#### Why Use Effects?

- **Composable**: Chain steps with `.flatMap()` / `.map()` — build complex pipelines from simple pieces
- **🚀 Blocking is Safe**: Effects run on Cajun's virtual-thread executor — write natural blocking code
- **Type-safe error handling**: `.catchAll()`, `.attempt()`, `retry(n)`, `timeout(Duration)`
- **Parallel execution**: `Effects.parAll()`, `Effects.race()`, `Effects.traverse()`
- **Resource safety**: `Resource<A>` guarantees cleanup on success and failure
- **Testable**: Handler logic is pure — test without spawning actors

#### Common Effect Patterns

**Chaining steps:**
```java
Effect.<RuntimeException, Integer>succeed(n)
    .flatMap(x -> Effect.suspend(() -> process(x)))
    .map(result -> result * 2)
```

**Error handling:**
```java
Effect.suspend(() -> riskyCall())
    .catchAll(err -> Effect.succeed(defaultValue))
```

**Retry & timeout:**
```java
Effect.suspend(() -> httpCall())
    .retry(3)                          // 3 additional attempts
    .timeout(Duration.ofSeconds(5))
```

**Parallel execution:**
```java
Effects.parAll(List.of(effect1, effect2, effect3))  // run concurrently
```

**Resource management:**
```java
Resource.fromCloseable(() -> openFile(path))
    .use(file -> Effect.suspend(() -> readLines(file)))
```

#### Learn More

- **[Getting Started with Effect Actors](docs/effect-actors/getting-started.md)** — first actor, `Effect.suspend`, `flatMap`, `LogCapability`
- **[Patterns Catalogue](docs/effect-actors/patterns.md)** — retry, timeout, parallel, resource, ask pattern
- **[Capabilities Guide](docs/effect-actors/capabilities.md)** — `Capability<R>`, `CapabilityHandler`, custom capabilities
- **[Migration Guide](docs/effect-actors/migration.md)** — upgrading from old `Effect<State,Error,Result>` API
```

---

## Verification

After the edit:

```bash
grep -n "Effect\.modify\|Effect\.tell\|Effect\.tellSelf\|Effect\.ask\|fromEffect\|effect_monad_api\|effect_monad_guide\|functional_actor_evolution\|Effect<State\|Effect<Integer" README.md
```

Expected: **zero results** (all stale refs removed).

Also confirm new links exist:
```bash
grep -n "effect-actors/getting-started\|effect-actors/patterns\|effect-actors/capabilities\|effect-actors/migration" README.md
```

Expected: **4 results** — one for each new link.

---

## Success Criteria

- [ ] Old Effect API code examples replaced with Roux-native `EffectActorBuilder` examples
- [ ] All 5 old `Effect.modify/tell/tellSelf/ask/logState` snippet blocks removed
- [ ] `fromEffect()` removed
- [ ] 3 stale doc links replaced with 4 current `effect-actors/` links
- [ ] Verification grep returns zero results for stale patterns

---

## Output

- `README.md` — Effect Actor section updated (lines 944–1045 replaced)
