# Testing

## Framework

| Library | Version | Purpose |
|---------|---------|---------|
| JUnit Jupiter | 5.11.1 | Core test framework |
| Mockito | 5.7.0 | Mocking |
| mockito-junit-jupiter | 5.7.0 | JUnit 5 integration |
| Awaitility | 4.2.0 | Async condition waiting |
| Custom test-utils | — | TestKit, TestProbe, AsyncAssertion, etc. |

## Test Categories

| Category | Tag | Run Command |
|----------|-----|------------|
| Unit/Integration | (default) | `./gradlew test` |
| Performance | `@Tag("performance")` | `./gradlew performanceTest` |
| Cluster (Etcd) | `requires-etcd` | Excluded by default; requires external Etcd |

Performance tests excluded from default runs (timeout 5 min per test) to keep CI fast.

## Test Structure

- Tests co-located with source: `src/test/java/` mirroring production package structure
- `@BeforeEach` / `@AfterEach` for fresh `ActorSystem` per test — no shared state
- `@Nested` classes for grouping related tests
- `@Timeout` on integration tests to prevent hangs (10 seconds typical)

```java
@BeforeEach
void setUp() {
    actorSystem = new ActorSystem();
}

@AfterEach
void tearDown() {
    if (actorSystem != null) {
        actorSystem.shutdown();
    }
}
```

## Test Naming

- `shouldX()` — preferred for behavior descriptions (e.g., `shouldBeAbleToCreateAGreetingActor()`)
- `testX()` — also used (e.g., `testBasicMessageProcessing()`)
- Names state expected behavior clearly, avoid vague names

## Test Patterns

### Arrange-Act-Assert
Clear separation, explicit sections:
```java
@Test
void testMessageProcessing() {
    // Arrange
    Pid pid = system.actorOf(MyHandler.class).withId("test").spawn();

    // Act
    pid.tell(new MyMessage("hello"));

    // Assert
    AsyncAssertion.eventually(
        () -> handler.getCount() == 1,
        Duration.ofSeconds(2)
    );
}
```

### Async Assertions
Custom `AsyncAssertion` utility avoids fragile Thread.sleep:
```java
AsyncAssertion.eventually(
    () -> actor.getCountSync() == 5,
    Duration.ofSeconds(2)
);

AsyncAssertion.awaitValue(completableFuture, Duration.ofSeconds(1));
```

### Countdown Latches
For explicit synchronization in concurrent tests:
```java
CountDownLatch completionLatch = new CountDownLatch(MESSAGE_COUNT);
// ... send messages ...
assertTrue(completionLatch.await(5, TimeUnit.SECONDS));
```

### AtomicBoolean/Integer
For thread-safe state tracking in callbacks:
```java
AtomicBoolean received = new AtomicBoolean(false);
AtomicInteger count = new AtomicInteger(0);
```

### Mock Journals
Custom mock implementations for persistence testing:
```java
MockBatchedMessageJournal<TestMessage> mockJournal = new MockBatchedMessageJournal<>();
```

## test-utils Module

Dedicated module `test-utils` with reusable testing infrastructure:

| Class | Purpose |
|-------|---------|
| `TestKit` | Main entry point — AutoCloseable, fluent API |
| `TestPid<M>` | PID wrapper with test-specific methods |
| `TestProbe<M>` | Message capture probe for verifying actor output |
| `MailboxInspector` | Inspect mailbox contents |
| `StateInspector` | Access actor state directly |
| `MessageCapture` | Capture and assert on messages |
| `AskTestHelper` | Ask pattern test utilities |
| `AsyncAssertion` | Async condition waiting utilities |
| `PerformanceAssertion` | Performance measurement and assertions |
| `MessageMatcher` | Message matching utilities |
| `TempPersistenceExtension` | JUnit extension for temporary persistence |

```java
try (TestKit testKit = TestKit.create()) {
    TestPid<String> actor = testKit.spawn(MyHandler.class);
    TestProbe<String> probe = testKit.createProbe();

    actor.tell("hello");
    probe.expectMessage(Duration.ofSeconds(1));
}
```

`TempPersistenceExtension` usage:
```java
@ExtendWith(TempPersistenceExtension.class)
class MyPersistenceTest { ... }
```

## Key Test Files

| File | Purpose |
|------|---------|
| `ActorTest.java` | Core actor message processing |
| `StatefulActorTest.java` | Stateful actors with persistence |
| `ActorErrorHandlingTest.java` | Error handling and supervision |
| `FunctionalActorTest.java` | Functional-style actor usage |
| `BackpressureIntegrationTest.java` | Backpressure system integration |
| `NewEffectOperatorsTest.java` | Effect monad operators (1272 LOC) |
| `EffectGeneratorTest.java` | Generator-based effects |
| Various `*PerformanceTest.java` | Throughput and latency benchmarks |

## Test Coverage Assessment

**Well Covered:**
- Core actor lifecycle and message passing ✅
- Stateful actors with persistence ✅
- Backpressure state transitions ✅
- Ask pattern (request-response) ✅
- Error handling and basic supervision ✅
- Thread pool configurations ✅

**Known Gaps** (from `docs/supervision_audit.md`):
- ❌ RESUME strategy — no test verifying actor continues after error
- ❌ STOP strategy — no test verifying actor stops permanently
- ❌ Multiple restart scenarios — consecutive failures
- ❌ Concurrent error handling — errors during batch processing
- ❌ shouldReprocess flag — detailed reprocessing behavior
- ❌ Full mailbox during restart — message loss scenarios
- ❌ Multi-actor failure cascades (ESCALATE)
- ❌ Cluster mode + persistence combined
- ❌ Memory leak detection under sustained load

## Running Tests

```bash
# Default test run (excludes performance + etcd)
./gradlew test

# Run performance tests
./gradlew performanceTest

# Run specific test class
./gradlew test --tests "com.cajunsystems.ActorSystemTest"

# Run specific test
./gradlew test --tests "com.cajunsystems.ActorTest#testBasicMessageProcessing"
```
