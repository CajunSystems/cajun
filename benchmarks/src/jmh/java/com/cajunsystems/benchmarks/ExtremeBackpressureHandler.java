package com.cajunsystems.benchmarks;

import com.cajunsystems.ActorContext;
import com.cajunsystems.handler.Handler;

/**
 * Handler that creates extreme pressure to maximize backpressure effects.
 */
public class ExtremeBackpressureHandler implements Handler<Object> {
    
    @Override
    public void receive(Object message, ActorContext context) {
        if (message instanceof ExtremeBackpressureTest.ExtremeMessage) {
            ExtremeBackpressureTest.ExtremeMessage msg = 
                (ExtremeBackpressureTest.ExtremeMessage) message;
            
            // Create processing pressure that benefits from backpressure
            try {
                // Variable processing to create queue buildup
                if (msg.id % 4 == 0) {
                    Thread.sleep(3); // 25% take 3ms
                } else if (msg.id % 8 == 0) {
                    Thread.sleep(6); // 12.5% take 6ms  
                } else {
                    Thread.sleep(1); // Most take 1ms
                }
                
                // CPU work to increase pressure
                for (int i = 0; i < 50; i++) {
                    Math.sqrt(msg.id * i + 1);
                }
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            
            // Count completion
            msg.counter.incrementAndGet();
            
            // Complete after processing all messages for this actor
            if (msg.id == 499) { // Last message (0-499)
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
