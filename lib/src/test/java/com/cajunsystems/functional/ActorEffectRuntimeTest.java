package com.cajunsystems.functional;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.config.ThreadPoolFactory;
import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.Fiber;
import org.junit.jupiter.api.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ActorEffectRuntimeTest {

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
    void fiberForkAndJoinReturnsResult() throws Exception {
        // fork() returns Effect<Throwable, Fiber<E,A>> (error type is widened to Throwable)
        Effect<Throwable, Fiber<RuntimeException, String>> forked =
                Effect.<RuntimeException, String>succeed("from-fiber").fork();
        Effect<Throwable, String> workflow = forked.flatMap(Fiber::join);

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
            assertEquals("shared", sharedRuntime.unsafeRun(Effect.succeed("shared")));
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
            assertEquals("dedicated", dedicatedRuntime.unsafeRun(Effect.succeed("dedicated")));
        } finally {
            noSharedSystem.shutdown();
        }
    }
}
