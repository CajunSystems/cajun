package examples;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.functional.ActorSystemEffectExtensions;
import com.cajunsystems.functional.EffectActorBuilder;
import com.cajunsystems.functional.capabilities.ConsoleLogHandler;
import com.cajunsystems.functional.capabilities.LogCapability;
import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.capability.CapabilityHandler;
import com.cajunsystems.roux.data.Unit;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Demonstrates the Roux-native effect actor API.
 *
 * <p>Shows how to spawn actors whose message handling is expressed as
 * Roux {@link com.cajunsystems.roux.Effect} pipelines, executed through
 * {@link com.cajunsystems.functional.ActorEffectRuntime} on the actor system's executor.
 */
class EffectActorExample {

    /**
     * Logging counter actor using EffectActorBuilder + LogCapability + Effect.generate.
     */
    @Test
    void loggingCounterActor() throws InterruptedException {
        ActorSystem system = new ActorSystem();
        int messageCount = 3;
        CountDownLatch latch = new CountDownLatch(messageCount);
        AtomicInteger processed = new AtomicInteger(0);

        CapabilityHandler<com.cajunsystems.roux.capability.Capability<?>> handler =
                new ConsoleLogHandler().widen();

        Pid counter = new EffectActorBuilder<>(
                system,
                (Integer n) -> Effect.<RuntimeException, Unit>generate(
                        ctx -> {
                            ctx.perform(new LogCapability.Info("Processing: " + n));
                            processed.addAndGet(n);
                            ctx.perform(new LogCapability.Debug("Total: " + processed.get()));
                            latch.countDown();
                            return Unit.unit();
                        },
                        handler
                )
        ).withId("logging-counter").spawn();

        counter.tell(1);
        counter.tell(2);
        counter.tell(3);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(6, processed.get());

        system.shutdown();
    }

    /**
     * One-liner spawning via ActorSystemEffectExtensions + Effect.suspend.
     */
    @Test
    void oneLineEffectActor() throws InterruptedException {
        ActorSystem system = new ActorSystem();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger result = new AtomicInteger();

        Pid echoActor = ActorSystemEffectExtensions.spawnEffectActor(
                system,
                "echo-actor",
                (Integer n) -> Effect.suspend(() -> {
                    result.set(n * 2);
                    latch.countDown();
                    return Unit.unit();
                })
        );

        echoActor.tell(21);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(42, result.get());

        system.shutdown();
    }

    /**
     * Effect pipeline with flatMap chaining across multiple steps.
     */
    @Test
    void effectPipelineWithFlatMap() throws InterruptedException {
        ActorSystem system = new ActorSystem();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger result = new AtomicInteger();

        Pid pipeline = ActorSystemEffectExtensions.spawnEffectActor(
                system,
                (Integer n) -> Effect.<RuntimeException, Integer>succeed(n)
                        .flatMap(x -> Effect.succeed(x * 2))
                        .flatMap(x -> Effect.suspend(() -> {
                            result.set(x);
                            latch.countDown();
                            return Unit.unit();
                        }))
        );

        pipeline.tell(10);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(20, result.get());

        system.shutdown();
    }
}
