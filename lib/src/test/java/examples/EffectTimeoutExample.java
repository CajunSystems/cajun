package examples;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.functional.ActorEffectRuntime;
import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.data.Unit;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.cajunsystems.functional.ActorSystemEffectExtensions.spawnEffectActor;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates {@code effect.timeout(Duration)} with graceful degradation patterns.
 *
 * <p>Roux's {@code timeout(Duration)} wraps an effect and fails with
 * {@code com.cajunsystems.roux.exception.TimeoutException} if the effect does not
 * complete within the deadline. The error type widens to {@code Throwable}.
 *
 * <p>The idiomatic degradation pattern is:
 * <pre>{@code
 * effect.timeout(Duration.ofMillis(50))
 *       .catchAll(e -> Effect.succeed("fallback"))
 * }</pre>
 *
 * <p>{@code catchAll} catches any error (including {@code TimeoutException}) and
 * returns a fallback effect. The caller always receives a value — no exception propagates.
 *
 * <p>Key patterns shown:
 * <ul>
 *   <li>Timeout + {@code catchAll} for a fallback value when the deadline is exceeded</li>
 *   <li>Fast effects that complete within a generous deadline — {@code timeout} is transparent</li>
 *   <li>Effect actors that always reply even when processing times out</li>
 * </ul>
 */
class EffectTimeoutExample {

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

    /**
     * A slow effect that exceeds the deadline is replaced by a fallback value via {@code catchAll}.
     *
     * <p>{@code timeout(50ms)} fails when the 500ms sleep is not done in time.
     * {@code catchAll} catches the {@code TimeoutException} and returns {@code "fallback"}.
     * The runtime returns normally — no exception is thrown to the caller.
     */
    @Test
    void timeoutFallbackReturnsDefaultWhenDeadlineExceeded() throws Throwable {
        Effect<RuntimeException, String> slow = Effect.suspend(() -> {
            Thread.sleep(500);
            return "too late";
        });

        // timeout widens to Throwable; catchAll catches TimeoutException → returns fallback
        String result = runtime.unsafeRun(
                slow.timeout(Duration.ofMillis(50))
                    .catchAll(e -> Effect.succeed("fallback"))
        );

        assertEquals("fallback", result);
    }

    /**
     * A fast effect completes normally when the deadline is generous.
     *
     * <p>Confirms that {@code timeout} adds no overhead or result alteration
     * when the effect finishes well within the deadline.
     */
    @Test
    void fastEffectCompletesNormallyUnderTimeout() throws Throwable {
        Effect<RuntimeException, String> fast = Effect.suspend(() -> {
            Thread.sleep(10);
            return "on time";
        });

        String result = runtime.unsafeRun(
                fast.timeout(Duration.ofMillis(500))
                    .catchAll(e -> Effect.succeed("fallback"))
        );

        assertEquals("on time", result);
    }

    /**
     * An effect actor guarantees a reply even when its processing exceeds the timeout deadline.
     *
     * <p>The actor receives a {@code Request} carrying an embedded reply-to {@link Pid}.
     * If the computation finishes within 100 ms it sends the real result; if not, it sends
     * {@code "timeout-reply"}. The collector always receives exactly one reply per request.
     *
     * <p>This pattern is important for systems where callers block on a reply and must not
     * wait indefinitely for slow downstream operations.
     */
    @Test
    void actorAlwaysRepliesEvenOnTimeout() throws InterruptedException {
        record Request(int sleepMs, Pid replyTo) {}

        CountDownLatch latch = new CountDownLatch(2);
        AtomicReference<String> slowReply = new AtomicReference<>();
        AtomicReference<String> fastReply = new AtomicReference<>();

        Pid collector = spawnEffectActor(system,
                (String reply) -> Effect.suspend(() -> {
                    // First reply goes to slowReply, second to fastReply
                    if (!slowReply.compareAndSet(null, reply)) {
                        fastReply.set(reply);
                    }
                    latch.countDown();
                    return Unit.unit();
                })
        );

        Pid processor = spawnEffectActor(system,
                (Request req) -> {
                    Effect<RuntimeException, String> work = Effect.suspend(() -> {
                        Thread.sleep(req.sleepMs());
                        return "processed-" + req.sleepMs();
                    });

                    // 100ms deadline; TimeoutException caught → fallback reply
                    return work.timeout(Duration.ofMillis(100))
                               .catchAll(e -> Effect.succeed("timeout-reply"))
                               .flatMap(reply -> Effect.runnable(() -> req.replyTo().tell(reply)));
                }
        );

        // Slow request (500ms > 100ms deadline) sent first, then fast request (20ms)
        processor.tell(new Request(500, collector));
        processor.tell(new Request(20,  collector));

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals("timeout-reply", slowReply.get(), "Slow request should get timeout fallback");
        assertEquals("processed-20",  fastReply.get(),  "Fast request should get normal result");
    }
}
