package com.cajunsystems.benchmarks;

import com.cajunsystems.ActorContext;
import com.cajunsystems.handler.Handler;

/**
 * Simple handler that just counts messages.
 * 
 * This does minimal work to reveal mailbox performance differences.
 */
public class SimpleStressHandler implements Handler<Object> {
    
    @Override
    public void receive(Object message, ActorContext context) {
        if (message instanceof SimpleMailboxStressBenchmark.SimpleMessage) {
            SimpleMailboxStressBenchmark.SimpleMessage msg = 
                (SimpleMailboxStressBenchmark.SimpleMessage) message;
            
            // Minimal processing - just count and acknowledge
            msg.counter.incrementAndGet();
            msg.latch.countDown();
            
            // Very occasional work to simulate real processing
            if (msg.value % 10000 == 0) {
                // Tiny bit of work
                Math.sqrt(msg.value);
            }
        }
    }
    
    @Override
    public void preStart(ActorContext context) {
        // No initialization needed
    }
    
    @Override
    public void postStop(ActorContext context) {
        // No cleanup needed
    }
}
