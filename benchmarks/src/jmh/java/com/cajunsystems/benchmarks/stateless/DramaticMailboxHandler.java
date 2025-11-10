package com.cajunsystems.benchmarks.stateless;

import com.cajunsystems.ActorContext;
import com.cajunsystems.handler.Handler;

/**
 * Handler designed to reveal mailbox performance differences.
 * 
 * This handler creates scenarios where:
 * - BLOCKING mailboxes show thread overhead
 * - DISPATCHER_LBQ shows queue contention
 * - DISPATCHER_MPSC shows lock-free superiority
 */
public class DramaticMailboxHandler implements Handler<Object> {
    
    @Override
    public void receive(Object message, ActorContext context) {
        if (message instanceof DramaticMailboxComparison.DramaticMessage) {
            DramaticMailboxComparison.DramaticMessage msg = 
                (DramaticMailboxComparison.DramaticMessage) message;
            
            // Minimal processing to focus on mailbox performance
            // This makes mailbox overhead the dominant factor
            
            // Very light CPU work to simulate real processing
            if (msg.id % 100 == 0) {
                // Only 1% of messages do any meaningful work
                Math.sqrt(msg.id);
            }
            
            // Count and acknowledge
            msg.counter.incrementAndGet();
            
            // Complete after processing all messages for this actor
            if (msg.id == 999) { // Last message (0-999)
                msg.latch.countDown();
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
