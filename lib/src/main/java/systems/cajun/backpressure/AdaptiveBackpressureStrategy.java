package systems.cajun.backpressure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * An adaptive backpressure strategy that adjusts its behavior based on system load.
 * This strategy implements several techniques for handling backpressure:
 * 
 * 1. Adaptive throttling based on queue size and processing time
 * 2. Probabilistic message dropping under extreme load
 * 3. Exponential backoff for retrying failed operations
 * 4. Circuit breaking to prevent cascading failures
 */
public class AdaptiveBackpressureStrategy implements BackpressureStrategy {
    private static final long serialVersionUID = 1L;
    private static final Logger logger = LoggerFactory.getLogger(AdaptiveBackpressureStrategy.class);
    
    // Thresholds for backpressure levels
    private final int lowQueueThreshold;
    private final int highQueueThreshold;
    private final int criticalQueueThreshold;
    private final long highProcessingTimeThresholdNanos;
    
    // Metrics tracking
    private final AtomicInteger rejectedMessagesCount = new AtomicInteger(0);
    private final AtomicInteger delayedMessagesCount = new AtomicInteger(0);
    private final AtomicLong totalProcessingTimeNanos = new AtomicLong(0);
    private final AtomicLong messageCount = new AtomicLong(0);
    
    // Circuit breaker state
    private volatile boolean circuitOpen = false;
    private volatile long circuitOpenUntilTimestamp = 0;
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final int failureThreshold;
    private final long baseCircuitOpenTimeMs;
    
    // Current backpressure level (0.0 - 1.0)
    private volatile double currentBackpressureLevel = 0.0;
    
    /**
     * Creates a new AdaptiveBackpressureStrategy with default settings.
     */
    public AdaptiveBackpressureStrategy() {
        this(50, 200, 1000, TimeUnit.MILLISECONDS.toNanos(50), 5, 1000);
    }
    
    /**
     * Creates a new AdaptiveBackpressureStrategy with custom settings.
     *
     * @param lowQueueThreshold Queue size at which to start applying mild backpressure
     * @param highQueueThreshold Queue size at which to apply moderate backpressure
     * @param criticalQueueThreshold Queue size at which to apply severe backpressure
     * @param highProcessingTimeThresholdNanos Processing time threshold in nanoseconds
     * @param failureThreshold Number of consecutive failures before opening the circuit
     * @param baseCircuitOpenTimeMs Base time in milliseconds to keep the circuit open
     */
    public AdaptiveBackpressureStrategy(
            int lowQueueThreshold,
            int highQueueThreshold,
            int criticalQueueThreshold,
            long highProcessingTimeThresholdNanos,
            int failureThreshold,
            long baseCircuitOpenTimeMs) {
        this.lowQueueThreshold = lowQueueThreshold;
        this.highQueueThreshold = highQueueThreshold;
        this.criticalQueueThreshold = criticalQueueThreshold;
        this.highProcessingTimeThresholdNanos = highProcessingTimeThresholdNanos;
        this.failureThreshold = failureThreshold;
        this.baseCircuitOpenTimeMs = baseCircuitOpenTimeMs;
    }
    
    @Override
    public boolean shouldApplyBackpressure(int queueSize, long processingTimeNanos, int persistenceQueueSize) {
        // Check if circuit breaker is open
        if (isCircuitOpen()) {
            return true;
        }
        
        // Calculate backpressure level based on queue size
        double queueBackpressure = calculateQueueBackpressure(queueSize);
        
        // Calculate backpressure level based on processing time
        double processingTimeBackpressure = calculateProcessingTimeBackpressure(processingTimeNanos);
        
        // Calculate backpressure level based on persistence queue size
        double persistenceBackpressure = calculatePersistenceBackpressure(persistenceQueueSize);
        
        // Take the maximum of all backpressure sources
        currentBackpressureLevel = Math.max(Math.max(queueBackpressure, processingTimeBackpressure), persistenceBackpressure);
        
        // Apply backpressure if level is above zero
        return currentBackpressureLevel > 0.0;
    }
    
    @Override
    public <M> boolean handleMessageUnderBackpressure(M message, Consumer<M> normalProcessing) {
        // If circuit is open, reject the message
        if (isCircuitOpen()) {
            rejectedMessagesCount.incrementAndGet();
            logger.warn("Circuit open, rejecting message: {}", message);
            return false;
        }
        
        // Under critical load, probabilistically drop messages
        if (currentBackpressureLevel > 0.8) {
            double dropProbability = (currentBackpressureLevel - 0.8) * 5.0; // Scale to 0-1 range
            if (ThreadLocalRandom.current().nextDouble() < dropProbability) {
                rejectedMessagesCount.incrementAndGet();
                logger.warn("Dropping message due to critical load: {}", message);
                return false;
            }
        }
        
        // Under high load, delay processing
        if (currentBackpressureLevel > 0.5) {
            delayedMessagesCount.incrementAndGet();
            long delayMs = (long)(currentBackpressureLevel * 100); // Scale delay based on backpressure
            logger.debug("Delaying message processing by {}ms due to backpressure: {}", delayMs, message);
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("Interrupted while delaying message processing");
            }
        }
        
        // Process the message
        normalProcessing.accept(message);
        return true;
    }
    
    @Override
    public void onMessageProcessed(long processingTimeNanos) {
        // Update processing time metrics
        totalProcessingTimeNanos.addAndGet(processingTimeNanos);
        messageCount.incrementAndGet();
        
        // Reset consecutive failures counter on successful processing
        consecutiveFailures.set(0);
    }
    
    @Override
    public void onMessageProcessingFailed(Throwable exception) {
        // Increment consecutive failures counter
        int failures = consecutiveFailures.incrementAndGet();
        
        // If threshold reached, open the circuit
        if (failures >= failureThreshold) {
            openCircuit();
        }
    }
    
    @Override
    public double getCurrentBackpressureLevel() {
        return currentBackpressureLevel;
    }
    
    @Override
    public BackpressureMetrics getMetrics() {
        long count = messageCount.get();
        long avgProcessingTime = count > 0 ? totalProcessingTimeNanos.get() / count : 0;
        
        return new BackpressureMetrics(
                currentBackpressureLevel,
                0, // Current queue size not tracked here
                rejectedMessagesCount.get(),
                delayedMessagesCount.get(),
                avgProcessingTime,
                0  // Persistence queue size not tracked here
        );
    }
    
    /**
     * Calculates backpressure level based on queue size.
     *
     * @param queueSize Current queue size
     * @return Backpressure level between 0.0 and 1.0
     */
    private double calculateQueueBackpressure(int queueSize) {
        if (queueSize <= lowQueueThreshold) {
            return 0.0;
        } else if (queueSize <= highQueueThreshold) {
            // Linear scaling between low and high thresholds (0.0 - 0.5)
            return 0.5 * (queueSize - lowQueueThreshold) / (double)(highQueueThreshold - lowQueueThreshold);
        } else if (queueSize <= criticalQueueThreshold) {
            // Linear scaling between high and critical thresholds (0.5 - 1.0)
            return 0.5 + 0.5 * (queueSize - highQueueThreshold) / (double)(criticalQueueThreshold - highQueueThreshold);
        } else {
            return 1.0;
        }
    }
    
    /**
     * Calculates backpressure level based on processing time.
     *
     * @param processingTimeNanos Processing time in nanoseconds
     * @return Backpressure level between 0.0 and 1.0
     */
    private double calculateProcessingTimeBackpressure(long processingTimeNanos) {
        if (processingTimeNanos <= highProcessingTimeThresholdNanos) {
            // Linear scaling up to the threshold (0.0 - 0.5)
            return 0.5 * processingTimeNanos / (double)highProcessingTimeThresholdNanos;
        } else {
            // Exponential scaling beyond the threshold (0.5 - 1.0)
            double ratio = processingTimeNanos / (double)highProcessingTimeThresholdNanos;
            return Math.min(1.0, 0.5 + 0.5 * Math.log10(ratio));
        }
    }
    
    /**
     * Calculates backpressure level based on persistence queue size.
     *
     * @param persistenceQueueSize Current persistence queue size
     * @return Backpressure level between 0.0 and 1.0
     */
    private double calculatePersistenceBackpressure(int persistenceQueueSize) {
        // Similar logic to queue backpressure but with different thresholds
        int persistenceLowThreshold = 10;
        int persistenceHighThreshold = 50;
        int persistenceCriticalThreshold = 200;
        
        if (persistenceQueueSize <= persistenceLowThreshold) {
            return 0.0;
        } else if (persistenceQueueSize <= persistenceHighThreshold) {
            return 0.5 * (persistenceQueueSize - persistenceLowThreshold) / 
                   (double)(persistenceHighThreshold - persistenceLowThreshold);
        } else if (persistenceQueueSize <= persistenceCriticalThreshold) {
            return 0.5 + 0.5 * (persistenceQueueSize - persistenceHighThreshold) / 
                   (double)(persistenceCriticalThreshold - persistenceHighThreshold);
        } else {
            return 1.0;
        }
    }
    
    /**
     * Opens the circuit breaker for a period of time.
     * The duration increases exponentially with consecutive failures.
     */
    private void openCircuit() {
        int failures = consecutiveFailures.get();
        long openTimeMs = baseCircuitOpenTimeMs * (long)Math.pow(2, failures - failureThreshold);
        openTimeMs = Math.min(openTimeMs, TimeUnit.MINUTES.toMillis(5)); // Cap at 5 minutes
        
        circuitOpen = true;
        circuitOpenUntilTimestamp = System.currentTimeMillis() + openTimeMs;
        
        logger.warn("Circuit opened for {}ms after {} consecutive failures", openTimeMs, failures);
    }
    
    /**
     * Checks if the circuit breaker is currently open.
     * If the open period has expired, closes the circuit.
     *
     * @return true if the circuit is open, false otherwise
     */
    private boolean isCircuitOpen() {
        if (circuitOpen) {
            long now = System.currentTimeMillis();
            if (now >= circuitOpenUntilTimestamp) {
                circuitOpen = false;
                logger.info("Circuit closed after cooling period");
                return false;
            }
            return true;
        }
        return false;
    }
    
    /**
     * Manually resets the circuit breaker to closed state.
     * This can be used to force the system to try processing messages again.
     */
    public void resetCircuitBreaker() {
        circuitOpen = false;
        consecutiveFailures.set(0);
        logger.info("Circuit breaker manually reset");
    }
}
