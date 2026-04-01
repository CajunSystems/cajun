package com.cajunsystems.functional;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.functional.capabilities.ConsoleLogHandler;
import com.cajunsystems.functional.capabilities.LogCapability;
import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.data.Unit;
import org.junit.jupiter.api.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.cajunsystems.functional.ActorSystemEffectExtensions.*;
import static org.junit.jupiter.api.Assertions.*;

class EffectActorBuilderTest {

    private ActorSystem system;

    @BeforeEach
    void setUp() {
        system = new ActorSystem();
    }

    @AfterEach
    void tearDown() {
        system.shutdown();
    }

    @Test
    void effectActorProcessesMessageViaSuspend() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> captured = new AtomicReference<>();

        Pid pid = spawnEffectActor(
                system,
                (String msg) -> Effect.suspend(() -> {
                    captured.set(msg);
                    latch.countDown();
                    return Unit.unit();
                })
        );

        pid.tell("hello-effect");
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals("hello-effect", captured.get());
    }

    @Test
    void effectActorProcessesMessageViaSucceedAndFlatMap() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        Pid pid = spawnEffectActor(
                system,
                (String msg) -> Effect.<RuntimeException, Unit>succeed(Unit.unit())
                        .flatMap(__ -> Effect.suspend(() -> {
                            latch.countDown();
                            return Unit.unit();
                        }))
        );

        pid.tell("trigger");
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    void withIdAssignsActorId() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        Pid pid = new EffectActorBuilder<>(
                system,
                (String msg) -> Effect.suspend(() -> {
                    latch.countDown();
                    return Unit.unit();
                })
        ).withId("named-effect-actor").spawn();

        assertEquals("named-effect-actor", pid.actorId());
        pid.tell("ping");
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    void withCapabilityHandlerEnablesCapabilityEffects() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        ConsoleLogHandler logHandler = new ConsoleLogHandler();

        Pid pid = new EffectActorBuilder<>(
                system,
                (String msg) -> Effect.<RuntimeException, Unit>from(
                                new LogCapability.Info("received: " + msg))
                        .flatMap(__ -> Effect.suspend(() -> {
                            latch.countDown();
                            return Unit.unit();
                        }))
        ).withCapabilityHandler(logHandler.widen()).spawn();

        pid.tell("cap-test");
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    void multipleMessagesProcessedByEffectActor() throws InterruptedException {
        int count = 3;
        CountDownLatch latch = new CountDownLatch(count);
        AtomicInteger total = new AtomicInteger(0);

        Pid pid = spawnEffectActor(
                system,
                (Integer msg) -> Effect.suspend(() -> {
                    total.addAndGet(msg);
                    latch.countDown();
                    return Unit.unit();
                })
        );

        pid.tell(1);
        pid.tell(2);
        pid.tell(3);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(6, total.get());
    }

    @Test
    void effectActorOfReturnsConfigurableBuilder() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        Pid pid = effectActorOf(
                system,
                (String msg) -> Effect.suspend(() -> {
                    latch.countDown();
                    return Unit.unit();
                })
        ).withId("builder-actor").spawn();

        assertEquals("builder-actor", pid.actorId());
        pid.tell("test");
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    void spawnEffectActorThreeArgVariantAssignsId() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        Pid pid = spawnEffectActor(
                system,
                "explicit-id-actor",
                (String msg) -> Effect.suspend(() -> {
                    latch.countDown();
                    return Unit.unit();
                })
        );

        assertEquals("explicit-id-actor", pid.actorId());
        pid.tell("ping");
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}
