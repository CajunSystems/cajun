package com.cajunsystems.benchmarks;

import com.cajunsystems.mailbox.Mailbox;
import com.cajunsystems.mailbox.LinkedMailbox;
import com.cajunsystems.mailbox.MpscMailbox;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/**
 * Microbenchmarks comparing different mailbox implementations.
 *
 * This focuses purely on in-memory queue performance (offer + poll)
 * rather than full actor throughput.
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class MailboxBenchmark {

    /**
     * Single-threaded benchmarks - each thread gets its own mailbox
     */
    @State(Scope.Thread)
    public static class SingleThreadedState {
        @Param({"linked", "mpsc"})
        public String mailboxType;

        private Mailbox<Integer> mailbox;

        @Setup
        public void setup() {
            switch (mailboxType) {
                case "linked" -> mailbox = new LinkedMailbox<>();
                case "mpsc" -> mailbox = new MpscMailbox<>();
                default -> throw new IllegalArgumentException("Unknown mailbox type: " + mailboxType);
            }
        }
    }

    /**
     * Multi-threaded benchmarks - all threads share the same mailbox
     */
    @State(Scope.Group)
    public static class MultiThreadedState {
        @Param({"linked", "mpsc"})
        public String mailboxType;

        private Mailbox<Integer> mailbox;

        @Setup
        public void setup() {
            switch (mailboxType) {
                case "linked" -> mailbox = new LinkedMailbox<>();
                case "mpsc" -> mailbox = new MpscMailbox<>();
                default -> throw new IllegalArgumentException("Unknown mailbox type: " + mailboxType);
            }
        }

        @TearDown(Level.Iteration)
        public void teardown() {
            // Drain any remaining messages
            while (mailbox.poll() != null) {
                // empty
            }
        }
    }

    /**
     * Single-threaded: Offer then poll in pairs (interleaved).
     * Tests queue overhead with minimal queueing depth.
     */
    @Benchmark
    public int offerPollPairs(SingleThreadedState state) {
        int sum = 0;
        for (int i = 0; i < 1000; i++) {
            state.mailbox.offer(i);
            Integer val = state.mailbox.poll();
            if (val != null) sum += val;
        }
        return sum; // Prevent dead code elimination
    }

    /**
     * Single-threaded: Fill queue, then drain it.
     * Tests queue performance with actual queueing.
     */
    @Benchmark
    public int offerThenPoll(SingleThreadedState state) {
        // Fill
        for (int i = 0; i < 1000; i++) {
            state.mailbox.offer(i);
        }
        // Drain
        int sum = 0;
        for (int i = 0; i < 1000; i++) {
            Integer val = state.mailbox.poll();
            if (val == null) throw new AssertionError("Mailbox underflow at " + i);
            sum += val;
        }
        return sum; // Prevent dead code elimination
    }

    /**
     * Multi-threaded: Multiple producers, single consumer.
     * True MPSC test - all threads share the same mailbox.
     * Each iteration: 3 producers each send 333 msgs = 999 total msgs consumed.
     */
    @Benchmark
    @Group("mpscTest")
    @GroupThreads(3)
    public void producer(MultiThreadedState state) {
        for (int i = 0; i < 333; i++) {
            while (!state.mailbox.offer(i)) {
                Thread.onSpinWait(); // Efficient spin hint
            }
        }
    }

    @Benchmark
    @Group("mpscTest")
    @GroupThreads(1)
    public int consumer(MultiThreadedState state) {
        int sum = 0;
        int received = 0;
        int spins = 0;
        final int target = 999; // 3 producers * 333 msgs
        while (received < target) {
            Integer val = state.mailbox.poll();
            if (val != null) {
                sum += val;
                received++;
                spins = 0;
            } else {
                Thread.onSpinWait(); // Efficient spin hint
                // Give up after too many spins to prevent hangs
                if (++spins > 10000) {
                    break;
                }
            }
        }
        return sum;
    }
}
