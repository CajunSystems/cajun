package com.cajunsystems;

import com.cajunsystems.handler.EffectBehavior;
import com.cajunsystems.test.TempPersistenceExtension;
import com.cajunsystems.handler.StatefulHandler;
import com.cajunsystems.roux.Effect;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import java.io.Serializable;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ActorSystem#fromEffect} and {@link EffectBehavior}.
 *
 * Note: effect-based actors are full actors — they have a mailbox, run on a virtual thread,
 * process messages one at a time, and support all the same options as class-based actors.
 */
@ExtendWith(TempPersistenceExtension.class)
class FromEffectTest {

    private ActorSystem system;

    @BeforeEach
    void setUp() {
        system = new ActorSystem();
    }

    @AfterEach
    void tearDown() {
        system.shutdown();
    }

    // -------------------------------------------------------------------------
    // Message types
    // -------------------------------------------------------------------------

    sealed interface CounterMsg extends Serializable permits FromEffectTest.Inc, FromEffectTest.Dec,
            FromEffectTest.Reset, FromEffectTest.Snapshot {}

    record Inc(int n)           implements CounterMsg {}
    record Dec(int n)           implements CounterMsg {}
    record Reset()              implements CounterMsg {}
    /** Ask the actor to publish its current state to an external latch/ref. */
    record Snapshot()           implements CounterMsg {}

    sealed interface FailMsg extends Serializable permits FromEffectTest.Good, FromEffectTest.Bad {}
    record Good() implements FailMsg {}
    record Bad()  implements FailMsg {}

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Spawns a counter whose state is observable via tap(). */
    private Pid spawnObservableCounter(int initial, String id,
            AtomicInteger stateRef, CountDownLatch snapshotLatch) throws InterruptedException {
        Pid pid = system.fromEffect(
            (CounterMsg msg, Integer count, ActorContext ctx) -> switch (msg) {
                case Inc i      -> Effect.succeed(count + i.n());
                case Dec d      -> Effect.succeed(count - d.n());
                case Reset r    -> Effect.succeed(0);
                case Snapshot s -> Effect.suspend(() -> {
                    stateRef.set(count);
                    snapshotLatch.countDown();
                    return count;
                });
            },
            initial
        ).withId(id).spawn();
        // Wait for async state initialization to complete before callers send messages.
        Thread.sleep(100);
        return pid;
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("fromEffect: basic state transitions")
    void basicStateTransitions() throws InterruptedException {
        AtomicInteger ref = new AtomicInteger(-1);
        CountDownLatch latch = new CountDownLatch(1);

        Pid counter = spawnObservableCounter(0, "counter-basic", ref, latch);

        counter.tell(new Inc(5));
        counter.tell(new Inc(3));
        counter.tell(new Dec(2));
        counter.tell(new Snapshot());

        assertTrue(latch.await(3, TimeUnit.SECONDS), "snapshot never arrived");
        assertEquals(6, ref.get());
    }

    @Test
    @DisplayName("fromEffect: initial state is respected")
    void initialStateRespected() throws InterruptedException {
        AtomicInteger ref = new AtomicInteger(-1);
        CountDownLatch latch = new CountDownLatch(1);

        Pid counter = spawnObservableCounter(100, "counter-initial", ref, latch);
        counter.tell(new Snapshot());

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertEquals(100, ref.get());
    }

    @Test
    @DisplayName("fromEffect: reset returns to zero")
    void resetBehavior() throws InterruptedException {
        AtomicInteger ref = new AtomicInteger(-1);
        CountDownLatch latch = new CountDownLatch(1);

        Pid counter = spawnObservableCounter(0, "counter-reset", ref, latch);
        counter.tell(new Inc(42));
        counter.tell(new Reset());
        counter.tell(new Snapshot());

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertEquals(0, ref.get());
    }

    @Test
    @DisplayName("fromEffect: builder returns full StatefulActorBuilder (.withId works)")
    void builderApiIsAccessible() throws InterruptedException {
        AtomicInteger ref = new AtomicInteger(-1);
        CountDownLatch latch = new CountDownLatch(1);

        // Verify explicit ID is honoured
        Pid pid = system.<RuntimeException, Integer, CounterMsg>fromEffect(
            (msg, count, ctx) -> switch (msg) {
                case Inc i      -> Effect.succeed(count + i.n());
                case Dec d      -> Effect.succeed(count - d.n());
                case Reset r    -> Effect.succeed(0);
                case Snapshot s -> Effect.suspend(() -> {
                    ref.set(count);
                    latch.countDown();
                    return count;
                });
            },
            0
        ).withId("explicit-id").spawn();

        assertEquals("explicit-id", pid.actorId());
        Thread.sleep(100); // allow async state initialization to complete

        pid.tell(new Inc(7));
        pid.tell(new Snapshot());

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertEquals(7, ref.get());
    }

    @Test
    @DisplayName("EffectBehavior.asHandler() produces a working StatefulHandler")
    void asHandlerDelegates() throws InterruptedException {
        AtomicInteger ref = new AtomicInteger(-1);
        CountDownLatch latch = new CountDownLatch(1);

        EffectBehavior<RuntimeException, Integer, CounterMsg> behavior =
            (msg, count, ctx) -> switch (msg) {
                case Inc i      -> Effect.succeed(count + i.n());
                case Dec d      -> Effect.succeed(count - d.n());
                case Reset r    -> Effect.succeed(0);
                case Snapshot s -> Effect.suspend(() -> {
                    ref.set(count);
                    latch.countDown();
                    return count;
                });
            };

        // asHandler() promotes the lambda to a full StatefulHandler
        StatefulHandler<RuntimeException, Integer, CounterMsg> handler = behavior.asHandler();

        Pid counter = system.statefulActorOf(handler, 0)
            .withId("counter-ashandler")
            .spawn();
        Thread.sleep(100); // allow async state initialization to complete

        counter.tell(new Inc(3));
        counter.tell(new Snapshot());

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertEquals(3, ref.get());
    }

    @Test
    @DisplayName("fromEffect: actor survives a failing effect (default onError = continue)")
    void effectFailureActorSurvives() throws InterruptedException {
        AtomicInteger goodCount = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(2);

        Pid actor = system.<RuntimeException, Integer, FailMsg>fromEffect(
            (msg, count, ctx) -> switch (msg) {
                case Good g -> Effect.suspend(() -> {
                    int n = count + 1;
                    goodCount.set(n);
                    latch.countDown();
                    return n;
                });
                case Bad b  -> Effect.fail(new RuntimeException("intentional failure"));
            },
            0
        ).withId("fail-test").spawn();

        actor.tell(new Good());
        actor.tell(new Bad());    // default onError = swallow, actor keeps running
        actor.tell(new Good());   // actor must still process this

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertEquals(2, goodCount.get());
    }

    @Test
    @DisplayName("fromEffect: actors are full actors with mailboxes (messages process in order)")
    void actorHasMailbox() throws InterruptedException {
        // Effect-based actors have a mailbox — messages are processed sequentially
        // on a dedicated virtual thread, in the order they arrive.
        AtomicInteger ref = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(3);

        Pid counter = spawnObservableCounter(0, "mailbox-counter", ref, latch);

        // Three increments queued in order; Snapshot fires after all three
        counter.tell(new Inc(1));
        counter.tell(new Inc(1));
        counter.tell(new Inc(1));
        // Three Snapshots — each will decrement the latch once
        counter.tell(new Snapshot());
        counter.tell(new Snapshot());
        counter.tell(new Snapshot());

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        // After 3 increments of 1 each, state == 3
        assertEquals(3, ref.get());
    }
}
