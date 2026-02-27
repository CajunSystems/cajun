package examples;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.functional.EffectActorBuilder;
import com.cajunsystems.functional.capabilities.ConsoleLogHandler;
import com.cajunsystems.functional.capabilities.LogCapability;
import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.capability.Capability;
import com.cajunsystems.roux.capability.CapabilityHandler;
import com.cajunsystems.roux.data.Unit;
import org.junit.jupiter.api.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.cajunsystems.functional.ActorSystemEffectExtensions.spawnEffectActor;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates the reply-via-Pid pattern for effect actors.
 *
 * <p>{@link com.cajunsystems.functional.EffectActorBuilder} does not expose
 * {@code ActorContext} to the effect function, so effect actors cannot use
 * {@code context.getSender()} to reply. Instead, embed the reply destination
 * as a {@code Pid replyTo} field in the request message.
 *
 * <p>The effect actor calls {@code req.replyTo().tell(result)} to send the response.
 * The {@code replyTo} can be any actor: a temporary reply actor, a stateful actor,
 * or another effect actor.
 */
class EffectAskPatternExample {

    // Request embeds the reply-to Pid
    record ComputeRequest(String input, Pid replyTo) {}

    // Response type
    record TransformResult(String upper, int length) {}

    private ActorSystem system;
    private CapabilityHandler<Capability<?>> logHandler;

    @BeforeEach
    void setUp() {
        system = new ActorSystem();
        logHandler = new ConsoleLogHandler().widen();
    }

    @AfterEach
    void tearDown() { system.shutdown(); }

    /**
     * An effect actor processes a request and replies to the embedded {@code replyTo} Pid.
     *
     * <p>The caller spawns a lightweight reply actor (another EffectActorBuilder) to
     * capture the response. This avoids any shared mutable state between caller and worker.
     */
    @Test
    void effectActorRepliesViaEmbeddedReplyToPid() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<TransformResult> captured = new AtomicReference<>();

        // 1. Reply receiver — captures the response
        Pid replyPid = spawnEffectActor(system,
            (TransformResult result) -> Effect.suspend(() -> {
                captured.set(result);
                latch.countDown();
                return Unit.unit();
            })
        );

        // 2. Worker effect actor — transforms input and logs via LogCapability
        Pid transformActor = new EffectActorBuilder<>(
            system,
            (ComputeRequest req) -> Effect.generate(ctx -> {
                ctx.perform(new LogCapability.Info("Transforming: \"" + req.input() + "\""));
                String upper  = req.input().toUpperCase();
                int    length = req.input().length();
                ctx.perform(new LogCapability.Debug(
                        "Result: " + upper + " (length=" + length + ")"));
                // Reply directly to the embedded replyTo Pid
                req.replyTo().tell(new TransformResult(upper, length));
                return Unit.unit();
            }, logHandler)
        ).withId("transform-actor").spawn();

        // 3. Send request with reply destination embedded in the message
        transformActor.tell(new ComputeRequest("hello world", replyPid));

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals("HELLO WORLD", captured.get().upper());
        assertEquals(11, captured.get().length());
    }

    /**
     * Multiple callers share one effect actor, each providing their own reply Pid.
     *
     * <p>Because the reply Pid is embedded in the message rather than inferred from
     * sender context, concurrent callers never interfere with each other's responses.
     */
    @Test
    void multipleCallersShareOneEffectActor() throws InterruptedException {
        int callerCount = 3;
        CountDownLatch latch = new CountDownLatch(callerCount);
        AtomicInteger totalLength = new AtomicInteger(0);

        // Shared worker effect actor
        Pid counterActor = new EffectActorBuilder<>(
            system,
            (ComputeRequest req) -> Effect.generate(ctx -> {
                ctx.perform(new LogCapability.Info(
                        "Counting chars in: \"" + req.input() + "\""));
                req.replyTo().tell(req.input().length());
                return Unit.unit();
            }, logHandler)
        ).withId("char-counter").spawn();

        // Three independent callers — each with its own reply actor
        String[] inputs = {"hello", "world", "cajun"};  // 5 + 5 + 5 = 15 total
        for (String input : inputs) {
            Pid replyPid = spawnEffectActor(system,
                (Integer count) -> Effect.suspend(() -> {
                    totalLength.addAndGet(count);
                    latch.countDown();
                    return Unit.unit();
                })
            );
            counterActor.tell(new ComputeRequest(input, replyPid));
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(15, totalLength.get()); // 5 + 5 + 5
    }
}
