# Phase 9, Plan 1: EffectPipelineExample

## Objective
Write `EffectPipelineExample.java` — a 4-stage linear processing pipeline where every
stage is an `EffectActorBuilder` actor. Shows how to replace `WorkflowExample`-style
imperative actor chains with effect-native equivalents using `LogCapability` throughout.

## Context

### Pipeline architecture
```
test → enricher → validator → transformer → sink (collector)
```
Stages are wired via Pid closure capture: build `sink` first, then each upstream stage
captures the next stage's `Pid` in its lambda closure — no constructors, no setters.

### Stage responsibilities
| Stage | Input | Output | Logic |
|-------|-------|--------|-------|
| Enricher | `RawRecord(text)` | `EnrichedRecord(text, normalized)` | trim + lowercase |
| Validator | `EnrichedRecord` | `ValidatedRecord(text, normalized, valid, reason)` | length ≥ 5 |
| Transformer | `ValidatedRecord` | `ProcessedRecord(original, result, valid)` | reverse if valid, pass if not |
| Sink | `ProcessedRecord` | records result, counts down latch | — |

### Wiring pattern (build sink-first, capture downstream Pids)
```java
Pid sink        = spawnEffectActor(system, (ProcessedRecord r) -> ...);
Pid transformer = new EffectActorBuilder<>(system,
    (ValidatedRecord vr) -> Effect.generate(ctx -> {
        // ... sinkActor captured from outer scope
        sinkActor.tell(new ProcessedRecord(...));
        return Unit.unit();
    }, logHandler)).withId("transformer").spawn();
// etc. — each stage captures the next stage's Pid
```

### Key API facts (from prior phases)
- `Effect.generate(ctx -> { ctx.perform(new LogCapability.X(...)); ... return Unit.unit(); }, logHandler)` — effect with capability
- `Effect.suspend(() -> { ...; return Unit.unit(); })` — effect without capability (simpler test cases)
- `spawnEffectActor(system, handler)` — auto-generated ID, quick for sink/simple actors
- `new EffectActorBuilder<>(system, handler).withId("name").spawn()` — explicit ID
- `ConsoleLogHandler().widen()` — widens to `CapabilityHandler<Capability<?>>`
- All `EffectActorBuilder` actors run independently on the actor system executor

### File location
`lib/src/test/java/examples/EffectPipelineExample.java`

---

## Tasks

### Task 1 — Write EffectPipelineExample.java

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

import static com.cajunsystems.functional.ActorSystemEffectExtensions.spawnEffectActor;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates a 4-stage processing pipeline where every stage is an effect actor.
 *
 * <pre>
 * Source (test) → Enricher → Validator → Transformer → Sink
 * </pre>
 *
 * <p>Stages are wired by capturing the next stage's {@link Pid} in the effect lambda
 * closure — the same pattern as constructor injection but with no boilerplate class.
 * Each stage uses {@link com.cajunsystems.functional.capabilities.LogCapability} to
 * observe its work via {@link ConsoleLogHandler}.
 *
 * <p>Key patterns shown:
 * <ul>
 *   <li>Linear pipeline wiring via Pid capture in lambda closures (sink-first build)</li>
 *   <li>Per-stage logging with a shared {@code ConsoleLogHandler}</li>
 *   <li>Conditional logic inside the transformer (valid vs invalid paths)</li>
 *   <li>Invalid records are marked but not dropped — the sink receives every record</li>
 * </ul>
 */
class EffectPipelineExample {

    // --- Pipeline record types ---
    // No persistence — EffectActorBuilder actors are not StatefulActors,
    // so Serializable is NOT required for these types.

    record RawRecord(String text) {}
    record EnrichedRecord(String text, String normalized) {}
    record ValidatedRecord(String text, String normalized, boolean valid, String reason) {}
    record ProcessedRecord(String original, String result, boolean valid) {}

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
     * A valid record flows through all four stages and arrives at the sink transformed.
     *
     * <p>Pipeline trace for {@code "  Hello World  "}:
     * <ol>
     *   <li>Enricher: trim + lowercase → {@code "hello world"}</li>
     *   <li>Validator: length 11 ≥ 5 → {@code valid=true}</li>
     *   <li>Transformer: reverse → {@code "dlrow olleh"}</li>
     *   <li>Sink: records {@code ProcessedRecord("hello world", "dlrow olleh", true)}</li>
     * </ol>
     */
    @Test
    void validRecordFlowsThroughAllFourStages() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        List<ProcessedRecord> sink = new CopyOnWriteArrayList<>();

        // Stage 4 — Sink: captures the final result
        Pid sinkActor = spawnEffectActor(system,
            (ProcessedRecord rec) -> Effect.generate(ctx -> {
                ctx.perform(new LogCapability.Info(
                        "[sink] result=\"" + rec.result() + "\" valid=" + rec.valid()));
                sink.add(rec);
                latch.countDown();
                return Unit.unit();
            }, logHandler)
        );

        // Stage 3 — Transformer: reverses the normalized text if valid, passes through if not
        Pid transformer = new EffectActorBuilder<>(
            system,
            (ValidatedRecord vr) -> Effect.generate(ctx -> {
                ctx.perform(new LogCapability.Info(
                        "[transform] input=\"" + vr.normalized() + "\" valid=" + vr.valid()));
                String result = vr.valid()
                    ? new StringBuilder(vr.normalized()).reverse().toString()
                    : vr.normalized();
                ctx.perform(new LogCapability.Debug("[transform] output=\"" + result + "\""));
                sinkActor.tell(new ProcessedRecord(vr.normalized(), result, vr.valid()));
                return Unit.unit();
            }, logHandler)
        ).withId("transformer").spawn();

        // Stage 2 — Validator: requires normalized length >= 5
        Pid validator = new EffectActorBuilder<>(
            system,
            (EnrichedRecord er) -> Effect.generate(ctx -> {
                ctx.perform(new LogCapability.Info(
                        "[validate] checking=\"" + er.normalized() + "\""));
                boolean valid = er.normalized().length() >= 5;
                String reason = valid ? "ok" : "too short (min 5 chars)";
                if (!valid) {
                    ctx.perform(new LogCapability.Warn("[validate] rejected: " + reason));
                }
                transformer.tell(new ValidatedRecord(er.text(), er.normalized(), valid, reason));
                return Unit.unit();
            }, logHandler)
        ).withId("validator").spawn();

        // Stage 1 — Enricher: trims whitespace and lowercases
        Pid enricher = new EffectActorBuilder<>(
            system,
            (RawRecord raw) -> Effect.generate(ctx -> {
                ctx.perform(new LogCapability.Info("[enrich] raw=\"" + raw.text() + "\""));
                String normalized = raw.text().trim().toLowerCase();
                ctx.perform(new LogCapability.Debug("[enrich] normalized=\"" + normalized + "\""));
                validator.tell(new EnrichedRecord(raw.text(), normalized));
                return Unit.unit();
            }, logHandler)
        ).withId("enricher").spawn();

        // Source: send into the first stage
        enricher.tell(new RawRecord("  Hello World  "));

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(1, sink.size());
        assertEquals("hello world",  sink.get(0).original());
        assertEquals("dlrow olleh",  sink.get(0).result());
        assertTrue(sink.get(0).valid());
    }

    /**
     * Invalid records flow through all stages — the validator marks them invalid
     * but does not drop them. The sink receives both valid and invalid records.
     *
     * <p>Sends two records: {@code "Hi"} (too short → invalid) and
     * {@code "valid input"} (length 11 → valid). Both reach the sink; only the
     * valid record is reversed at the transformer stage.
     *
     * <p>This test uses {@code Effect.suspend()} without capabilities to show
     * the lighter-weight form suitable for stages that don't need logging.
     */
    @Test
    void invalidRecordsAreMarkedNotDropped() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(2);
        List<ProcessedRecord> sink = new CopyOnWriteArrayList<>();

        Pid sinkActor = spawnEffectActor(system,
            (ProcessedRecord rec) -> Effect.suspend(() -> {
                sink.add(rec);
                latch.countDown();
                return Unit.unit();
            })
        );

        Pid transformer = new EffectActorBuilder<>(
            system,
            (ValidatedRecord vr) -> Effect.suspend(() -> {
                String result = vr.valid()
                    ? new StringBuilder(vr.normalized()).reverse().toString()
                    : vr.normalized();
                sinkActor.tell(new ProcessedRecord(vr.normalized(), result, vr.valid()));
                return Unit.unit();
            })
        ).withId("transformer-b").spawn();

        Pid validator = new EffectActorBuilder<>(
            system,
            (EnrichedRecord er) -> Effect.suspend(() -> {
                boolean valid = er.normalized().length() >= 5;
                transformer.tell(new ValidatedRecord(er.text(), er.normalized(), valid, ""));
                return Unit.unit();
            })
        ).withId("validator-b").spawn();

        Pid enricher = new EffectActorBuilder<>(
            system,
            (RawRecord raw) -> Effect.suspend(() -> {
                String normalized = raw.text().trim().toLowerCase();
                validator.tell(new EnrichedRecord(raw.text(), normalized));
                return Unit.unit();
            })
        ).withId("enricher-b").spawn();

        enricher.tell(new RawRecord("Hi"));           // too short → invalid
        enricher.tell(new RawRecord("valid input"));  // length 11 → valid

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(2, sink.size());

        ProcessedRecord invalid = sink.stream()
                .filter(r -> !r.valid()).findFirst().orElseThrow();
        ProcessedRecord valid   = sink.stream()
                .filter(ProcessedRecord::valid).findFirst().orElseThrow();

        // Invalid: transformer passes it through unchanged
        assertEquals("hi",           invalid.original());
        assertEquals("hi",           invalid.result());

        // Valid: transformer reverses it
        assertEquals("valid input",  valid.original());
        assertEquals("tupni dilav",  valid.result());
    }
}
```

Commit:
```bash
git add lib/src/test/java/examples/EffectPipelineExample.java
git commit -m "feat(9-9): add EffectPipelineExample — 4-stage effect actor pipeline"
```

---

### Task 2 — Run tests and verify

```bash
./gradlew test --no-daemon
```

Must produce BUILD SUCCESSFUL. The new class adds 2 tests.

Expected minimum: **355 tests** (353 existing + 2 new).

No commit for this task — verification only.

---

## Potential deviations and fixes

**If type inference fails for the stage lambdas (e.g., `ValidatedRecord` vs inferred `Object`):**

Add explicit type witness to `EffectActorBuilder`:
```java
new EffectActorBuilder<RuntimeException, ValidatedRecord, Unit>(system,
    (ValidatedRecord vr) -> Effect.generate(...))
```

**If `Effect.generate` return type conflicts with `Effect.suspend` return type:**

Use `Effect.<RuntimeException, Unit>generate(...)` with explicit type witnesses.

**If `CopyOnWriteArrayList` ordering causes flaky assertion in test 2:**

Use `assertTrue(sink.stream().anyMatch(...))` instead of index-based access — already done in plan above.

---

## Verification

- [ ] `EffectPipelineExample.java` created with 2 tests
- [ ] Test 1: valid record traced through all 4 stages, result reversed
- [ ] Test 2: both valid and invalid records reach sink, invalid not reversed
- [ ] `./gradlew test` → BUILD SUCCESSFUL, ≥ 355 tests pass

## Success Criteria

Both tests pass. The example clearly shows the 4-stage pipeline pattern with sink-first
wiring and the distinction between records processed with vs without capabilities.

## Output
- Created: `lib/src/test/java/examples/EffectPipelineExample.java`
