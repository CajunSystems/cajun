<objective>
Verify that Effects.parAll(), Effects.race(), Effects.traverse(), effect.timeout(Duration),
and scoped-fork capability inheritance all work correctly through ActorEffectRuntime.

Two files are touched:
1. NEW: ActorEffectRuntimeConcurrencyTest.java — parAll, race, traverse
2. EXISTING: ActorEffectRuntimeTest.java — add timeout and scoped-fork tests

No production code changes expected. These are pure integration tests that confirm
the Roux v0.2.1 API functions correctly when dispatch runs through Cajun's actor executor.
</objective>

<execution_context>
- Existing test: `lib/src/test/java/com/cajunsystems/functional/ActorEffectRuntimeTest.java`
  (9 tests; @BeforeEach creates ActorSystem + ActorEffectRuntime, @AfterEach calls system.shutdown())
- New test file location: `lib/src/test/java/com/cajunsystems/functional/ActorEffectRuntimeConcurrencyTest.java`
- Package: `com.cajunsystems.functional`
- Runtime reference: `runtime.unsafeRun(effect)` — throws if effect fails
- Async runtime: `runtime.runAsync(effect, onSuccess, onError)`
- Import: `import com.cajunsystems.roux.Effects;` (for parAll, race, traverse)
- Import: `import com.cajunsystems.roux.Fiber;` (for fiber.join())
</execution_context>

<context>
## API reference — new in Roux v0.2.0/v0.2.1

### Effects.parAll()
```java
// Signature
public static <E extends Throwable, A> Effect<Throwable, List<A>> parAll(List<Effect<E, A>> effects)
// Error type always widens to Throwable — test methods need `throws Throwable`
// Forks all effects concurrently, joins in order → results preserve input order
// First failure propagates (others left to complete/interrupt)
```

### Effects.race()
```java
// Two-arg variant
public static <E extends Throwable, A> Effect<Throwable, A> race(Effect<E, A> first, Effect<E, A> second)
// List variant
public static <E extends Throwable, A> Effect<Throwable, A> race(List<Effect<E, A>> effects)
// Returns whichever completes first; cancels the rest
```

### Effects.traverse()
```java
// Sequential — not parallel
public static <E extends Throwable, A, B> Effect<E, List<B>> traverse(List<A> items, Function<A, Effect<E, B>> f)
// Error type stays as E (not widened to Throwable)
```

### effect.timeout(Duration)
```java
default Effect<Throwable, A> timeout(Duration duration)
// Fails with java.util.concurrent.TimeoutException if effect doesn't complete in time
// Error type widens to Throwable — test methods need `throws Throwable`
```

### Scoped fork (v0.2.1 fix)
```java
// Effect.scoped creates a managed scope
static <E extends Throwable, A> Effect<E, A> scoped(Function<EffectScope, Effect<E, A>> body)

// forkIn forks an effect within that scope; inherits parent ExecutionContext (v0.2.1 fix)
default Effect<Throwable, Fiber<E, A>> forkIn(EffectScope scope)  // = scope.fork(this)

// Fiber.join() returns Effect<E,A>; call .widen() to align with Throwable chain
// fiber.join().widen()  →  Effect<Throwable, A>
```

Before v0.2.1: `scope.fork()` did NOT inherit the parent's capability handler —
capabilities inside forked effects threw `MissingCapabilityHandlerException`.
After v0.2.1: the ExecutionContext (including capability handlers) is propagated to forks.

## Existing test setup pattern (from ActorEffectRuntimeTest.java)
```java
@BeforeEach void setUp() {
    system = new ActorSystem();
    runtime = new ActorEffectRuntime(system);
}
@AfterEach void tearDown() { system.shutdown(); }

// Sync test pattern:
Effect<RuntimeException, Integer> effect = Effect.succeed(42);
assertEquals(42, runtime.unsafeRun(effect));

// Async test pattern:
CountDownLatch latch = new CountDownLatch(1);
runtime.runAsync(effect, v -> { ...; latch.countDown(); }, err -> latch.countDown());
assertTrue(latch.await(5, TimeUnit.SECONDS));

// Fiber pattern:
Effect<Throwable, Fiber<RuntimeException, String>> forked =
        Effect.<RuntimeException, String>succeed("from-fiber").fork();
Effect<Throwable, String> workflow = forked.flatMap(fiber -> fiber.join().widen());
assertEquals("from-fiber", runtime.unsafeRun(workflow));
```

## Capability handler pattern for scoped-fork test
```java
// Local test-local sealed capability
sealed interface TrackCapability extends Capability<String>
        permits TrackCapability.Label {
    record Label(String name) implements TrackCapability {}
}

// Handler implementing Capability<?> contract (v0.2.0 compose() fix)
CapabilityHandler<Capability<?>> handler = new CapabilityHandler<>() {
    @Override @SuppressWarnings("unchecked")
    public <R> R handle(Capability<?> cap) {
        if (cap instanceof TrackCapability.Label l) {
            return (R) ("result-" + l.name());
        }
        throw new UnsupportedOperationException();
    }
};
```
</context>

<tasks>

## Task 1 — Write ActorEffectRuntimeConcurrencyTest.java (new file)

**File**: `lib/src/test/java/com/cajunsystems/functional/ActorEffectRuntimeConcurrencyTest.java`
**Package**: `com.cajunsystems.functional`

Write a JUnit 5 test class with the same @BeforeEach/@AfterEach setup pattern as
`ActorEffectRuntimeTest`. Include the following tests:

### Test 1.1 — `parAllCollectsResultsInOrder()`
- Create `List.of(Effect.succeed(1), Effect.succeed(2), Effect.succeed(3))`
- Run with `Effects.parAll(effects)`
- Assert result equals `List.of(1, 2, 3)` (order preserved)
- Method needs `throws Throwable`

### Test 1.2 — `parAllRunsEffectsConcurrently()`
- Create 3 effects that each sleep 50ms then return a distinct value
  (use `Effect.suspend(() -> { Thread.sleep(50); return n; })`)
- Measure elapsed time: `long start = System.currentTimeMillis();`
- Assert elapsed < 200ms (concurrently ~50ms, not sequentially ~150ms)
  Use a loose bound (200ms) to avoid flakiness on slow CI
- Assert all 3 values are present in result (order may vary due to join timing,
  but parAll preserves insertion order — assert `equals(List.of(1, 2, 3))`)
- Method needs `throws Throwable`

### Test 1.3 — `parAllPropagatesFirstFailure()`
- Create one failing effect and two succeeding effects
- Run with `Effects.parAll()`
- Assert `assertThrows(RuntimeException.class, () -> runtime.unsafeRun(par))`
- Method needs `throws Throwable` in the outer scope

### Test 1.4 — `raceReturnsFirstCompletingEffect()`
- Create a slow effect (sleep 200ms, returns "slow") and a fast effect (sleep 10ms, returns "fast")
- Run `Effects.race(slow, fast)`
- Assert result equals "fast"
- Method needs `throws Throwable`

### Test 1.5 — `traverseAppliesFunctionToAllItems()`
- `Effects.traverse(List.of(1, 2, 3), n -> Effect.<RuntimeException, Integer>succeed(n * 10))`
- Assert result equals `List.of(10, 20, 30)`
- traverse does NOT widen error type — method can throw `RuntimeException`

### Test 1.6 — `traverseShortCircuitsOnFailure()`
- `Effects.traverse(List.of(1, 2, 3), n -> n == 2 ? Effect.fail(new RuntimeException("fail-at-2")) : Effect.succeed(n))`
- Assert `assertThrows(RuntimeException.class, () -> runtime.unsafeRun(t))`

After writing, run `./gradlew :lib:test --tests "com.cajunsystems.functional.ActorEffectRuntimeConcurrencyTest"`.
Fix any compile or test errors before proceeding to Task 2.

---

## Task 2 — Add timeout tests to ActorEffectRuntimeTest.java

**File**: `lib/src/test/java/com/cajunsystems/functional/ActorEffectRuntimeTest.java`

Add two new tests at the end of the existing class (before the closing brace).

### Test 2.1 — `timeoutExpiresWhenEffectExceedsDeadline()`
```java
@Test
void timeoutExpiresWhenEffectExceedsDeadline() {
    Effect<Throwable, String> slow = Effect.<RuntimeException, String>suspend(() -> {
        Thread.sleep(500);
        return "too late";
    }).timeout(Duration.ofMillis(50));

    assertThrows(RuntimeException.class, () -> runtime.unsafeRun(slow));
    // TimeoutException is a Throwable; the runtime wraps it in RuntimeException
    // or propagates it directly — assertThrows(RuntimeException.class, ...) catches either
}
```
Note: if the test shows that `TimeoutException` (from `java.util.concurrent`) propagates as-is
as a `Throwable` (not wrapped), use:
```java
Throwable thrown = assertThrowsExactly(/* or assertThrows */ Throwable.class, ...);
assertInstanceOf(TimeoutException.class, thrown);
// or: assertTrue(thrown instanceof TimeoutException || thrown.getCause() instanceof TimeoutException);
```
Adjust the assertion based on what actually propagates.

### Test 2.2 — `timeoutDoesNotTriggerForFastEffect()`
```java
@Test
void timeoutDoesNotTriggerForFastEffect() throws Throwable {
    Effect<Throwable, String> fast = Effect.<RuntimeException, String>suspend(() -> {
        Thread.sleep(10);
        return "on time";
    }).timeout(Duration.ofMillis(500));

    assertEquals("on time", runtime.unsafeRun(fast));
}
```

Required imports to add: `import java.time.Duration;` and
`import java.util.concurrent.TimeoutException;`

After writing, run `./gradlew :lib:test --tests "com.cajunsystems.functional.ActorEffectRuntimeTest"`.
Fix assertion issues based on what `timeout()` actually throws.

---

## Task 3 — Add scoped-fork capability inheritance test to ActorEffectRuntimeTest.java

**File**: `lib/src/test/java/com/cajunsystems/functional/ActorEffectRuntimeTest.java`

Add one more test at the end of the class. This test verifies the v0.2.1 fix that
`scope.fork()` inherits the parent's capability handler.

Define a test-local capability inside the test method:

```java
@Test
void scopedForkInheritsCapabilityHandlerFromParent() throws Throwable {
    // Test-local capability
    sealed interface TrackCapability extends Capability<String>
            permits TrackCapability.Label {
        record Label(String name) implements TrackCapability {}
    }

    // Handler that prefixes all labels with "handled-"
    CapabilityHandler<Capability<?>> tracker = new CapabilityHandler<>() {
        @Override @SuppressWarnings("unchecked")
        public <R> R handle(Capability<?> cap) {
            if (cap instanceof TrackCapability.Label l) {
                return (R) ("handled-" + l.name());
            }
            throw new UnsupportedOperationException();
        }
    };

    // Effect: outer scope uses capability, then forks child that also uses capability
    // The v0.2.1 fix ensures the forked child inherits the handler from the outer context
    Effect<Throwable, String> effect = Effect.scoped(scope -> {
        // Fork a child effect that uses the capability
        Effect<Throwable, Fiber<RuntimeException, String>> forkedFiber =
                Effect.<RuntimeException, String>from(new TrackCapability.Label("child"))
                      .forkIn(scope);

        // Join the fork, then also use the capability in parent after joining
        return forkedFiber
                .flatMap(fiber -> fiber.join().widen())
                .flatMap(childResult ->
                    Effect.<RuntimeException, String>from(new TrackCapability.Label("parent"))
                          .map(parentResult -> childResult + "+" + parentResult)
                          .widen()
                );
    });

    // Run with the handler injected — forked child must inherit it (v0.2.1 fix)
    String result = runtime.unsafeRunWithHandler(effect, tracker);

    // Both parent and child capability calls should have been handled
    assertEquals("handled-child+handled-parent", result);
}
```

Required imports to add:
```java
import com.cajunsystems.roux.Effect;        // already present
import com.cajunsystems.roux.EffectScope;   // new — for type in scoped lambda if needed
import com.cajunsystems.roux.Fiber;         // already present
import com.cajunsystems.roux.capability.Capability;      // new
import com.cajunsystems.roux.capability.CapabilityHandler; // new
```

If `TrackCapability` as a local sealed interface doesn't compile (Java 21 local sealed
interface restrictions), move it to be a static nested class of the test class.

After writing, run `./gradlew :lib:test --tests "com.cajunsystems.functional.ActorEffectRuntimeTest"`.

---

## Task 4 — Run full test suite

```bash
./gradlew test
```

Expected: 362 + 8 new tests = ~370 tests, 0 failures (excluding pre-existing ClusterModeTest).

If any new test fails:
1. Read the failure message and stack trace
2. Check if it's a timing sensitivity issue (flaky concurrent test) — increase bounds
3. Check if it's a type/API mismatch — consult the failing assertion and fix the test
4. Fix minimally — do not change production code

</tasks>

<verification>
- [ ] `ActorEffectRuntimeConcurrencyTest.java` created with 6 tests
- [ ] Tests 1.1–1.3 (parAll) pass
- [ ] Tests 1.4 (race) passes
- [ ] Tests 1.5–1.6 (traverse) pass
- [ ] Timeout tests (2.1, 2.2) added to `ActorEffectRuntimeTest.java` and pass
- [ ] Scoped-fork capability inheritance test (3.1) added and passes
- [ ] Full suite: `./gradlew test` → 370+ tests, 0 new failures
</verification>

<success_criteria>
- parAll(), race(), traverse() confirmed working through ActorEffectRuntime
- timeout(Duration) confirmed working — deadline enforced, success case unaffected
- Scoped fork confirmed to inherit capability handler (v0.2.1 regression verified)
- All tests committed individually; full suite green
</success_criteria>

<output>
- Created: `lib/src/test/java/com/cajunsystems/functional/ActorEffectRuntimeConcurrencyTest.java`
- Modified: `lib/src/test/java/com/cajunsystems/functional/ActorEffectRuntimeTest.java`
- Create: `.planning/phases/13-bridge-concurrency-timeout/13-1-SUMMARY.md`
</output>
