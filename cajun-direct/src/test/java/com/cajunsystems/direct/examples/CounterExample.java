package com.cajunsystems.direct.examples;

import com.cajunsystems.direct.*;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Example showing a stateful counter actor with direct-style calls.
 *
 * <p>Demonstrates:
 * <ul>
 *   <li>Stateful direct actors with immutable state</li>
 *   <li>{@link DirectResult} for returning both new state and reply</li>
 *   <li>Mixed {@code call} / {@code tell} / {@code callAsync} usage</li>
 *   <li>Inter-actor coordination using direct calls on virtual threads</li>
 * </ul>
 */
public class CounterExample {

    // Message protocol
    sealed interface CounterMsg {
        record Increment() implements CounterMsg {}
        record IncrementBy(int amount) implements CounterMsg {}
        record Decrement() implements CounterMsg {}
        record Reset() implements CounterMsg {}
        record GetValue() implements CounterMsg {}
    }

    // Stateful handler: returns (newState, reply) for every message
    static class CounterHandler implements StatefulDirectHandler<Integer, CounterMsg, Integer> {
        @Override
        public DirectResult<Integer, Integer> handle(
                CounterMsg msg, Integer state, DirectContext context) {
            return switch (msg) {
                case CounterMsg.Increment _         -> DirectResult.of(state + 1, state + 1);
                case CounterMsg.IncrementBy(int n)  -> DirectResult.of(state + n, state + n);
                case CounterMsg.Decrement _         -> DirectResult.of(state - 1, state - 1);
                case CounterMsg.Reset _             -> DirectResult.of(0, 0);
                case CounterMsg.GetValue _          -> DirectResult.of(state, state);
            };
        }

        @Override
        public Integer preStart(Integer initialState, DirectContext context) {
            context.getLogger().info("Counter starting with state {}", initialState);
            return initialState;
        }
    }

    public static void main(String[] args) throws Exception {
        DirectActorSystem system = new DirectActorSystem();

        DirectPid<CounterMsg, Integer> counter = system
                .statefulActorOf(new CounterHandler(), 0)
                .withId("counter")
                .withTimeout(Duration.ofSeconds(5))
                .spawn();

        // Sequential blocking calls — reads like normal imperative code
        System.out.println("Initial: " + counter.call(new CounterMsg.GetValue()));   // 0
        System.out.println("After ++: " + counter.call(new CounterMsg.Increment())); // 1
        System.out.println("After ++: " + counter.call(new CounterMsg.Increment())); // 2
        System.out.println("After +5: " + counter.call(new CounterMsg.IncrementBy(5))); // 7
        System.out.println("After --: " + counter.call(new CounterMsg.Decrement())); // 6
        System.out.println("After reset: " + counter.call(new CounterMsg.Reset()));  // 0

        // Fire-and-forget increments (no reply needed)
        for (int i = 0; i < 10; i++) {
            counter.tell(new CounterMsg.Increment());
        }
        // Wait for all tells to be processed, then read the value
        Thread.sleep(100);
        System.out.println("After 10 tells: " + counter.call(new CounterMsg.GetValue())); // 10

        // Async call for non-blocking read
        CompletableFuture<Integer> valueFuture = counter.callAsync(new CounterMsg.GetValue());
        valueFuture
                .thenApply(v -> "Async read: " + v)
                .thenAccept(System.out::println);

        // Coordination on virtual threads: two actors, one reads from the other
        DirectPid<CounterMsg, Integer> secondary = system
                .statefulActorOf(new CounterHandler(), 100)
                .withId("secondary-counter")
                .spawn();

        // Run on a virtual thread — blocks safely, no thread pool starvation
        Thread.ofVirtual().start(() -> {
            int fromPrimary = counter.call(new CounterMsg.GetValue());
            secondary.call(new CounterMsg.IncrementBy(fromPrimary));
            int total = secondary.call(new CounterMsg.GetValue());
            System.out.println("Secondary after absorbing primary value: " + total);
        }).join();

        system.shutdown();
    }
}
