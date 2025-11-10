# Testing Dispatcher Implementation

## Test Coverage

### Unit Tests

#### 1. DispatcherTest
Tests the core Dispatcher class functionality.

**Test Cases:**
- Virtual thread dispatcher creation
- Fixed thread pool dispatcher creation
- Task scheduling (single and multiple)
- Shutdown and termination
- Concurrent scheduling
- Edge cases (zero/negative threads)

**Run:**
```bash
./gradlew :lib:test --tests "com.cajunsystems.dispatcher.DispatcherTest"
```

#### 2. DispatcherMailboxTest
Tests both LinkedBlockingQueue and MPSC mailbox variants.

**Test Cases:**
- Mailbox creation (LBQ and MPSC)
- Enqueue and poll operations
- Coalesced scheduling pattern
- Overflow strategies (BLOCK and DROP)
- Power-of-2 capacity validation for MPSC
- Concurrent enqueue operations
- Scheduled flag handling

**Run:**
```bash
./gradlew :lib:test --tests "com.cajunsystems.dispatcher.DispatcherMailboxTest"
```

#### 3. ActorRunnerTest
Tests the batch message processor.

**Test Cases:**
- Single message processing
- Batch processing with throughput limits
- Exception handling
- Re-scheduling logic
- Empty mailbox handling
- Throughput configuration

**Run:**
```bash
./gradlew :lib:test --tests "com.cajunsystems.dispatcher.ActorRunnerTest"
```

### Integration Tests

#### DispatcherActorIntegrationTest
End-to-end tests with real ActorSystem and actors.

**Test Cases:**
- Dispatcher LBQ actor message flow
- Dispatcher MPSC actor message flow
- Multiple dispatcher actors
- Ping-pong between actors
- Mixed mailbox types (separate systems)
- High throughput scenarios (10k+ messages)
- Overflow strategy DROP behavior
- Actor shutdown with dispatcher

**Run:**
```bash
./gradlew :lib:test --tests "com.cajunsystems.dispatcher.DispatcherActorIntegrationTest"
```

## Running All Dispatcher Tests

```bash
# All dispatcher tests
./gradlew :lib:test --tests "com.cajunsystems.dispatcher.*"

# With logging
./gradlew :lib:test --tests "com.cajunsystems.dispatcher.*" --info

# Single test class
./gradlew :lib:test --tests "com.cajunsystems.dispatcher.DispatcherTest"

# Single test method
./gradlew :lib:test --tests "com.cajunsystems.dispatcher.DispatcherTest.testVirtualThreadDispatcherCreation"
```

## Common Issues and Solutions

### Issue 1: Tests Hanging / Not Exiting

**Symptom:** Tests pass but JVM doesn't exit

**Cause:** ActorSystem has a non-daemon keep-alive thread

**Solution:** Ensure `@AfterEach` calls `system.shutdown()`

```java
@AfterEach
void cleanup() {
    if (system != null) {
        system.shutdown();
    }
}
```

### Issue 2: IllegalAccessException

**Symptom:** `java.lang.IllegalAccessException` when creating handler instances

**Cause:** Handler classes must be public for reflection

**Solution:** Make test handler classes `public static`

```java
// Bad
static class MyHandler implements Handler<String> { ... }

// Good
public static class MyHandler implements Handler<String> { ... }
```

### Issue 3: Messages Not Processed

**Symptom:** CountDownLatch times out, messages not processed

**Possible Causes:**
1. Actor not started: Call `.spawn()` on builder
2. Dispatcher not scheduling: Verify mailbox enqueue triggers schedule
3. Test timeout too short: Increase await time

**Solution:**
```java
// Ensure actor is spawned
Pid actor = system.actorOf(Handler.class).spawn();

// Give enough time for processing
assertTrue(latch.await(5, TimeUnit.SECONDS));
```

### Issue 4: MPSC Capacity Error

**Symptom:** `IllegalArgumentException: Capacity must be power of 2`

**Cause:** MpscArrayQueue requires power-of-2 capacity

**Solution:**
```java
// Bad
config.setInitialCapacity(1000);

// Good
config.setInitialCapacity(1024); // 2^10
```

## Test Patterns

### Pattern 1: Simple Message Counter

```java
public static class CountingHandler implements Handler<String> {
    static final AtomicInteger counter = new AtomicInteger(0);
    static CountDownLatch latch;

    @Override
    public void receive(String message, ActorContext context) {
        counter.incrementAndGet();
        if (latch != null) {
            latch.countDown();
        }
    }

    static void reset(int count) {
        counter.set(0);
        latch = new CountDownLatch(count);
    }
}

// Usage in test
CountingHandler.reset(10);
Pid actor = system.actorOf(CountingHandler.class).spawn();
for (int i = 0; i < 10; i++) {
    actor.tell("message-" + i);
}
assertTrue(CountingHandler.latch.await(5, TimeUnit.SECONDS));
assertEquals(10, CountingHandler.counter.get());
```

### Pattern 2: Dispatcher Configuration

```java
MailboxConfig config = new MailboxConfig()
    .setMailboxType(MailboxType.DISPATCHER_MPSC)
    .setInitialCapacity(4096)  // Power of 2
    .setThroughput(128)
    .setOverflowStrategy(OverflowStrategy.BLOCK);

ActorSystem system = new ActorSystem(
    new ThreadPoolFactory(),
    null,
    config,
    new DefaultMailboxProvider<>()
);
```

### Pattern 3: Concurrent Producer Test

```java
int threadCount = 10;
int messagesPerThread = 100;
CountDownLatch latch = new CountDownLatch(threadCount * messagesPerThread);

for (int i = 0; i < threadCount; i++) {
    new Thread(() -> {
        for (int j = 0; j < messagesPerThread; j++) {
            actor.tell("message");
        }
    }).start();
}

assertTrue(latch.await(10, TimeUnit.SECONDS));
```

## Performance Testing

### Benchmarking Different Mailbox Types

```java
@Test
void benchmarkMailboxTypes() {
    // Test 1: Traditional BLOCKING
    MailboxConfig blocking = new MailboxConfig()
        .setMailboxType(MailboxType.BLOCKING);
    
    // Test 2: DISPATCHER_LBQ
    MailboxConfig lbq = new MailboxConfig()
        .setMailboxType(MailboxType.DISPATCHER_LBQ)
        .setThroughput(64);
    
    // Test 3: DISPATCHER_MPSC
    MailboxConfig mpsc = new MailboxConfig()
        .setMailboxType(MailboxType.DISPATCHER_MPSC)
        .setInitialCapacity(4096)
        .setThroughput(128);
    
    // Run same workload with each configuration
    // Measure: latency, throughput, memory
}
```

## Debugging Tips

### Enable Debug Logging

```java
// In test setup
System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "DEBUG");
System.setProperty("org.slf4j.simpleLogger.log.com.cajunsystems", "DEBUG");
```

### Print Mailbox State

```java
System.out.println("Mailbox size: " + mailbox.size());
System.out.println("Scheduled: " + mailbox.getScheduled().get());
System.out.println("Capacity: " + mailbox.getCapacity());
```

### Add Test Timeouts

```java
@Test
@Timeout(value = 10, unit = TimeUnit.SECONDS)
void testWithTimeout() {
    // Test that must complete in 10 seconds
}
```

## Continuous Integration

### GitHub Actions Example

```yaml
- name: Run Dispatcher Tests
  run: ./gradlew :lib:test --tests "com.cajunsystems.dispatcher.*"
  
- name: Generate Test Report
  if: always()
  uses: dorny/test-reporter@v1
  with:
    name: Dispatcher Tests
    path: lib/build/test-results/test/*.xml
    reporter: java-junit
```

## Coverage Goals

- **Unit Test Coverage**: >90% for dispatcher package
- **Integration Test Coverage**: All major use cases
- **Performance Tests**: Baseline for regression detection

## See Also

- [Dispatcher Usage Guide](./dispatcher_usage_guide.md)
- [Dispatcher Integration Proposal](./dispatcher_integration_proposal.md)
- [Performance Optimizations](../performance_optimizations.md)
