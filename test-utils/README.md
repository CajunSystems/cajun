# Cajun Test Utilities

Test utilities for writing clean, maintainable tests for Cajun actor systems.

## Overview

The Cajun test utilities eliminate common pain points in actor testing:

- ❌ **No more `Thread.sleep()`** - Deterministic waiting with timeouts
- ❌ **No more `CountDownLatch` boilerplate** - Built-in synchronization
- ❌ **No more polling loops** - Direct state inspection
- ❌ **No more reflection hacks** - Proper test instrumentation
- ✅ **Clean, readable tests** - Fluent API design
- ✅ **Fast test execution** - Minimal waiting, maximum efficiency

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
        TestPid<Increment> counter = 
            testKit.spawnStateful(CounterHandler.class, 0);
        
        counter.tellAndWait(new Increment(5), Duration.ofSeconds(1));
        
        counter.stateInspector()
            .assertThat()
            .isEqualTo(5);
    }
}
```

## Core Components

### 1. TestKit
Main entry point for creating test actors and probes.

```java
try (TestKit testKit = TestKit.create()) {
    TestPid<String> actor = testKit.spawn(MyHandler.class);
    TestProbe<String> probe = testKit.createProbe();
    // ... test code ...
}
```

### 2. TestProbe
Capture and assert on messages.

```java
TestProbe<Response> probe = testKit.createProbe();
actor.tell(new Request("data", probe.ref()));

Response response = probe.expectMessage(Duration.ofSeconds(1));
assertEquals("processed", response.value());
```

### 3. TestPid
Enhanced Pid with test capabilities.

```java
TestPid<Message> actor = testKit.spawn(MyHandler.class);

// Send and wait for processing
actor.tellAndWait(new Message(), Duration.ofSeconds(1));

// Inspect state
StateInspector<Integer> state = actor.stateInspector();
assertEquals(5, state.current());
```

### 4. StateInspector
Verify stateful actor state changes.

```java
StateInspector<Integer> state = actor.stateInspector();

actor.tell(new Increment(3));
state.awaitState(s -> s == 3, Duration.ofSeconds(1));
```

### 5. MailboxInspector
Monitor mailbox state and backpressure.

```java
MailboxInspector mailbox = actor.mailboxInspector();

// Send messages
for (int i = 0; i < 10; i++) {
    actor.tell(new Message(i));
}

// Wait for processing
mailbox.awaitEmpty(Duration.ofSeconds(2));
```

## Installation

Add to your test dependencies:

```gradle
dependencies {
    testImplementation project(':test-utils')
}
```

## Design Document

See [TEST_UTILITIES_DESIGN.md](TEST_UTILITIES_DESIGN.md) for the complete API design, implementation plan, and examples.

## Status

🚧 **In Design Phase** - API design complete, implementation pending

### Implementation Roadmap

- **Phase 1 (Week 1)**: Core components (TestKit, TestProbe, TestPid)
- **Phase 2 (Week 2)**: Inspection tools (StateInspector, MailboxInspector)
- **Phase 3 (Week 3)**: Advanced features (MessageCapture, AskTestHelper)

## Contributing

This module is part of the Cajun actor system. See the main [README](../README.md) for contribution guidelines.
