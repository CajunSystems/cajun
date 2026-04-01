<objective>
Write two new example files demonstrating Roux v0.2.x concurrency and resource patterns:
1. EffectParallelExample.java — Effects.parAll(), Effects.race(), Effects.traverse()
   in actor-friendly usage patterns (complementing the pure runtime tests of Phase 13)
2. EffectResourceExample.java — Resource.make(), Resource.fromCloseable(),
   Resource.ensuring() with guaranteed cleanup on success and failure

No production code changes. Pure new example files.
</objective>

<execution_context>
- New file 1: lib/src/test/java/examples/EffectParallelExample.java
- New file 2: lib/src/test/java/examples/EffectResourceExample.java
- Package: examples
- Runtime: ActorEffectRuntime (same @BeforeEach/@AfterEach pattern as other examples)
- Actor: spawnEffectActor(system, (MsgType msg) -> Effect<E,A>)
- Reference: ActorEffectRuntimeConcurrencyTest (Phase 13) — already verified parAll/race/traverse
</execution_context>

<context>
## API reference — Effects (Roux v0.2.x)

### Effects.parAll(List<Effect<E,A>>) → Effect<Throwable, List<A>>
- Runs all effects concurrently; joins results in insertion order
- Error type widens to Throwable — test methods need `throws Throwable`
- Phase 13 confirmed: works through ActorEffectRuntime

### Effects.race(Effect<E,A>, Effect<E,A>) → Effect<Throwable, A>
### Effects.race(List<Effect<E,A>>) → Effect<Throwable, A>
- Returns whichever completes first; others cancelled
- Error type widens to Throwable

### Effects.traverse(List<A>, Function<A, Effect<E,B>>) → Effect<E, List<B>>
- Sequential — NOT parallel; error type stays as E (not widened)
- Short-circuits on first failure

### Effects.sequence(List<Effect<E,A>>) → Effect<E, List<A>>
- Sequential; error type stays as E (same as traverse without the mapping function)

## API reference — Resource<A> (Roux v0.2.x)

### Resource.make(Effect<E,A> acquire, Function<A, Effect<Throwable,Unit>> release)
```java
// acquire: obtains the resource
// release: always called after use (success or failure)
Resource<Connection> res = Resource.make(
    Effect.suspend(() -> pool.acquire()),        // acquire
    conn -> Effect.runnable(conn::close)         // release
);
```

### Resource.fromCloseable(Effect<E, A extends AutoCloseable>)
```java
// Convenience: release = resource.close()
Resource<AutoCloseable> res = Resource.fromCloseable(
    Effect.succeed(someAutoCloseable)
);
```

### resource.use(Function<A, Effect<E,B>>) → Effect<Throwable, B>
```java
// ALWAYS widens to Throwable — test methods need throws Throwable
Effect<Throwable, String> result = resource.use(conn ->
    Effect.suspend(() -> conn.execute("SELECT ..."))
);
```
- If use() succeeds: release runs, result returned
- If use() fails: release STILL runs; original error propagated
- If release fails: error suppressed; original error (if any) preserved

### Resource.ensuring(Effect<E,A> effect, Effect<Throwable,Unit> finalizer) → Effect<Throwable,A>
```java
// Static method — try-finally pattern
// finalizer always runs; finalizer errors are suppressed
Effect<Throwable, String> safe = Resource.ensuring(
    Effect.suspend(() -> riskyOperation()),
    Effect.runnable(() -> cleanup())
);
// Widens to Throwable — test methods need throws Throwable
```

### Resource.flatMap(Function<A, Resource<B>>) — USE WITH CAUTION
The flatMap implementation has a known simplification (outer value not available during
inner release). For composing resources that depend on each other, use nested use() instead:
```java
// CORRECT: nested use()
outerResource.use(outer ->
    innerResource(outer).use(inner ->
        Effect.succeed(combine(outer, inner))
    )
);
// AVOID: resource.flatMap() for complex compositions
```

## import summary
```java
import com.cajunsystems.roux.Effects;     // parAll, race, traverse
import com.cajunsystems.roux.Resource;    // make, fromCloseable, ensuring
import java.time.Duration;               // for parAll timing test
import java.util.List;                   // for parAll/traverse
import java.util.concurrent.atomic.AtomicBoolean;  // for resource release tracking
```

## Pattern: tracking resource lifecycle
```java
AtomicBoolean acquired = new AtomicBoolean(false);
AtomicBoolean released = new AtomicBoolean(false);

Resource<String> resource = Resource.make(
    Effect.suspend(() -> { acquired.set(true); return "conn"; }),
    conn -> Effect.runnable(() -> released.set(true))
);

String result = runtime.unsafeRun(resource.use(conn -> Effect.succeed("used:" + conn)));
assertEquals("used:conn", result);
assertTrue(acquired.get());
assertTrue(released.get());
```
</context>

<tasks>

## Task 1 — Create EffectParallelExample.java

**File**: `lib/src/test/java/examples/EffectParallelExample.java`

Write 3 tests demonstrating the parallel combinators in practical usage:

### Test 1.1 — `parAllFansOutWorkAndCollectsResultsInOrder()`
```java
@Test
void parAllFansOutWorkAndCollectsResultsInOrder() throws Throwable {
    // Simulate 3 independent data-fetch operations with different latencies
    Effect<RuntimeException, String> fetchA = Effect.suspend(() -> {
        Thread.sleep(50); return "resultA";
    });
    Effect<RuntimeException, String> fetchB = Effect.suspend(() -> {
        Thread.sleep(30); return "resultB";
    });
    Effect<RuntimeException, String> fetchC = Effect.suspend(() -> {
        Thread.sleep(40); return "resultC";
    });

    long start = System.currentTimeMillis();
    List<String> results = runtime.unsafeRun(Effects.parAll(List.of(fetchA, fetchB, fetchC)));
    long elapsed = System.currentTimeMillis() - start;

    // Results in insertion order (A, B, C) regardless of completion order
    assertEquals(List.of("resultA", "resultB", "resultC"), results);
    // Concurrent: ~50ms total, not ~120ms sequential
    assertTrue(elapsed < 200, "Expected concurrent execution, took: " + elapsed + "ms");
}
```

### Test 1.2 — `raceSelectsFastestDataSource()`
```java
@Test
void raceSelectsFastestDataSource() throws Throwable {
    // Simulate three data sources with different response times
    Effect<RuntimeException, String> slow   = Effect.suspend(() -> { Thread.sleep(300); return "slow-source"; });
    Effect<RuntimeException, String> medium = Effect.suspend(() -> { Thread.sleep(100); return "medium-source"; });
    Effect<RuntimeException, String> fast   = Effect.suspend(() -> { Thread.sleep(20);  return "fast-source"; });

    // race returns whichever completes first
    String winner = runtime.unsafeRun(Effects.race(List.of(slow, medium, fast)));

    assertEquals("fast-source", winner);
}
```

### Test 1.3 — `traverseTransformsEachItemSequentiallyInOrder()`
```java
@Test
void traverseTransformsEachItemSequentiallyInOrder() throws RuntimeException {
    // traverse applies a function to each item sequentially and collects results
    // Unlike parAll, traverse preserves order trivially (it's sequential)
    // Unlike map, the function can produce effects (I/O, failures, etc.)
    List<String> words = List.of("hello", "world", "cajun");

    Effect<RuntimeException, List<Integer>> wordLengths =
            Effects.traverse(words, word ->
                Effect.<RuntimeException, Integer>succeed(word.length())
            );

    List<Integer> result = runtime.unsafeRun(wordLengths);
    assertEquals(List.of(5, 5, 5), result);
}
```

**Full class template** (write the complete file):
```java
package examples;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.functional.ActorEffectRuntime;
import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.Effects;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates Effects.parAll(), Effects.race(), and Effects.traverse()
 * for parallel and sequential collection processing.
 *
 * ... (Javadoc summarising when to use each)
 */
class EffectParallelExample {
    // @BeforeEach/@AfterEach setup ...
    // 3 tests above ...
}
```

After writing, run:
```bash
./gradlew :lib:test --tests "examples.EffectParallelExample"
```
Fix any compile or test errors before Task 2.

---

## Task 2 — Create EffectResourceExample.java

**File**: `lib/src/test/java/examples/EffectResourceExample.java`

Write 4 tests demonstrating `Resource<A>` lifecycle guarantees:

### Test 2.1 — `resourceIsAlwaysReleasedAfterSuccessfulUse()`
```java
@Test
void resourceIsAlwaysReleasedAfterSuccessfulUse() throws Throwable {
    AtomicBoolean acquired = new AtomicBoolean(false);
    AtomicBoolean released = new AtomicBoolean(false);

    Resource<String> connection = Resource.make(
            Effect.suspend(() -> { acquired.set(true); return "db-connection"; }),
            conn -> Effect.runnable(() -> released.set(true))
    );

    String result = runtime.unsafeRun(
            connection.use(conn -> Effect.succeed("query-result-from:" + conn))
    );

    assertEquals("query-result-from:db-connection", result);
    assertTrue(acquired.get(), "Resource must have been acquired");
    assertTrue(released.get(), "Resource must be released after successful use");
}
```

### Test 2.2 — `resourceIsReleasedEvenWhenUseFails()`
```java
@Test
void resourceIsReleasedEvenWhenUseFails() {
    AtomicBoolean released = new AtomicBoolean(false);

    Resource<String> connection = Resource.make(
            Effect.succeed("db-connection"),
            conn -> Effect.runnable(() -> released.set(true))
    );

    // use() fails midway — release must STILL run
    assertThrows(Throwable.class, () ->
            runtime.unsafeRun(
                    connection.use(conn ->
                            Effect.<RuntimeException, String>fail(new RuntimeException("query failed"))
                    )
            )
    );

    assertTrue(released.get(), "Resource must be released even when use() fails");
}
```

### Test 2.3 — `fromCloseableReleasesAutoCloseableOnCompletion()`
```java
@Test
void fromCloseableReleasesAutoCloseableOnCompletion() throws Throwable {
    AtomicBoolean closed = new AtomicBoolean(false);

    // Simulate any AutoCloseable resource (InputStream, Connection, etc.)
    AutoCloseable mockStream = () -> closed.set(true);

    Resource<AutoCloseable> resource = Resource.fromCloseable(
            Effect.succeed(mockStream)
    );

    String result = runtime.unsafeRun(
            resource.use(stream -> Effect.succeed("data read from stream"))
    );

    assertEquals("data read from stream", result);
    assertTrue(closed.get(), "AutoCloseable.close() must have been called");
}
```

### Test 2.4 — `ensuringRunsFinalizerOnBothSuccessAndFailure()`
```java
@Test
void ensuringRunsFinalizerOnBothSuccessAndFailure() throws Throwable {
    AtomicBoolean finalizerRan = new AtomicBoolean(false);

    // Success case: finalizer still runs
    runtime.unsafeRun(Resource.ensuring(
            Effect.succeed("ok"),
            Effect.runnable(() -> finalizerRan.set(true))
    ));
    assertTrue(finalizerRan.get(), "Finalizer must run on success");

    // Failure case: finalizer still runs
    finalizerRan.set(false);
    assertThrows(Throwable.class, () ->
            runtime.unsafeRun(Resource.ensuring(
                    Effect.<RuntimeException, String>fail(new RuntimeException("fail")),
                    Effect.runnable(() -> finalizerRan.set(true))
            ))
    );
    assertTrue(finalizerRan.get(), "Finalizer must run on failure");
}
```

**Full class template** (write the complete file):
```java
package examples;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.functional.ActorEffectRuntime;
import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.Resource;
import org.junit.jupiter.api.*;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates Resource<A> for managed resource lifecycle.
 *
 * ... (Javadoc)
 */
class EffectResourceExample {
    // @BeforeEach/@AfterEach setup ...
    // 4 tests above ...
}
```

After writing, run:
```bash
./gradlew :lib:test --tests "examples.EffectResourceExample"
```
Fix any compile or test errors before Task 3.

---

## Task 3 — Run full test suite

```bash
./gradlew test
```

Expected: 376 + 3 parallel + 4 resource = **383 tests, 0 failures**.

If any test fails:
1. Read failure stack trace
2. Timing failures: increase bounds (parAll timing test uses <200ms bound — safe on slow CI)
3. Resource tests: ensure AtomicBoolean is reset between sub-cases in test 2.4
4. Fix minimally — no production code changes

</tasks>

<verification>
- [ ] EffectParallelExample.java created with 3 tests (parAll, race, traverse)
- [ ] EffectResourceExample.java created with 4 tests (make, fromCloseable, ensuring)
- [ ] ./gradlew test → 383 tests, 0 failures
- [ ] Each file committed individually
- [ ] SUMMARY.md created, STATE.md + ROADMAP updated, metadata committed
</verification>

<success_criteria>
- Effects.parAll/race/traverse shown in practical patterns (not just integration tests)
- Resource<A> lifecycle guarantee demonstrated: release runs on success AND failure
- fromCloseable convenience pattern shown with AutoCloseable
- Resource.ensuring shown as try-finally alternative
- All examples self-contained and runnable by Cajun library users
- Test count at 383, full suite green
</success_criteria>

<output>
- Created: lib/src/test/java/examples/EffectParallelExample.java
- Created: lib/src/test/java/examples/EffectResourceExample.java
- Created: .planning/phases/15-concurrency-resource-examples/15-1-SUMMARY.md
</output>
