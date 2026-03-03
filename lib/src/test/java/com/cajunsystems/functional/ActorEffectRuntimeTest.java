package com.cajunsystems.functional;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.config.ThreadPoolFactory;
import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.Fiber;
import com.cajunsystems.roux.capability.Capability;
import com.cajunsystems.roux.capability.CapabilityHandler;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ActorEffectRuntimeTest {

    // Nested capability used by scopedForkInheritsCapabilityHandlerFromParent test.
    // Must be a static nested type — Java 21 does not allow local sealed interfaces.
    sealed interface TrackCapability extends Capability<String>
            permits TrackCapability.Label {
        record Label(String name) implements TrackCapability {}
    }

    private ActorSystem system;
    private ActorEffectRuntime runtime;

    @BeforeEach
    void setUp() {
        system = new ActorSystem();
        runtime = new ActorEffectRuntime(system);
    }

    @AfterEach
    void tearDown() {
        system.shutdown();
    }

    @Test
    void succeedEffectReturnsValue() throws Exception {
        Effect<RuntimeException, Integer> effect = Effect.succeed(42);
        assertEquals(42, runtime.unsafeRun(effect));
    }

    @Test
    void failEffectThrowsError() {
        Effect<RuntimeException, Integer> effect =
                Effect.fail(new RuntimeException("actor-effect-failure"));
        RuntimeException ex = assertThrows(RuntimeException.class, () -> runtime.unsafeRun(effect));
        assertEquals("actor-effect-failure", ex.getMessage());
    }

    @Test
    void flatMapChainsEffects() throws Exception {
        Effect<RuntimeException, Integer> effect =
                Effect.<RuntimeException, Integer>succeed(5)
                      .flatMap(n -> Effect.succeed(n * 3));
        assertEquals(15, runtime.unsafeRun(effect));
    }

    @Test
    void runAsyncInvokesSuccessCallback() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Integer> result = new AtomicReference<>();

        runtime.runAsync(
                Effect.<RuntimeException, Integer>succeed(99),
                v  -> { result.set(v); latch.countDown(); },
                err -> latch.countDown()
        );

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(99, result.get());
    }

    @Test
    void runAsyncInvokesErrorCallback() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> caught = new AtomicReference<>();

        runtime.runAsync(
                Effect.<RuntimeException, Integer>fail(new RuntimeException("async-fail")),
                _   -> latch.countDown(),
                err -> { caught.set(err); latch.countDown(); }
        );

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertNotNull(caught.get());
        assertEquals("async-fail", caught.get().getMessage());
    }

    @Test
    void fiberForkAndJoinReturnsResult() throws Throwable {
        // fork() returns Effect<Throwable, Fiber<E,A>> (error type is widened to Throwable).
        // join() returns Effect<E,A> (e.g. Effect<RuntimeException,String>) which must be
        // widened to Effect<Throwable,String> so it matches the outer flatMap's error type.
        Effect<Throwable, Fiber<RuntimeException, String>> forked =
                Effect.<RuntimeException, String>succeed("from-fiber").fork();
        Effect<Throwable, String> workflow = forked.flatMap(fiber -> fiber.join().widen());

        assertEquals("from-fiber", runtime.unsafeRun(workflow));
    }

    @Test
    void executorIsNonNull() {
        assertNotNull(runtime.executor());
    }

    @Test
    void worksWithSharedExecutorEnabled() throws Exception {
        ActorSystem sharedSystem = new ActorSystem(
                new ThreadPoolFactory().setUseSharedExecutor(true)
        );
        try {
            ActorEffectRuntime sharedRuntime = new ActorEffectRuntime(sharedSystem);
            assertEquals("shared", sharedRuntime.unsafeRun(Effect.<RuntimeException, String>succeed("shared")));
        } finally {
            sharedSystem.shutdown();
        }
    }

    @Test
    void worksWithSharedExecutorDisabled() throws Exception {
        ActorSystem noSharedSystem = new ActorSystem(
                new ThreadPoolFactory().setUseSharedExecutor(false)
        );
        try {
            ActorEffectRuntime dedicatedRuntime = new ActorEffectRuntime(noSharedSystem);
            assertEquals("dedicated", dedicatedRuntime.unsafeRun(Effect.<RuntimeException, String>succeed("dedicated")));
        } finally {
            noSharedSystem.shutdown();
        }
    }

    @Test
    void timeoutExpiresWhenEffectExceedsDeadline() {
        Effect<Throwable, String> slow = Effect.<RuntimeException, String>suspend(() -> {
            Thread.sleep(500);
            return "too late";
        }).timeout(Duration.ofMillis(50));

        // Roux uses com.cajunsystems.roux.exception.TimeoutException (not java.util.concurrent.TimeoutException)
        Throwable thrown = assertThrows(Throwable.class, () -> runtime.unsafeRun(slow));
        assertTrue(thrown.getClass().getName().contains("TimeoutException"),
                "Expected TimeoutException (possibly wrapped), got: " + thrown);
    }

    @Test
    void timeoutDoesNotTriggerForFastEffect() throws Throwable {
        Effect<Throwable, String> fast = Effect.<RuntimeException, String>suspend(() -> {
            Thread.sleep(10);
            return "on time";
        }).timeout(Duration.ofMillis(500));

        assertEquals("on time", runtime.unsafeRun(fast));
    }

    @Test
    void scopedForkInheritsCapabilityHandlerFromParent() throws Throwable {
        // Handler registered via builder — dispatches TrackCapability.Label, throws UOE for anything else
        CapabilityHandler<Capability<?>> tracker = CapabilityHandler.builder()
                .on(TrackCapability.Label.class, l -> "handled-" + l.name())
                .build();

        // Scoped effect: fork a child that uses the capability, join it,
        // then use the capability again in the parent.
        // v0.2.1 fix: forkIn() now inherits the parent ExecutionContext (including capability handlers)
        Effect<Throwable, String> effect = Effect.scoped(scope -> {
            Effect<Throwable, Fiber<RuntimeException, String>> forkedFiber =
                    Effect.<RuntimeException, String>from(new TrackCapability.Label("child"))
                          .forkIn(scope);

            return forkedFiber
                    .flatMap(fiber -> fiber.join().widen())
                    .flatMap(childResult ->
                        Effect.<RuntimeException, String>from(new TrackCapability.Label("parent"))
                              .map(parentResult -> childResult + "+" + parentResult)
                              .widen()
                    );
        });

        String result = runtime.unsafeRunWithHandler(effect, tracker);
        assertEquals("handled-child+handled-parent", result);
    }
}
