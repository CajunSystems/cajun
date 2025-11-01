# Cajun Test Utilities - API Design

## Pain Points Identified from Existing Tests

### 1. **Manual Thread.sleep() Everywhere**
```java
// Current approach - brittle and slow
Thread.sleep(100);  // Hope actor initialized
Thread.sleep(200);  // Hope message processed
```
**Problem**: Tests are slow, flaky, and timing-dependent

### 2. **CountDownLatch Boilerplate**
```java
// Repeated in every test
CountDownLatch latch = new CountDownLatch(1);
SomeHandler.setLatch(latch);  // Static field anti-pattern
assertTrue(latch.await(2, TimeUnit.SECONDS));
```
**Problem**: Verbose, requires static fields, error-prone

### 3. **No Way to Verify State**
```java
// Current: polling with retries
int maxRetries = 10;
while (retryCount < maxRetries) {
    Thread.sleep(200);
    stateValue = actor.getCountSync();
    if (stateValue == 5) break;
}
```
**Problem**: Ugly polling loops, hard to debug failures

### 4. **Reflection Hacks for Testing**
```java
// Current: breaking encapsulation
Field mailboxField = Actor.class.getDeclaredField("mailbox");
mailboxField.setAccessible(true);
Object mailbox = mailboxField.get(actor);
```
**Problem**: Brittle, breaks with refactoring, violates encapsulation

### 5. **Complex Ask Pattern Testing**
```java
// Current: nested try-catch for exception handling
try {
    future.get(4, TimeUnit.SECONDS);
} catch (ExecutionException ex) {
    if (ex.getCause() instanceof TimeoutException) {
        throw (TimeoutException) ex.getCause();
    }
}
```
**Problem**: Verbose, hard to read, easy to get wrong

### 6. **No Message Capture**
- Can't verify what messages were sent
- Can't assert on message order
- Can't inspect message contents

---

## Proposed Test Utilities API

### 1. **TestKit** - Main Entry Point

```java
public class TestKit implements AutoCloseable {
    private final ActorSystem system;
    
    public static TestKit create() {
        return new TestKit(new ActorSystem());
    }
    
    public static TestKit create(ActorSystem system) {
        return new TestKit(system);
    }
    
    // Spawn actors with test instrumentation
    public <T> TestPid<T> spawn(Class<? extends Handler<T>> handlerClass);
    public <T> TestPid<T> spawn(Handler<T> handler);
    public <S, T> TestPid<T> spawnStateful(
        Class<? extends StatefulHandler<S, T>> handlerClass, S initialState);
    
    // Create test probes
    public <T> TestProbe<T> createProbe();
    public <T> TestProbe<T> createProbe(String name);
    
    // Shutdown
    @Override
    public void close() {
        system.shutdown();
    }
}
```

**Usage:**
```java
@Test
void myTest() {
    try (TestKit testKit = TestKit.create()) {
        TestPid<String> actor = testKit.spawn(MyHandler.class);
        TestProbe<String> probe = testKit.createProbe();
        
        actor.tell("hello");
        probe.expectMessage("response");
    }
}
```

---

### 2. **TestProbe** - Message Capture and Assertions

```java
public class TestProbe<T> {
    private final Pid pid;
    private final BlockingQueue<T> receivedMessages;
    
    // Get the Pid to use as replyTo
    public Pid ref() { return pid; }
    
    // Expect specific message
    public T expectMessage(Duration timeout);
    public T expectMessage(Class<? extends T> messageType, Duration timeout);
    public T expectMessage(Predicate<T> predicate, Duration timeout);
    
    // Expect no message
    public void expectNoMessage(Duration duration);
    
    // Expect multiple messages
    public List<T> expectMessages(int count, Duration timeout);
    
    // Fluent assertions
    public MessageAssertion<T> expectMessageThat();
    
    // Receive without timeout (for manual control)
    public Optional<T> receiveMessage(Duration timeout);
    
    // Get all received messages
    public List<T> receivedMessages();
}
```

**Usage:**
```java
@Test
void testWithProbe() {
    try (TestKit testKit = TestKit.create()) {
        TestProbe<Response> probe = testKit.createProbe();
        TestPid<Request> actor = testKit.spawn(MyHandler.class);
        
        // Send request with probe as replyTo
        actor.tell(new Request("data", probe.ref()));
        
        // Assert on response
        Response response = probe.expectMessage(Duration.ofSeconds(1));
        assertEquals("processed", response.value());
        
        // Or fluent style
        probe.expectMessageThat()
            .isInstanceOf(SuccessResponse.class)
            .matches(r -> r.value().equals("expected"));
    }
}
```

---

### 3. **TestPid** - Enhanced Pid with Test Capabilities

```java
public class TestPid<T> {
    private final Pid pid;
    private final Actor<?> underlyingActor;  // For inspection
    
    // Standard operations
    public void tell(T message);
    public Pid pid();
    
    // Test-specific operations
    public void tellAndWait(T message, Duration timeout);
    
    // State inspection (for stateful actors)
    public <S> StateInspector<S> stateInspector();
    
    // Mailbox inspection
    public MailboxInspector mailboxInspector();
    
    // Stop and wait
    public void stopAndWait(Duration timeout);
}
```

**Usage:**
```java
@Test
void testActorState() {
    try (TestKit testKit = TestKit.create()) {
        TestPid<CounterMessage> actor = 
            testKit.spawnStateful(CounterHandler.class, 0);
        
        // Send and wait for processing
        actor.tellAndWait(new Increment(5), Duration.ofSeconds(1));
        
        // Inspect state
        StateInspector<Integer> state = actor.stateInspector();
        state.awaitState(s -> s == 5, Duration.ofSeconds(1));
        assertEquals(5, state.current());
    }
}
```

---

### 4. **StateInspector** - State Verification

```java
public class StateInspector<S> {
    // Get current state (blocking)
    public S current();
    public S current(Duration timeout);
    
    // Wait for specific state
    public void awaitState(Predicate<S> predicate, Duration timeout);
    public void awaitState(S expectedState, Duration timeout);
    
    // Fluent assertions
    public StateAssertion<S> assertThat();
}
```

**Usage:**
```java
@Test
void testStateChanges() {
    StateInspector<Integer> state = actor.stateInspector();
    
    actor.tell(new Increment(3));
    state.awaitState(s -> s == 3, Duration.ofSeconds(1));
    
    actor.tell(new Increment(2));
    state.assertThat()
        .eventuallyEquals(5, Duration.ofSeconds(1))
        .satisfies(s -> s > 0);
}
```

---

### 5. **MailboxInspector** - Mailbox Verification

```java
public class MailboxInspector {
    // Get mailbox metrics
    public int size();
    public int remainingCapacity();
    public boolean isEmpty();
    
    // Wait for conditions
    public void awaitEmpty(Duration timeout);
    public void awaitSize(int expectedSize, Duration timeout);
    
    // Backpressure state (if enabled)
    public Optional<BackpressureState> backpressureState();
}
```

**Usage:**
```java
@Test
void testMailboxProcessing() {
    MailboxInspector mailbox = actor.mailboxInspector();
    
    // Send multiple messages
    for (int i = 0; i < 10; i++) {
        actor.tell(new Message(i));
    }
    
    // Wait for all to be processed
    mailbox.awaitEmpty(Duration.ofSeconds(2));
    assertEquals(0, mailbox.size());
}
```

---

### 6. **MessageCapture** - Intercept Messages

```java
public class MessageCapture<T> {
    public static <T> MessageCapture<T> create(TestKit testKit);
    
    // Capture messages to a specific actor
    public void captureMessagesTo(Pid target);
    
    // Get captured messages
    public List<T> messages();
    public List<T> messages(Predicate<T> filter);
    
    // Assertions
    public void assertMessageCount(int expected);
    public void assertMessageOrder(Class<?>... messageTypes);
    public void assertContainsMessage(Predicate<T> predicate);
}
```

**Usage:**
```java
@Test
void testMessageFlow() {
    try (TestKit testKit = TestKit.create()) {
        MessageCapture<Object> capture = MessageCapture.create(testKit);
        TestPid<String> actor = testKit.spawn(MyHandler.class);
        
        capture.captureMessagesTo(actor.pid());
        
        actor.tell("msg1");
        actor.tell("msg2");
        actor.tell("msg3");
        
        capture.assertMessageCount(3);
        capture.assertMessageOrder(String.class, String.class, String.class);
    }
}
```

---

### 7. **AskTestHelper** - Simplified Ask Testing

```java
public class AskTestHelper {
    public static <T> T askAndExpect(
        ActorSystem system, Pid target, Object message, 
        Class<T> responseType, Duration timeout);
    
    public static void askAndExpectTimeout(
        ActorSystem system, Pid target, Object message, Duration timeout);
    
    public static <T> void askAndExpectError(
        ActorSystem system, Pid target, Object message,
        Class<? extends Throwable> errorType, Duration timeout);
}
```

**Usage:**
```java
@Test
void testAskPattern() {
    try (TestKit testKit = TestKit.create()) {
        TestPid<String> actor = testKit.spawn(PongHandler.class);
        
        // Simple success case
        String response = AskTestHelper.askAndExpect(
            testKit.system(), actor.pid(), "ping", 
            String.class, Duration.ofSeconds(1));
        assertEquals("pong", response);
        
        // Timeout case
        AskTestHelper.askAndExpectTimeout(
            testKit.system(), actor.pid(), "no-reply",
            Duration.ofMillis(100));
        
        // Error case
        AskTestHelper.askAndExpectError(
            testKit.system(), actor.pid(), "error",
            IllegalStateException.class, Duration.ofSeconds(1));
    }
}
```

---

### 8. **AsyncAssertion** - Async Assertions

```java
public class AsyncAssertion {
    public static <T> void eventually(
        Supplier<T> supplier, 
        Predicate<T> condition,
        Duration timeout);
    
    public static void eventually(
        Runnable assertion,
        Duration timeout);
}
```

**Usage:**
```java
@Test
void testEventualConsistency() {
    actor.tell(new UpdateCommand());
    
    // Wait for condition to become true
    AsyncAssertion.eventually(
        () -> actor.stateInspector().current(),
        state -> state.isReady(),
        Duration.ofSeconds(2)
    );
    
    // Or with standard assertions
    AsyncAssertion.eventually(
        () -> assertEquals(5, actor.stateInspector().current()),
        Duration.ofSeconds(1)
    );
}
```

---

## Example: Before and After

### Before (Current)
```java
@Test
void testCounterIncrement() throws InterruptedException {
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
        
        Thread.sleep(100);  // Wait for actor to start
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
void testCounterIncrement() {
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

---

## Implementation Priority

1. **Phase 1 - Core (Week 1)**
   - TestKit
   - TestProbe
   - TestPid
   - Basic assertions

2. **Phase 2 - Inspection (Week 2)**
   - StateInspector
   - MailboxInspector
   - AsyncAssertion

3. **Phase 3 - Advanced (Week 3)**
   - MessageCapture
   - AskTestHelper
   - Fluent assertion builders

---

## Module Structure

```
test-utils/
├── build.gradle
├── src/
│   ├── main/java/com/cajunsystems/test/
│   │   ├── TestKit.java
│   │   ├── TestProbe.java
│   │   ├── TestPid.java
│   │   ├── StateInspector.java
│   │   ├── MailboxInspector.java
│   │   ├── MessageCapture.java
│   │   ├── AskTestHelper.java
│   │   ├── AsyncAssertion.java
│   │   └── assertions/
│   │       ├── MessageAssertion.java
│   │       └── StateAssertion.java
│   └── test/java/com/cajunsystems/test/
│       └── [test utilities tests]
└── README.md
```

---

## Dependencies

```gradle
dependencies {
    api project(':lib')  // Cajun core
    api 'org.junit.jupiter:junit-jupiter-api:5.10.0'
    implementation 'org.awaitility:awaitility:4.2.0'  // For eventually assertions
    testImplementation 'org.junit.jupiter:junit-jupiter-engine:5.10.0'
}
```
