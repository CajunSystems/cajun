# Phase 9, Plan 2: EffectFanOutExample

## Objective
Write `EffectFanOutExample.java` — a fan-out pattern where a single dispatcher effect
actor distributes work items across a pool of worker effect actors, with results
aggregated by a collector. Shows 1-to-N actor communication and concurrent processing.

## Context

### Fan-out architecture
```
test → dispatcher → worker-0 ─┐
                   → worker-1 ─┼→ collector (records results)
                   → worker-2 ─┘
```

The dispatcher receives a `Batch` and fans out one `WorkItem` per element to worker
actors using round-robin assignment. Workers process independently and reply to a
shared `replyTo` collector Pid embedded in the `WorkItem`.

### Key distinction from Phase 8 (reply-via-Pid)
Phase 8 showed one caller/one worker. Phase 9 fan-out shows:
- **Dispatcher-fan-out**: one actor distributes N items across M workers
- **Worker pool**: M independent actors sharing the same work type
- **Convergence**: N results flow back to one collector from different workers

### Message types

```java
// Fan-out: dispatcher sends one of these per item to each worker
record WorkItem(String text, Pid replyTo) {}

// Aggregation: each worker sends one of these to the collector
record WorkResult(String original, String processed) {}

// Dispatch: test sends one of these to the dispatcher
record Batch(List<String> items) {}
```

**Note**: Use `record Batch(List<String> items) {}` — NOT raw `List<String>` as
message type. Raw generic types like `List<String>` cause type inference failures
in `EffectActorBuilder` because the lambda parameter type becomes ambiguous.

### Worker pool creation pattern
```java
Pid[] workers = new Pid[N];
for (int i = 0; i < N; i++) {
    final int workerId = i;  // must be effectively final for lambda capture
    workers[i] = new EffectActorBuilder<>(system,
        (WorkItem item) -> Effect.generate(ctx -> {
            ctx.perform(new LogCapability.Debug("[worker-" + workerId + "] " + item.text()));
            item.replyTo().tell(new WorkResult(item.text(), item.text().toUpperCase()));
            return Unit.unit();
        }, logHandler)
    ).withId("worker-" + workerId).spawn();
}
```

### Round-robin dispatcher pattern
```java
AtomicInteger cursor = new AtomicInteger(0);
Pid dispatcher = new EffectActorBuilder<>(system,
    (Batch batch) -> Effect.generate(ctx -> {
        ctx.perform(new LogCapability.Info("[dispatcher] " + batch.items().size() + " items"));
        for (String item : batch.items()) {
            int idx = cursor.getAndIncrement() % workers.length;
            workers[idx].tell(new WorkItem(item, collector));
        }
        return Unit.unit();
    }, logHandler)
).withId("dispatcher").spawn();
```

### File location
`lib/src/test/java/examples/EffectFanOutExample.java`

---

## Tasks

### Task 1 — Write EffectFanOutExample.java

```java
package examples;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.functional.EffectActorBuilder;
import com.cajunsystems.functional.capabilities.ConsoleLogHandler;
import com.cajunsystems.functional.capabilities.LogCapability;
import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.capability.Capability;
import com.cajunsystems.roux.capability.CapabilityHandler;
import com.cajunsystems.roux.data.Unit;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.cajunsystems.functional.ActorSystemEffectExtensions.spawnEffectActor;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates fan-out from a dispatcher effect actor to a pool of worker effect actors.
 *
 * <p>A {@link com.cajunsystems.functional.EffectActorBuilder} dispatcher receives a
 * {@link Batch} of work items and distributes them across a worker pool using round-robin
 * assignment. Each worker processes its item independently and sends the result to a
 * shared collector actor.
 *
 * <p>Key patterns shown:
 * <ul>
 *   <li>Fan-out: one dispatcher forwards N items to N workers</li>
 *   <li>Worker pool: multiple actors of the same type sharing work concurrently</li>
 *   <li>Result aggregation: all workers reply to a single collector via embedded Pid</li>
 *   <li>{@code AtomicInteger} cursor for stateless round-robin inside a pure effect</li>
 * </ul>
 */
class EffectFanOutExample {

    // --- Message types ---

    /** A batch of text items sent to the dispatcher. */
    record Batch(List<String> items) {}

    /**
     * One unit of work sent from the dispatcher to a worker.
     * Embeds the reply-to Pid so workers know where to send results.
     */
    record WorkItem(String text, Pid replyTo) {}

    /** Result produced by a worker and sent to the collector. */
    record WorkResult(String original, String processed) {}

    private ActorSystem system;
    private CapabilityHandler<Capability<?>> logHandler;

    @BeforeEach
    void setUp() {
        system = new ActorSystem();
        logHandler = new ConsoleLogHandler().widen();
    }

    @AfterEach
    void tearDown() { system.shutdown(); }

    /**
     * Three work items are dispatched to three separate workers.
     * Each worker processes one item and replies to the shared collector.
     * The collector accumulates all three results.
     */
    @Test
    void dispatcherFansOutToThreeWorkers() throws InterruptedException {
        List<WorkResult> results = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(3);

        // Collector — aggregates results from all workers
        Pid collector = spawnEffectActor(system,
            (WorkResult r) -> Effect.generate(ctx -> {
                ctx.perform(new LogCapability.Info(
                        "[collector] \"" + r.original() + "\" → \"" + r.processed() + "\""));
                results.add(r);
                latch.countDown();
                return Unit.unit();
            }, logHandler)
        );

        // Worker pool — three independent actors of the same type
        Pid[] workers = new Pid[3];
        for (int i = 0; i < 3; i++) {
            final int workerId = i;
            workers[i] = new EffectActorBuilder<>(
                system,
                (WorkItem item) -> Effect.generate(ctx -> {
                    ctx.perform(new LogCapability.Debug(
                            "[worker-" + workerId + "] processing \"" + item.text() + "\""));
                    String processed = item.text().toUpperCase();
                    item.replyTo().tell(new WorkResult(item.text(), processed));
                    return Unit.unit();
                }, logHandler)
            ).withId("worker-" + workerId).spawn();
        }

        // Dispatcher — round-robin fan-out across the worker pool
        AtomicInteger cursor = new AtomicInteger(0);
        Pid dispatcher = new EffectActorBuilder<>(
            system,
            (Batch batch) -> Effect.generate(ctx -> {
                ctx.perform(new LogCapability.Info(
                        "[dispatcher] fanning out " + batch.items().size() +
                        " items to " + workers.length + " workers"));
                for (String item : batch.items()) {
                    int idx = cursor.getAndIncrement() % workers.length;
                    workers[idx].tell(new WorkItem(item, collector));
                }
                return Unit.unit();
            }, logHandler)
        ).withId("dispatcher").spawn();

        dispatcher.tell(new Batch(List.of("hello", "world", "cajun")));

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(3, results.size());
        assertTrue(results.stream().anyMatch(r ->
                r.original().equals("hello") && r.processed().equals("HELLO")));
        assertTrue(results.stream().anyMatch(r ->
                r.original().equals("world") && r.processed().equals("WORLD")));
        assertTrue(results.stream().anyMatch(r ->
                r.original().equals("cajun") && r.processed().equals("CAJUN")));
    }

    /**
     * A two-worker pool handles two consecutive batches (four items total).
     * All four results arrive at the collector regardless of processing order.
     *
     * <p>This test uses {@code Effect.suspend()} without capabilities to show
     * the lighter-weight form for stages that don't need per-step logging.
     * Workers produce the character count of each input as their "processed" result.
     */
    @Test
    void twoWorkerPoolHandlesTwoBatches() throws InterruptedException {
        List<WorkResult> results = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(4);

        Pid collector = spawnEffectActor(system,
            (WorkResult r) -> Effect.suspend(() -> {
                results.add(r);
                latch.countDown();
                return Unit.unit();
            })
        );

        // Two workers — each transforms text to "N chars"
        Pid[] workers = new Pid[2];
        for (int i = 0; i < 2; i++) {
            final int id = i;
            workers[i] = new EffectActorBuilder<>(
                system,
                (WorkItem item) -> Effect.generate(ctx -> {
                    ctx.perform(new LogCapability.Debug(
                            "[w" + id + "] \"" + item.text() + "\""));
                    item.replyTo().tell(
                            new WorkResult(item.text(), item.text().length() + " chars"));
                    return Unit.unit();
                }, logHandler)
            ).withId("pool-worker-" + i).spawn();
        }

        AtomicInteger cursor = new AtomicInteger(0);
        Pid dispatcher = new EffectActorBuilder<>(
            system,
            (Batch batch) -> Effect.suspend(() -> {
                for (String item : batch.items()) {
                    int idx = cursor.getAndIncrement() % workers.length;
                    workers[idx].tell(new WorkItem(item, collector));
                }
                return Unit.unit();
            })
        ).withId("pool-dispatcher").spawn();

        dispatcher.tell(new Batch(List.of("hi", "world")));    // 2 + 5 chars
        dispatcher.tell(new Batch(List.of("foo", "bar")));     // 3 + 3 chars

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(4, results.size());
        // Verify by checking specific processed values appear in results
        assertTrue(results.stream().anyMatch(r -> r.processed().equals("2 chars")));
        assertTrue(results.stream().anyMatch(r -> r.processed().equals("5 chars")));
        assertTrue(results.stream().anyMatch(r -> r.processed().equals("3 chars")));
        // "bar" is also 3 chars, so at least 2 results should be "3 chars"
        assertEquals(2, results.stream().filter(r -> r.processed().equals("3 chars")).count());
    }
}
```

Commit:
```bash
git add lib/src/test/java/examples/EffectFanOutExample.java
git commit -m "feat(9-9): add EffectFanOutExample — fan-out dispatcher to worker pool"
```

---

### Task 2 — Run tests and verify

```bash
./gradlew test --no-daemon
```

Must produce BUILD SUCCESSFUL. The new class adds 2 tests.

Expected minimum: **357 tests** (355 from plan 9-1 + 2 new).

No commit for this task — verification only.

---

## Potential deviations and fixes

**If `(Batch batch) ->` causes type inference failure:**

Add explicit generic types to `EffectActorBuilder`:
```java
new EffectActorBuilder<RuntimeException, Batch, Unit>(system, (Batch batch) -> ...)
```

**If `final int workerId = i` in loop causes "effectively final" error:**

Replace loop body with a method call, or use `var`:
```java
// This approach already works in Java 21 with --enable-preview
final int workerId = i;  // This IS effectively final — captured in the lambda below
```

**If `AtomicInteger cursor` inside the lambda causes repeated increment on the same call:**

The dispatcher sends all items in one effect execution — `cursor.getAndIncrement()` increments sequentially within a single effect run. This is fine since effects are sequential (not concurrent) within one actor invocation.

**If worker IDs collide across tests (test 1 uses worker-0/1/2, test 2 uses pool-worker-0/1):**

IDs are already distinct: `worker-0`, `worker-1`, `worker-2` in test 1 vs `pool-worker-0`, `pool-worker-1` in test 2. No collision.

---

## Verification

- [ ] `EffectFanOutExample.java` created with 2 tests
- [ ] Test 1: 3 items fanned out to 3 workers, all 3 results aggregated at collector
- [ ] Test 2: 4 items across 2 batches, 2-worker pool, all 4 results collected
- [ ] `./gradlew test` → BUILD SUCCESSFUL, ≥ 357 tests pass

## Success Criteria

Both tests pass. The example clearly shows the dispatcher-fan-out pattern and is
distinct from Phase 8's reply-via-Pid (one-caller / one-worker). The `Batch` wrapper
record demonstrates why concrete message types are preferred over raw generics.

## Output
- Created: `lib/src/test/java/examples/EffectFanOutExample.java`
