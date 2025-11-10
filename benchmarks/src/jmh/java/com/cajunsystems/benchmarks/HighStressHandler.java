package com.cajunsystems.benchmarks;

import com.cajunsystems.ActorContext;
import com.cajunsystems.handler.Handler;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-stress handler designed to reveal mailbox performance characteristics.
 * 
 * This handler:
 * - Processes messages with minimal work (pure mailbox testing)
 * - Supports stress testing with configurable message counts
 * - Handles high contention scenarios
 * - Tracks processing metrics
 */
public class HighStressHandler implements Handler<Object> {
    
    private ActorContext context;
    private long messagesProcessed = 0;
    
    @Override
    public void receive(Object message, ActorContext context) {
        this.context = context;
        
        if (message instanceof HighStressMailboxBenchmark.WarmupMessage) {
            // Simple warmup - just acknowledge
            messagesProcessed = 0;
            
        } else if (message instanceof HighStressMailboxBenchmark.StressTestStart) {
            handleStressTestStart((HighStressMailboxBenchmark.StressTestStart) message);
            
        } else if (message instanceof HighStressMailboxBenchmark.ContentionMessage) {
            handleContentionMessage((HighStressMailboxBenchmark.ContentionMessage) message);
            
        } else if (message instanceof HighStressMailboxBenchmark.ProcessedMessage) {
            handleProcessedMessage((HighStressMailboxBenchmark.ProcessedMessage) message);
            
        } else if (message instanceof ProcessInteger) {
            handleProcessInteger((ProcessInteger) message);
            
        } else if (message instanceof Integer) {
            // Simple integer processing for basic stress test
            handleInteger((Integer) message);
        }
    }
    
    private void handleStressTestStart(HighStressMailboxBenchmark.StressTestStart start) {
        try {
            // Wait for all actors to be ready
            start.startLatch.await();
            
            // Send messages to self for processing (asynchronous)
            for (int i = 0; i < start.messageCount; i++) {
                context.self().tell(new ProcessInteger(start.counter, start.endLatch));
                
                // Batch send to avoid mailbox overflow
                if (i % 1000 == 999) {
                    Thread.yield();
                }
            }
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    private void handleContentionMessage(HighStressMailboxBenchmark.ContentionMessage message) {
        // Forward message to target actor (creates cross-actor contention)
        message.target.tell(new HighStressMailboxBenchmark.ProcessedMessage(message.latch));
    }
    
    private void handleProcessedMessage(HighStressMailboxBenchmark.ProcessedMessage message) {
        // Simple acknowledgment
        message.latch.countDown();
    }
    
    private void handleProcessInteger(ProcessInteger message) {
        // Minimal processing work
        messagesProcessed++;
        message.counter.incrementAndGet();
        
        // Check if this actor has completed all its messages
        if (messagesProcessed % 100000 == 0) { // Every 100K messages
            message.endLatch.countDown();
        }
    }
    
    private void handleInteger(Integer value) {
        // Minimal processing - just increment counter
        messagesProcessed++;
        
        // Simulate some work
        if (value % 1000 == 0) {
            // Occasional slightly more expensive operation
            Math.sqrt(value);
        }
    }
    
    @Override
    public void preStart(ActorContext context) {
        this.context = context;
        this.messagesProcessed = 0;
    }
    
    @Override
    public void postStop(ActorContext context) {
        // Cleanup if needed
    }
    
    // Message class for processing integers
    public static class ProcessInteger {
        final AtomicLong counter;
        final CountDownLatch endLatch;
        
        public ProcessInteger(AtomicLong counter, CountDownLatch endLatch) {
            this.counter = counter;
            this.endLatch = endLatch;
        }
    }
}
