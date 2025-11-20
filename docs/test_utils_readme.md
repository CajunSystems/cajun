# Cajun Test Utilities

Comprehensive testing utilities for the Cajun actor system - making actor testing clean, fast, and approachable.

## Overview

The Cajun test utilities eliminate common pain points in actor testing:

- ❌ **No more `Thread.sleep()`** - Use `AsyncAssertion` for deterministic waiting
- ❌ **No more `CountDownLatch` boilerplate** - Built-in synchronization with `TestProbe`
- ❌ **No more polling loops** - Direct state inspection with `StateInspector`
- ❌ **No more reflection hacks** - Proper test instrumentation
- ✅ **Clean, readable tests** - Fluent API design
- ✅ **Fast test execution** - Minimal waiting, maximum efficiency
- ✅ **Complete visibility** - Inspect state, mailbox, and messages

## Installation

Add the test utilities to your project's test dependencies:

**Gradle:**
```gradle
dependencies {
    testImplementation 'com.cajunsystems:cajun-test:0.1.4'
}
```

**Maven:**
```xml
<dependency>
    <groupId>com.cajunsystems</groupId>
    <artifactId>cajun-test</artifactId>
    <version>0.1.4</version>
    <scope>test</scope>
</dependency>
```

**Note**: The test utilities require Java 21+ with preview features enabled (same as the core Cajun library).

## Quick Example

### Before (Without Test Utils)
```java
@Test
void testCounter() throws InterruptedException {
    ActorSystem system = new ActorSystem();
    try {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger result = new AtomicInteger();
        
        Pid counter = system.register((msg) -> {
            if (msg instanceof Increment inc) {
                result.set(inc.value());
                latch.countDown();
            }
            return null;
        }, "counter");
        
        Thread.sleep(100);  // Hope actor initialized
        counter.tell(new Increment(5));
        
        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(5, result.get());
    } finally {
        system.shutdown();
    }
}
```

### After (With Test Utils)
```java
@Test
void testCounter() {
    try (TestKit testKit = TestKit.create()) {
        TestPid<Object> counter = testKit.spawnStateful(CounterHandler.class, 0);
        
        counter.tell(new Increment(5));
        
        // No Thread.sleep()! Wait for exact state
        AsyncAssertion.awaitValue(
            counter.stateInspector()::current,
            5,
            Duration.ofSeconds(1)
        );
    }
}
```

## Core Components

### 1. TestKit
Main entry point for creating test actors and probes. Manages actor system lifecycle.

```java
try (TestKit testKit = TestKit.create()) {
    // Spawn regular actors
    TestPid<String> actor = testKit.spawn(MyHandler.class);
    
    // Spawn stateful actors
    TestPid<Object> stateful = testKit.spawnStateful(CounterHandler.class, 0);
    
    // Create message probes
    TestProbe<Response> probe = testKit.createProbe();
    
    // Access the actor system
    ActorSystem system = testKit.system();
}

// Use builder for custom configuration
try (TestKit testKit = TestKit.builder()
        .withTimeout(Duration.ofSeconds(10))
        .build()) {
    // Test code with custom timeout
}
```

**Key Methods:**
- `create()` - Create with default settings
- `create(system)` - Use existing ActorSystem
- `builder()` - Create builder for custom configuration
- `spawn(handlerClass)` - Spawn regular actor
- `spawn(handler)` - Spawn with lambda handler
- `spawnStateful(handlerClass, initialState)` - Spawn stateful actor
- `createProbe()` - Create message probe
- `system()` - Get underlying ActorSystem
- `getDefaultTimeout()` / `withDefaultTimeout(timeout)` - Timeout configuration

### 2. TestProbe
Capture and assert on messages sent to a probe.

```java
TestProbe<Response> probe = testKit.createProbe();
actor.tell(new Request("data", probe.ref()));

// Wait for and assert on message
Response response = probe.expectMessage(Duration.ofSeconds(1));
assertEquals("processed", response.value());

// Expect multiple messages
List<Response> responses = probe.expectMessages(3, Duration.ofSeconds(2));

// Expect no messages
probe.expectNoMessage(Duration.ofMillis(500));
```

**Key Methods:**
- `ref()` - Get Pid to send to actors
- `expectMessage(timeout)` - Wait for single message
- `expectMessages(count, timeout)` - Wait for multiple messages
- `expectNoMessage(timeout)` - Assert no messages received

### 3. TestPid
Enhanced Pid with test capabilities and inspection tools.

```java
TestPid<Message> actor = testKit.spawn(MyHandler.class);

// Send messages
actor.tell(new Message());

// Send and wait for processing
actor.tellAndWait(new Message(), Duration.ofSeconds(1));

// Get inspectors
StateInspector<Integer> state = actor.stateInspector();
MailboxInspector mailbox = actor.mailboxInspector();

// Access underlying Pid
Pid pid = actor.pid();
```

**Key Methods:**
- `tell(message)` - Send message (fire-and-forget)
- `tellAndWait(message, timeout)` - Send and wait for processing
- `stateInspector()` - Get state inspector (stateful actors only)
- `mailboxInspector()` - Get mailbox inspector
- `pid()` - Get underlying Pid

## Inspection Tools

### 4. StateInspector
Direct state inspection for stateful actors - no query messages needed!

```java
TestPid<Object> counter = testKit.spawnStateful(CounterHandler.class, 0);
StateInspector<Integer> inspector = counter.stateInspector();

// Get current state
int current = inspector.current();

// Get as Optional
Optional<Integer> opt = inspector.currentOptional();

// Check equality
boolean matches = inspector.stateEquals(42);

// Track processing
long sequence = inspector.lastProcessedSequence();
```

**Key Methods:**
- `current()` - Get current state
- `currentOptional()` - Get state as Optional
- `stateEquals(expected)` - Check state equality
- `lastProcessedSequence()` - Get last processed sequence number

**Example:**
```java
@Test
void shouldInspectState() {
    try (TestKit testKit = TestKit.create()) {
        TestPid<Object> calc = testKit.spawnStateful(CalculatorHandler.class, 0);
        StateInspector<Integer> inspector = calc.stateInspector();
        
        calc.tell(new Add(10));
        calc.tell(new Add(5));
        
        // Wait for exact state - no Thread.sleep()!
        AsyncAssertion.awaitValue(inspector::current, 15, Duration.ofSeconds(2));
    }
}
```

### 5. MailboxInspector
Monitor mailbox state, capacity, and processing rates.

```java
TestPid<String> actor = testKit.spawn(SlowHandler.class);
MailboxInspector inspector = actor.mailboxInspector();

// Check mailbox state
int size = inspector.size();
int capacity = inspector.capacity();
double fillRatio = inspector.fillRatio();
boolean empty = inspector.isEmpty();
boolean full = inspector.isFull();

// Wait for conditions
inspector.awaitEmpty(Duration.ofSeconds(2));
inspector.awaitSizeBelow(10, Duration.ofSeconds(2));

// Check thresholds
boolean overloaded = inspector.exceedsThreshold(0.8);

// Get metrics
double rate = inspector.processingRate();
var metrics = inspector.metrics();
```

**Key Methods:**
- `size()` - Current mailbox size
- `capacity()` - Mailbox capacity
- `fillRatio()` - Fill ratio (0.0 to 1.0)
- `isEmpty()` / `isFull()` - State checks
- `awaitEmpty(timeout)` - Wait for empty mailbox
- `awaitSizeBelow(threshold, timeout)` - Wait for size drop
- `exceedsThreshold(threshold)` - Check if over threshold
- `processingRate()` - Messages per second
- `metrics()` - Get snapshot of all metrics

### 6. AsyncAssertion
Replace `Thread.sleep()` with proper async assertions.

```java
// Wait for condition
AsyncAssertion.eventually(
    () -> inspector.current() > 50,
    Duration.ofSeconds(2)
);

// Wait for specific value
int result = AsyncAssertion.awaitValue(
    inspector::current,
    42,
    Duration.ofSeconds(2)
);

// Wait for assertion to pass
AsyncAssertion.eventuallyAssert(
    () -> assertEquals(42, inspector.current()),
    Duration.ofSeconds(2)
);

// Custom poll interval
AsyncAssertion.eventually(
    () -> condition(),
    Duration.ofSeconds(2),
    50  // Poll every 50ms
);
```

**Key Methods:**
- `eventually(condition, timeout)` - Wait for boolean condition
- `awaitValue(supplier, expected, timeout)` - Wait for specific value
- `eventuallyAssert(assertion, timeout)` - Wait for assertion to pass
- All methods support custom poll intervals

## Advanced Tools

### 7. MessageCapture
Capture and inspect all messages sent to an actor.

```java
// Create capture
MessageCapture<String> capture = MessageCapture.create();
TestPid<String> actor = testKit.spawn(capture.handler());

actor.tell("msg1");
actor.tell("msg2");
actor.tell("msg3");

// Wait for messages
AsyncAssertion.awaitValue(capture::size, 3, Duration.ofSeconds(1));

// Access messages
assertEquals(3, capture.size());
assertEquals("msg1", capture.first());
assertEquals("msg3", capture.last());
String msg = capture.get(1);

// Filter messages
List<String> filtered = capture.filter(msg -> msg.startsWith("msg"));

// Search
boolean contains = capture.contains(msg -> msg.equals("msg2"));
long count = capture.count(msg -> msg.startsWith("msg"));

// Wait for specific message
String found = capture.awaitMessage(
    msg -> msg.equals("msg2"),
    Duration.ofSeconds(1)
);

// Get snapshot
var snapshot = capture.snapshot();

// Clear
capture.clear();
```

**Capture with Delegation:**
```java
// Capture AND process messages
Handler<String> processor = (msg, ctx) -> {
    // Process message
};

MessageCapture<String> capture = MessageCapture.create(processor);
TestPid<String> actor = testKit.spawn(capture.handler());

// Messages are both captured and processed
```

**Key Methods:**
- `create()` / `create(delegateHandler)` - Create capture
- `handler()` - Get capturing handler
- `size()` / `isEmpty()` - Check state
- `get(index)` / `first()` / `last()` - Access messages
- `all()` - Get all messages
- `filter(predicate)` - Filter messages
- `contains(predicate)` - Check for message
- `count(predicate)` - Count matching messages
- `awaitCount(count, timeout)` - Wait for count
- `awaitMessage(predicate, timeout)` - Wait for message
- `snapshot()` - Get immutable snapshot
- `clear()` - Clear messages

### 8. AskTestHelper
Simplified ask pattern for request-response testing.

```java
// Simple ask
Response response = AskTestHelper.ask(
    actor,
    new Request(),
    Duration.ofSeconds(2)
);

// Type-safe ask
Response typed = AskTestHelper.ask(
    actor,
    new Request(),
    Response.class,
    Duration.ofSeconds(2)
);

// Ask and assert
AskTestHelper.askAndAssert(
    actor,
    new Request(),
    (Response r) -> assertEquals(42, r.value()),
    Duration.ofSeconds(2)
);

// Ask and expect exact value
AskTestHelper.askAndExpect(
    actor,
    new Request(),
    new Response(42),
    Duration.ofSeconds(2)
);

// Try ask (returns null on failure)
Response result = AskTestHelper.tryAsk(
    actor,
    new Request(),
    Duration.ofSeconds(2)
);
```

**Key Methods:**
- `ask(actor, message, timeout)` - Simple ask
- `ask(actor, message, responseType, timeout)` - Type-safe ask
- `askAndAssert(actor, message, assertion, timeout)` - Ask and assert
- `askAndExpect(actor, message, expected, timeout)` - Ask and expect value
- `tryAsk(actor, message, timeout)` - Ask without throwing

### 9. PerformanceAssertion
Performance testing utilities for timing and throughput assertions.

```java
// Assert operation completes within time limit
String result = PerformanceAssertion.assertWithinTime(
    () -> expensiveOperation(),
    Duration.ofMillis(100)
);

// Assert throughput meets minimum requirement
PerformanceAssertion.assertThroughput(
    () -> actor.tell(new Message()),
    1000,  // iterations
    10000  // min ops/sec
);

// Measure execution time
Duration duration = PerformanceAssertion.measureTime(
    () -> operation()
);

// Measure throughput
double opsPerSec = PerformanceAssertion.measureThroughput(
    () -> operation(),
    1000  // iterations
);

// Comprehensive measurement
PerformanceResult result = PerformanceAssertion.measure(
    () -> operation(),
    1000
);
System.out.println(result); // duration, iterations, ops/sec
```

**Key Methods:**
- `assertWithinTime(operation, maxTime)` - Assert timing
- `assertThroughput(operation, iterations, minOpsPerSecond)` - Assert throughput
- `measureTime(operation)` - Measure execution time
- `measureThroughput(operation, iterations)` - Measure throughput
- `measure(operation, iterations)` - Full performance measurement

### 10. MessageMatcher
Sophisticated message pattern matching for complex assertions.

```java
// Match by type
Predicate<Object> matcher = MessageMatcher.instanceOf(Response.class);

// Combine multiple conditions (all must match)
Predicate<Response> matcher = MessageMatcher.allOf(
    MessageMatcher.instanceOf(Response.class),
    response -> response.value() > 0,
    response -> response.status().equals("OK")
);

// Match any condition
Predicate<Object> matcher = MessageMatcher.anyOf(
    MessageMatcher.instanceOf(ErrorResponse.class),
    MessageMatcher.instanceOf(TimeoutResponse.class)
);

// Negate a matcher
Predicate<String> matcher = MessageMatcher.not(
    MessageMatcher.contains("error")
);

// String matchers
Predicate<String> startsWithHello = MessageMatcher.startsWith("hello");
Predicate<String> containsWorld = MessageMatcher.contains("world");
Predicate<String> endsWithBang = MessageMatcher.endsWith("!");
Predicate<String> matchesPattern = MessageMatcher.matches("\\d{3}-\\d{4}");

// Use with MessageCapture
MessageCapture<Object> capture = MessageCapture.create();
List<Response> responses = capture.filter(
    MessageMatcher.allOf(
        MessageMatcher.instanceOf(Response.class),
        msg -> ((Response) msg).value() > 100
    )
);
```

**Key Methods:**
- `instanceOf(type)` - Match by type
- `allOf(predicates...)` - All must match
- `anyOf(predicates...)` - Any must match
- `not(predicate)` - Negate matcher
- `equalTo(value)` / `notEqualTo(value)` - Equality matching
- `isNull()` / `isNotNull()` - Null checks
- `contains(substring)` / `startsWith(prefix)` / `endsWith(suffix)` - String matching
- `matches(regex)` - Regex matching
- `any()` / `none()` - Always/never match

## Complete Example

Here's a comprehensive example showing multiple features:

```java
@Test
void comprehensiveExample() {
    try (TestKit testKit = TestKit.create()) {
        // Spawn stateful actor
        TestPid<Object> counter = testKit.spawnStateful(CounterHandler.class, 0);
        
        // Create message capture
        MessageCapture<Object> capture = MessageCapture.create();
        TestPid<Object> capturedActor = testKit.spawn(capture.handler());
        
        // Create probe
        TestProbe<CountResponse> probe = testKit.createProbe();
        
        // Get inspectors
        StateInspector<Integer> state = counter.stateInspector();
        MailboxInspector mailbox = counter.mailboxInspector();
        
        // Send messages
        counter.tell(new Increment(10));
        counter.tell(new Increment(5));
        
        // Wait for state change (no Thread.sleep!)
        AsyncAssertion.awaitValue(state::current, 15, Duration.ofSeconds(2));
        
        // Verify mailbox processed messages
        mailbox.awaitEmpty(Duration.ofSeconds(1));
        
        // Query state
        counter.tell(new GetCount(probe.ref()));
        CountResponse response = probe.expectMessage(Duration.ofSeconds(1));
        assertEquals(15, response.count());
        
        // Verify captured messages
        capturedActor.tell("test");
        AsyncAssertion.awaitValue(capture::size, 1, Duration.ofSeconds(1));
        assertEquals("test", capture.first());
        
        // Use ask pattern
        ValueResponse askResponse = AskTestHelper.ask(
            counter,
            new GetValue(),
            Duration.ofSeconds(2)
        );
        assertNotNull(askResponse);
    }
}
```

## Implementation Status

### ✅ Phase 1 - Core Components (Complete)
- **TestKit** - Actor lifecycle management with builder pattern
- **TestProbe** - Message capture and assertions
- **TestPid** - Enhanced actor references

### ✅ Phase 2 - Inspection Tools (Complete)
- **StateInspector** - Direct state access for stateful actors
- **MailboxInspector** - Mailbox monitoring and metrics
- **AsyncAssertion** - Async testing with enhanced error messages and null safety

### ✅ Phase 3 - Advanced Tools (Complete)
- **MessageCapture** - Message sequence inspection
- **AskTestHelper** - Simplified ask pattern
- **PerformanceAssertion** - Performance testing utilities
- **MessageMatcher** - Sophisticated pattern matching

## Test Coverage

**66 tests across 9 test files - all passing ✅**

- TestKitTest.java (5 tests)
- TestKitExampleTest.java (7 tests)
- StateInspectorTest.java (8 tests)
- MailboxInspectorTest.java (10 tests)
- AsyncAssertionTest.java (9 tests)
- MessageCaptureTest.java (14 tests)
- AskTestHelperTest.java (9 tests)
- Plus 4 additional integration tests

## Common Testing Patterns

### Testing Message Sequences

```java
@Test
void shouldProcessMessageSequence() {
    try (TestKit testKit = TestKit.create()) {
        MessageCapture<String> capture = MessageCapture.create();
        TestPid<String> actor = testKit.spawn(capture.handler());
        
        // Send sequence
        actor.tell("start");
        actor.tell("process");
        actor.tell("end");
        
        // Verify exact sequence
        AsyncAssertion.awaitValue(capture::size, 3, Duration.ofSeconds(1));
        assertEquals(List.of("start", "process", "end"), capture.all());
    }
}
```

### Testing with Pattern Matching

```java
@Test
void shouldFilterMessagesByType() {
    try (TestKit testKit = TestKit.create()) {
        MessageCapture<Object> capture = MessageCapture.create();
        TestPid<Object> actor = testKit.spawn(capture.handler());
        
        actor.tell(new SuccessResponse(42));
        actor.tell(new ErrorResponse("failed"));
        actor.tell(new SuccessResponse(100));
        
        // Filter using MessageMatcher
        List<Object> successes = capture.filter(
            MessageMatcher.instanceOf(SuccessResponse.class)
        );
        
        assertEquals(2, successes.size());
    }
}
```

### Testing Performance Requirements

```java
@Test
void shouldMeetPerformanceRequirements() {
    try (TestKit testKit = TestKit.create()) {
        TestPid<Message> actor = testKit.spawn(FastHandler.class);
        
        // Assert operation completes quickly
        PerformanceAssertion.assertWithinTime(
            () -> {
                actor.tell(new Message());
                actor.mailboxInspector().awaitEmpty(Duration.ofSeconds(1));
            },
            Duration.ofMillis(100)
        );
        
        // Assert throughput
        PerformanceAssertion.assertThroughput(
            () -> actor.tell(new Message()),
            10000,  // 10k messages
            50000   // min 50k ops/sec
        );
    }
}
```

### Testing Error Conditions

```java
@Test
void shouldHandleErrors() {
    try (TestKit testKit = TestKit.create()) {
        TestPid<String> actor = testKit.spawn(ErrorProneHandler.class);
        MailboxInspector mailbox = actor.mailboxInspector();
        
        actor.tell("cause-error");
        
        // Verify actor recovers and continues processing
        actor.tell("normal-message");
        
        AsyncAssertion.eventually(
            () -> mailbox.isEmpty(),
            Duration.ofSeconds(2)
        );
    }
}
```

### Testing with Custom Timeouts

```java
@Test
void shouldUseCustomTimeouts() {
    // Configure TestKit with longer timeout for slow operations
    try (TestKit testKit = TestKit.builder()
            .withTimeout(Duration.ofSeconds(30))
            .build()) {
        
        TestPid<Object> slowActor = testKit.spawnStateful(SlowHandler.class, 0);
        StateInspector<Integer> state = slowActor.stateInspector();
        
        slowActor.tell(new SlowOperation());
        
        // Uses default timeout from TestKit
        AsyncAssertion.awaitValue(
            state::current,
            42,
            testKit.getDefaultTimeout()
        );
    }
}
```

## Best Practices

### 1. Always use AsyncAssertion instead of Thread.sleep()

❌ **Bad:**
```java
actor.tell(new Message());
Thread.sleep(500);  // Arbitrary delay
assertEquals(expected, state.current());
```

✅ **Good:**
```java
actor.tell(new Message());
AsyncAssertion.awaitValue(state::current, expected, Duration.ofSeconds(2));
```

### 2. Use StateInspector for stateful actors

❌ **Bad:**
```java
// Create query message just to check state
actor.tell(new GetState(probe.ref()));
State state = probe.expectMessage(Duration.ofSeconds(1));
```

✅ **Good:**
```java
// Direct state inspection
State state = actor.stateInspector().current();
```

### 3. Use MessageCapture for sequence verification

❌ **Bad:**
```java
// Manual tracking
List<String> received = new ArrayList<>();
actor.tell(msg -> received.add(msg));
```

✅ **Good:**
```java
MessageCapture<String> capture = MessageCapture.create();
TestPid<String> actor = testKit.spawn(capture.handler());
// Automatic capture with filtering, searching, etc.
```

### 4. Use try-with-resources for TestKit

✅ **Always:**
```java
try (TestKit testKit = TestKit.create()) {
    // Test code
} // Automatic cleanup
```

## Roadmap

### Completed ✅
- All core components
- All inspection tools
- All advanced tools
- Comprehensive test coverage
- Documentation

### Future Enhancements (Optional)
- ActorHierarchyInspector - Inspect parent/child relationships
- TimeTravelTesting - Control virtual time for scheduling tests
- Fluent assertion builders
- Performance benchmarking utilities

## Contributing

This module is part of the Cajun actor system. See the main [README](../README.md) for contribution guidelines.

## License

Part of the Cajun project - see main project for license information.
