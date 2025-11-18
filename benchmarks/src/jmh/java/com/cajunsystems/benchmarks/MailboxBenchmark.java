package com.cajunsystems.benchmarks;

import com.cajunsystems.mailbox.LinkedMailbox;
import com.cajunsystems.mailbox.Mailbox;
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
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class MailboxBenchmark {

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

    /**
     * Offer + poll in a tight loop. This approximates single-producer/single-consumer
     * throughput and basic queue overhead.
     */
    @Benchmark
    @OperationsPerInvocation(1000)
    public void offerPollBurst() {
        for (int i = 0; i < 1000; i++) {
            mailbox.offer(i);
            mailbox.poll();
        }
    }
}
