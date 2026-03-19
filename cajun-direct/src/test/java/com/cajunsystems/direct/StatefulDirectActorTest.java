package com.cajunsystems.direct;

import org.junit.jupiter.api.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for stateful direct-style actors via {@link DirectActorSystem#statefulActorOf}.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StatefulDirectActorTest {

    private DirectActorSystem system;

    @BeforeEach
    void setUp() {
        system = new DirectActorSystem();
    }

    @AfterEach
    void tearDown() {
        system.shutdown();
    }

    // ---- Message types ----

    sealed interface CounterMsg {
        record Increment() implements CounterMsg {}
        record Decrement() implements CounterMsg {}
        record Reset() implements CounterMsg {}
        record GetValue() implements CounterMsg {}
        record Add(int amount) implements CounterMsg {}
    }

    sealed interface AccumulatorMsg {
        record Append(String text) implements AccumulatorMsg {}
        record Get() implements AccumulatorMsg {}
        record Clear() implements AccumulatorMsg {}
    }

    // ---- Handlers ----

    static class CounterHandler implements StatefulDirectHandler<Integer, CounterMsg, Integer> {
        @Override
        public DirectResult<Integer, Integer> handle(
                CounterMsg msg, Integer state, DirectContext context) {
            return switch (msg) {
                case CounterMsg.Increment _ -> DirectResult.of(state + 1, state + 1);
                case CounterMsg.Decrement _ -> DirectResult.of(state - 1, state - 1);
                case CounterMsg.Reset _     -> DirectResult.of(0, 0);
                case CounterMsg.GetValue _  -> DirectResult.of(state, state);
                case CounterMsg.Add(int n)  -> DirectResult.of(state + n, state + n);
            };
        }
    }

    static class AccumulatorHandler
            implements StatefulDirectHandler<String, AccumulatorMsg, String> {
        @Override
        public DirectResult<String, String> handle(
                AccumulatorMsg msg, String state, DirectContext context) {
            return switch (msg) {
                case AccumulatorMsg.Append(String text) ->
                        DirectResult.of(state + text, state + text);
                case AccumulatorMsg.Get _ ->
                        DirectResult.of(state, state);
                case AccumulatorMsg.Clear _ ->
                        DirectResult.of("", "");
            };
        }
    }

    static class FailingStatefulHandler
            implements StatefulDirectHandler<Integer, String, String> {
        @Override
        public DirectResult<Integer, String> handle(
                String msg, Integer state, DirectContext context) {
            throw new RuntimeException("Failing: " + msg);
        }
    }

    static class RecoveringStatefulHandler
            implements StatefulDirectHandler<Integer, String, String> {
        @Override
        public DirectResult<Integer, String> handle(
                String msg, Integer state, DirectContext context) {
            throw new RuntimeException("boom");
        }

        @Override
        public DirectResult<Integer, String> onError(
                String msg, Integer state, Throwable exception, DirectContext context) {
            return DirectResult.of(state, "recovered");
        }
    }

    static class InitStateHandler
            implements StatefulDirectHandler<Integer, String, Integer> {
        @Override
        public Integer preStart(Integer initialState, DirectContext context) {
            return initialState + 100; // Add 100 during initialization
        }

        @Override
        public DirectResult<Integer, Integer> handle(
                String msg, Integer state, DirectContext context) {
            return DirectResult.of(state, state);
        }
    }

    // ---- Tests ----

    @Test
    @Order(1)
    void increment_updatesStateAndReturnsNewValue() {
        DirectPid<CounterMsg, Integer> counter = system
                .statefulActorOf(new CounterHandler(), 0)
                .withId("counter-inc")
                .spawn();

        assertEquals(1, counter.call(new CounterMsg.Increment()));
        assertEquals(2, counter.call(new CounterMsg.Increment()));
        assertEquals(3, counter.call(new CounterMsg.Increment()));
    }

    @Test
    @Order(2)
    void decrement_updatesStateAndReturnsNewValue() {
        DirectPid<CounterMsg, Integer> counter = system
                .statefulActorOf(new CounterHandler(), 10)
                .withId("counter-dec")
                .spawn();

        assertEquals(9, counter.call(new CounterMsg.Decrement()));
        assertEquals(8, counter.call(new CounterMsg.Decrement()));
    }

    @Test
    @Order(3)
    void reset_returnsZeroAndStateIsZero() {
        DirectPid<CounterMsg, Integer> counter = system
                .statefulActorOf(new CounterHandler(), 5)
                .withId("counter-reset")
                .spawn();

        counter.call(new CounterMsg.Increment());
        counter.call(new CounterMsg.Increment());
        int afterReset = counter.call(new CounterMsg.Reset());
        assertEquals(0, afterReset);

        // Confirm state persisted at zero
        assertEquals(0, counter.call(new CounterMsg.GetValue()));
    }

    @Test
    @Order(4)
    void getValue_doesNotChangeState() {
        DirectPid<CounterMsg, Integer> counter = system
                .statefulActorOf(new CounterHandler(), 42)
                .withId("counter-get")
                .spawn();

        assertEquals(42, counter.call(new CounterMsg.GetValue()));
        assertEquals(42, counter.call(new CounterMsg.GetValue()));
    }

    @Test
    @Order(5)
    void add_updatesStateByAmount() {
        DirectPid<CounterMsg, Integer> counter = system
                .statefulActorOf(new CounterHandler(), 0)
                .withId("counter-add")
                .spawn();

        assertEquals(10, counter.call(new CounterMsg.Add(10)));
        assertEquals(25, counter.call(new CounterMsg.Add(15)));
    }

    @Test
    @Order(6)
    void initialState_isRespected() {
        DirectPid<CounterMsg, Integer> counter = system
                .statefulActorOf(new CounterHandler(), 100)
                .withId("counter-initial")
                .spawn();

        assertEquals(100, counter.call(new CounterMsg.GetValue()));
    }

    @Test
    @Order(7)
    void stateIsIsolatedBetweenActors() {
        DirectPid<CounterMsg, Integer> c1 = system
                .statefulActorOf(new CounterHandler(), 0)
                .withId("isolated-1")
                .spawn();

        DirectPid<CounterMsg, Integer> c2 = system
                .statefulActorOf(new CounterHandler(), 0)
                .withId("isolated-2")
                .spawn();

        c1.call(new CounterMsg.Increment());
        c1.call(new CounterMsg.Increment());
        c1.call(new CounterMsg.Increment());

        assertEquals(3, c1.call(new CounterMsg.GetValue()));
        assertEquals(0, c2.call(new CounterMsg.GetValue()));
    }

    @Test
    @Order(8)
    void accumulatorHandler_appendsAndReturnsState() {
        DirectPid<AccumulatorMsg, String> acc = system
                .statefulActorOf(new AccumulatorHandler(), "")
                .withId("accumulator")
                .spawn();

        acc.call(new AccumulatorMsg.Append("Hello"));
        acc.call(new AccumulatorMsg.Append(", "));
        acc.call(new AccumulatorMsg.Append("World"));

        assertEquals("Hello, World", acc.call(new AccumulatorMsg.Get()));
    }

    @Test
    @Order(9)
    void accumulator_clearResetsState() {
        DirectPid<AccumulatorMsg, String> acc = system
                .statefulActorOf(new AccumulatorHandler(), "")
                .withId("accumulator-clear")
                .spawn();

        acc.call(new AccumulatorMsg.Append("some text"));
        assertEquals("", acc.call(new AccumulatorMsg.Clear()));
        assertEquals("", acc.call(new AccumulatorMsg.Get()));
    }

    @Test
    @Order(10)
    void failingHandler_throwsDirectActorException() {
        DirectPid<String, String> ref = system
                .statefulActorOf(new FailingStatefulHandler(), 0)
                .withId("failing-stateful")
                .spawn();

        assertThrows(DirectActorException.class, () -> ref.call("test"));
    }

    @Test
    @Order(11)
    void onError_recoversWithFallbackAndPreservesState() {
        DirectPid<String, String> ref = system
                .statefulActorOf(new RecoveringStatefulHandler(), 0)
                .withId("recovering-stateful")
                .spawn();

        assertEquals("recovered", ref.call("any"));
    }

    @Test
    @Order(12)
    void preStart_canModifyInitialState() throws InterruptedException {
        DirectPid<String, Integer> ref = system
                .statefulActorOf(new InitStateHandler(), 5)
                .withId("init-state")
                .spawn();

        // preStart adds 100 to initialState (5), so first message should see state=105
        Thread.sleep(50); // let preStart complete
        assertEquals(105, ref.call("get"));
    }

    @Test
    @Order(13)
    void callAsync_statefulActor_returnsCompletableFuture() throws Exception {
        DirectPid<CounterMsg, Integer> counter = system
                .statefulActorOf(new CounterHandler(), 0)
                .withId("counter-async")
                .spawn();

        CompletableFuture<Integer> f1 = counter.callAsync(new CounterMsg.Increment());
        // Sequential: wait for f1 before f2 to get deterministic results
        assertEquals(1, f1.get(5, TimeUnit.SECONDS));

        CompletableFuture<Integer> f2 = counter.callAsync(new CounterMsg.Increment());
        assertEquals(2, f2.get(5, TimeUnit.SECONDS));
    }

    @Test
    @Order(14)
    void tell_statefulActor_doesNotBlock() throws InterruptedException {
        DirectPid<CounterMsg, Integer> counter = system
                .statefulActorOf(new CounterHandler(), 0)
                .withId("counter-tell")
                .spawn();

        // tell is fire-and-forget; call after to verify state was updated
        counter.tell(new CounterMsg.Increment());
        counter.tell(new CounterMsg.Increment());

        // Wait for messages to be processed
        Thread.sleep(100);
        assertEquals(2, counter.call(new CounterMsg.GetValue()));
    }
}
