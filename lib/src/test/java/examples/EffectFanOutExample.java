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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.cajunsystems.functional.ActorSystemEffectExtensions.spawnEffectActor;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates fan-out from a dispatcher effect actor to a pool of worker effect actors.
 *
 * <p>A {@link com.cajunsystems.functional.EffectActorBuilder} dispatcher receives a
 * {@link Batch} of work items and distributes them across a worker pool using round-robin
 * assignment. Each worker processes its item independently and sends the result to a
 * shared collector actor.
 *
 * <p>Key patterns shown:
 * <ul>
 *   <li>Fan-out: one dispatcher forwards N items to N workers</li>
 *   <li>Worker pool: multiple actors of the same type sharing work concurrently</li>
 *   <li>Result aggregation: all workers reply to a single collector via embedded Pid</li>
 *   <li>{@code AtomicInteger} cursor for stateless round-robin inside a pure effect</li>
 * </ul>
 */
class EffectFanOutExample {

    // --- Message types ---

    /** A batch of text items sent to the dispatcher. */
    record Batch(List<String> items) {}

    /**
     * One unit of work sent from the dispatcher to a worker.
     * Embeds the reply-to Pid so workers know where to send results.
     */
    record WorkItem(String text, Pid replyTo) {}

    /** Result produced by a worker and sent to the collector. */
    record WorkResult(String original, String processed) {}

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
     * Three work items are dispatched to three separate workers.
     * Each worker processes one item and replies to the shared collector.
     * The collector accumulates all three results.
     */
    @Test
    void dispatcherFansOutToThreeWorkers() throws InterruptedException {
        List<WorkResult> results = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(3);

        // Collector — aggregates results from all workers
        Pid collector = spawnEffectActor(system,
            (WorkResult r) -> Effect.generate(ctx -> {
                ctx.perform(new LogCapability.Info(
                        "[collector] \"" + r.original() + "\" → \"" + r.processed() + "\""));
                results.add(r);
                latch.countDown();
                return Unit.unit();
            }, logHandler)
        );

        // Worker pool — three independent actors of the same type
        Pid[] workers = new Pid[3];
        for (int i = 0; i < 3; i++) {
            final int workerId = i;
            workers[i] = new EffectActorBuilder<>(
                system,
                (WorkItem item) -> Effect.generate(ctx -> {
                    ctx.perform(new LogCapability.Debug(
                            "[worker-" + workerId + "] processing \"" + item.text() + "\""));
                    String processed = item.text().toUpperCase();
                    item.replyTo().tell(new WorkResult(item.text(), processed));
                    return Unit.unit();
                }, logHandler)
            ).withId("worker-" + workerId).spawn();
        }

        // Dispatcher — round-robin fan-out across the worker pool
        AtomicInteger cursor = new AtomicInteger(0);
        Pid dispatcher = new EffectActorBuilder<>(
            system,
            (Batch batch) -> Effect.generate(ctx -> {
                ctx.perform(new LogCapability.Info(
                        "[dispatcher] fanning out " + batch.items().size() +
                        " items to " + workers.length + " workers"));
                for (String item : batch.items()) {
                    int idx = cursor.getAndIncrement() % workers.length;
                    workers[idx].tell(new WorkItem(item, collector));
                }
                return Unit.unit();
            }, logHandler)
        ).withId("dispatcher").spawn();

        dispatcher.tell(new Batch(List.of("hello", "world", "cajun")));

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(3, results.size());
        assertTrue(results.stream().anyMatch(r ->
                r.original().equals("hello") && r.processed().equals("HELLO")));
        assertTrue(results.stream().anyMatch(r ->
                r.original().equals("world") && r.processed().equals("WORLD")));
        assertTrue(results.stream().anyMatch(r ->
                r.original().equals("cajun") && r.processed().equals("CAJUN")));
    }

    /**
     * A two-worker pool handles two consecutive batches (four items total).
     * All four results arrive at the collector regardless of processing order.
     *
     * <p>This test uses {@code Effect.suspend()} without capabilities to show
     * the lighter-weight form for stages that don't need per-step logging.
     * Workers produce the character count of each input as their "processed" result.
     */
    @Test
    void twoWorkerPoolHandlesTwoBatches() throws InterruptedException {
        List<WorkResult> results = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(4);

        Pid collector = spawnEffectActor(system,
            (WorkResult r) -> Effect.suspend(() -> {
                results.add(r);
                latch.countDown();
                return Unit.unit();
            })
        );

        // Two workers — each transforms text to "N chars"
        Pid[] workers = new Pid[2];
        for (int i = 0; i < 2; i++) {
            final int id = i;
            workers[i] = new EffectActorBuilder<>(
                system,
                (WorkItem item) -> Effect.generate(ctx -> {
                    ctx.perform(new LogCapability.Debug(
                            "[w" + id + "] \"" + item.text() + "\""));
                    item.replyTo().tell(
                            new WorkResult(item.text(), item.text().length() + " chars"));
                    return Unit.unit();
                }, logHandler)
            ).withId("pool-worker-" + i).spawn();
        }

        AtomicInteger cursor = new AtomicInteger(0);
        Pid dispatcher = new EffectActorBuilder<>(
            system,
            (Batch batch) -> Effect.suspend(() -> {
                for (String item : batch.items()) {
                    int idx = cursor.getAndIncrement() % workers.length;
                    workers[idx].tell(new WorkItem(item, collector));
                }
                return Unit.unit();
            })
        ).withId("pool-dispatcher").spawn();

        dispatcher.tell(new Batch(List.of("hi", "world")));    // 2 + 5 chars
        dispatcher.tell(new Batch(List.of("foo", "bar")));     // 3 + 3 chars

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(4, results.size());
        assertTrue(results.stream().anyMatch(r -> r.processed().equals("2 chars")));
        assertTrue(results.stream().anyMatch(r -> r.processed().equals("5 chars")));
        // "foo" and "bar" are both 3 chars → exactly 2 results should be "3 chars"
        assertEquals(2, results.stream().filter(r -> r.processed().equals("3 chars")).count());
    }
}
