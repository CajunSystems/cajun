package com.cajunsystems.benchmarks;

import com.cajunsystems.ActorContext;
import com.cajunsystems.handler.Handler;

/**
 * Handler that creates sustained system pressure to trigger backpressure.
 */
public class DirectBackpressureHandler implements Handler<Object> {
    
    @Override
    public void receive(Object message, ActorContext context) {
        if (message instanceof DirectBackpressureTest.FloodMessage) {
            DirectBackpressureTest.FloodMessage msg = 
                (DirectBackpressureTest.FloodMessage) message;
            
            // Create sustained processing pressure
            try {
                // Simulate I/O-bound work that creates backpressure
                if (msg.id % 3 == 0) {
                    Thread.sleep(5); // 33% of messages take 5ms
                } else if (msg.id % 10 == 0) {
                    Thread.sleep(10); // 10% take 10ms
                } else {
                    Thread.sleep(2); // Most take 2ms
                }
                
                // CPU work to add pressure
                for (int i = 0; i < 100; i++) {
                    Math.sqrt(msg.id * i);
                }
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            
            // Count completion
            msg.counter.incrementAndGet();
            
            // Complete after processing all messages for this actor
            if (msg.id == 999) { // Last message
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
