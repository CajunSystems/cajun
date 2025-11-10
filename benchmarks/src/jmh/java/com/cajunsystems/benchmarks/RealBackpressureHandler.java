package com.cajunsystems.benchmarks;

import com.cajunsystems.ActorContext;
import com.cajunsystems.handler.Handler;

/**
 * Handler that simulates realistic processing to create backpressure scenarios.
 */
public class RealBackpressureHandler implements Handler<Object> {
    
    @Override
    public void receive(Object message, ActorContext context) {
        if (message instanceof RealBackpressureBenchmark.TestMessage) {
            RealBackpressureBenchmark.TestMessage msg = 
                (RealBackpressureBenchmark.TestMessage) message;
            
            // Simulate processing work (slower than message arrival)
            try {
                // Variable processing time to create realistic backpressure
                if (msg.id % 5 == 0) {
                    Thread.sleep(2); // 20% of messages take 2ms
                } else if (msg.id % 10 == 0) {
                    Thread.sleep(5); // 10% of messages take 5ms
                } else {
                    Thread.sleep(1); // Most messages take 1ms
                }
                
                // Some CPU work
                if (msg.id % 3 == 0) {
                    Math.sqrt(msg.id * 1.0);
                }
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            
            msg.processedCounter.incrementAndGet();
            
        } else if (message instanceof RealBackpressureBenchmark.FinishMessage) {
            RealBackpressureBenchmark.FinishMessage msg = 
                (RealBackpressureBenchmark.FinishMessage) message;
            msg.latch.countDown();
        }
    }
    
    @Override
    public void preStart(ActorContext context) {
        // No initialization needed
    }
    
    @Override
    public void postStop(ActorContext context) {
        // Cleanup if needed
    }
}
