package com.cajunsystems.benchmarks;

import com.cajunsystems.ActorContext;
import com.cajunsystems.handler.Handler;

/**
 * Handler that simulates work to create backpressure scenarios.
 * 
 * This handler:
 * - Processes messages with variable delays
 * - Tracks message processing
 * - Simulates real-world work patterns
 */
public class BackpressureStressHandler implements Handler<Object> {
    
    private boolean slowProcessingMode = false;
    
    @Override
    public void receive(Object message, ActorContext context) {
        if (message instanceof BackpressureStressBenchmark.SlowProcessingMessage) {
            // Enable slow processing mode to create backpressure
            slowProcessingMode = true;
            BackpressureStressBenchmark.SlowProcessingMessage msg = 
                (BackpressureStressBenchmark.SlowProcessingMessage) message;
            msg.latch.countDown();
            
        } else if (message instanceof BackpressureStressBenchmark.BackpressureMessage) {
            BackpressureStressBenchmark.BackpressureMessage msg = 
                (BackpressureStressBenchmark.BackpressureMessage) message;
            
            // Simulate variable processing time
            try {
                if (slowProcessingMode) {
                    // In slow mode: ALL messages are slow (creates real backpressure)
                    Thread.sleep(10); // Much slower to overwhelm mailboxes
                } else {
                    // Normal mode: Most messages are fast, some are slow
                    if (msg.value % 10 == 0) {
                        // 10% of messages are slower
                        Thread.sleep(1);
                    } else if (msg.value % 100 == 0) {
                        // 1% of messages are very slow
                        Thread.sleep(5);
                    }
                }
                
                // Occasionally do some CPU work
                if (msg.value % 50 == 0) {
                    // Simulate processing work
                    Math.sqrt(msg.value * 1.0);
                    int work = msg.value;
                    for (int i = 0; i < 10; i++) {
                        work = work * 2 + 1;
                    }
                }
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            
            // Count and acknowledge
            msg.counter.incrementAndGet();
            msg.latch.countDown();
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
