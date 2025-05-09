package systems.cajun.flow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import systems.cajun.backpressure.BackpressureMetrics;

import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A queue implementation that provides backpressure capabilities.
 * This queue can operate in different modes to handle high load situations:
 * - BUFFER_THEN_DROP: When the buffer is full, new messages are dropped
 * - BUFFER_THEN_BLOCK: When the buffer is full, the producer is blocked until space is available
 * - ADAPTIVE: Dynamically adjusts behavior based on system load
 *
 * @param <T> The type of elements held in this queue
 */
public class BackpressureAwareQueue<T> implements BlockingQueue<T>, Flow.Subscriber<T> {
    private static final Logger logger = LoggerFactory.getLogger(BackpressureAwareQueue.class);

    // Queue implementation
    private final ConcurrentLinkedQueue<T> queue;
    
    // Capacity management
    private final int capacity;
    private final AtomicInteger size = new AtomicInteger(0);
    
    // Backpressure mode
    private final BackpressureMode backpressureMode;
    
    // Metrics tracking
    private final AtomicLong droppedMessageCount = new AtomicLong(0);
    private final AtomicLong delayedMessageCount = new AtomicLong(0);
    private final AtomicLong processedMessageCount = new AtomicLong(0);
    private final AtomicLong totalProcessingTimeNanos = new AtomicLong(0);
    
    // Lock for blocking operations
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();
    
    // Flow.Subscription for reactive streams
    private Flow.Subscription subscription;
    
    // Adaptive backpressure settings
    private double currentBackpressureLevel = 0.0;
    private long lastProcessingTimeNanos = 0;
    private final long targetProcessingTimeNanos = TimeUnit.MILLISECONDS.toNanos(10); // 10ms target
    
    /**
     * Backpressure modes for the queue
     */
    public enum BackpressureMode {
        /**
         * When the buffer is full, new messages are dropped
         */
        BUFFER_THEN_DROP,
        
        /**
         * When the buffer is full, the producer is blocked until space is available
         */
        BUFFER_THEN_BLOCK,
        
        /**
         * Dynamically adjusts behavior based on system load
         */
        ADAPTIVE
    }
    
    /**
     * Creates a new BackpressureAwareQueue with the specified capacity and backpressure mode
     * 
     * @param capacity The maximum number of elements the queue can hold
     * @param backpressureMode The backpressure mode to use
     */
    public BackpressureAwareQueue(int capacity, BackpressureMode backpressureMode) {
        this.capacity = capacity;
        this.backpressureMode = backpressureMode;
        this.queue = new ConcurrentLinkedQueue<>();
        
        // Initialize backpressure level based on capacity
        updateBackpressureLevel(targetProcessingTimeNanos);
        
        logger.debug("Created BackpressureAwareQueue with capacity {} and mode {}", capacity, backpressureMode);
    }
    
    /**
     * Creates a new BackpressureAwareQueue with the specified capacity and ADAPTIVE backpressure mode
     * 
     * @param capacity The maximum number of elements the queue can hold
     */
    public BackpressureAwareQueue(int capacity) {
        this(capacity, BackpressureMode.ADAPTIVE);
    }
    
    /**
     * Creates a new BackpressureAwareQueue with a default capacity of 1000 and ADAPTIVE backpressure mode
     */
    public BackpressureAwareQueue() {
        this(1000, BackpressureMode.ADAPTIVE);
    }
    
    /**
     * Gets the current backpressure level (0.0-1.0)
     * 
     * @return The current backpressure level
     */
    public double getCurrentBackpressureLevel() {
        return currentBackpressureLevel;
    }
    
    /**
     * Updates the backpressure level based on processing time and queue size
     * 
     * @param processingTimeNanos The time taken to process the last message in nanoseconds
     */
    public void updateBackpressureLevel(long processingTimeNanos) {
        this.lastProcessingTimeNanos = processingTimeNanos;
        totalProcessingTimeNanos.addAndGet(processingTimeNanos);
        
        // Calculate backpressure level based on queue size and processing time
        double queueFactor = (double) size() / capacity;
        double timeFactor = (double) processingTimeNanos / targetProcessingTimeNanos;
        
        // Combine factors with more weight on queue size
        currentBackpressureLevel = 0.7 * queueFactor + 0.3 * Math.min(1.0, timeFactor);
        
        // Ensure the level is between 0 and 1
        currentBackpressureLevel = Math.max(0.0, Math.min(1.0, currentBackpressureLevel));
    }
    
    /**
     * Gets the number of messages that have been dropped due to backpressure
     * 
     * @return The number of dropped messages
     */
    public long getDroppedMessageCount() {
        return droppedMessageCount.get();
    }
    
    /**
     * Gets the number of messages that have been delayed due to backpressure
     * 
     * @return The number of delayed messages
     */
    public long getDelayedMessageCount() {
        return delayedMessageCount.get();
    }
    
    /**
     * Gets the number of messages that have been processed
     * 
     * @return The number of processed messages
     */
    public long getProcessedMessageCount() {
        return processedMessageCount.get();
    }
    
    /**
     * Gets the average processing time per message in nanoseconds
     * 
     * @return The average processing time
     */
    public long getAverageProcessingTimeNanos() {
        long processed = processedMessageCount.get();
        if (processed == 0) {
            return 0;
        }
        return totalProcessingTimeNanos.get() / processed;
    }
    
    /**
     * Gets the current backpressure metrics
     * 
     * @return The current backpressure metrics
     */
    public BackpressureMetrics getBackpressureMetrics() {
        return new BackpressureMetrics(
            currentBackpressureLevel,
            size(),
            (int) droppedMessageCount.get(),
            (int) delayedMessageCount.get(),
            getAverageProcessingTimeNanos(),
            0  // No persistence queue in this implementation
        );
    }
    
    // BlockingQueue implementation
    
    @Override
    public boolean add(T t) {
        if (offer(t)) {
            return true;
        }
        throw new IllegalStateException("Queue full");
    }
    
    @Override
    public boolean offer(T t) {
        if (t == null) {
            throw new NullPointerException("Cannot add null element to queue");
        }
        
        // Check if we should apply backpressure
        boolean shouldApplyBackpressure = false;
        
        if (size.get() >= capacity) {
            shouldApplyBackpressure = true;
        } else if (backpressureMode == BackpressureMode.ADAPTIVE) {
            // In adaptive mode, we may apply backpressure even before the queue is full
            shouldApplyBackpressure = currentBackpressureLevel > 0.8; // Apply at 80% pressure
        }
        
        if (shouldApplyBackpressure) {
            switch (backpressureMode) {
                case BUFFER_THEN_DROP:
                    // Drop the message
                    droppedMessageCount.incrementAndGet();
                    logger.debug("Dropped message due to backpressure: {}", t);
                    return false;
                    
                case BUFFER_THEN_BLOCK:
                    // This should be handled by the offer(T, long, TimeUnit) method
                    // Here we just return false to indicate the message wasn't accepted
                    return false;
                    
                case ADAPTIVE:
                    // In adaptive mode, we decide based on the current backpressure level
                    if (currentBackpressureLevel > 0.95) {
                        // Very high pressure, drop the message
                        droppedMessageCount.incrementAndGet();
                        logger.debug("Dropped message due to high adaptive backpressure: {}", t);
                        return false;
                    } else {
                        // Accept but track as delayed
                        delayedMessageCount.incrementAndGet();
                    }
                    break;
            }
        }
        
        // Add the element to the queue
        boolean added = queue.offer(t);
        if (added) {
            size.incrementAndGet();
            signalNotEmpty();
        }
        return added;
    }
    
    @Override
    public T remove() {
        T x = poll();
        if (x != null) {
            return x;
        }
        throw new NoSuchElementException();
    }
    
    @Override
    public T poll() {
        T item = queue.poll();
        if (item != null) {
            size.decrementAndGet();
            processedMessageCount.incrementAndGet();
            signalNotFull();
        }
        return item;
    }
    
    @Override
    public T element() {
        T x = peek();
        if (x != null) {
            return x;
        }
        throw new NoSuchElementException();
    }
    
    @Override
    public T peek() {
        return queue.peek();
    }
    
    @Override
    public void put(T t) throws InterruptedException {
        if (t == null) {
            throw new NullPointerException();
        }
        
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (size.get() >= capacity) {
                notFull.await();
            }
            
            offer(t);
        } finally {
            lock.unlock();
        }
    }
    
    @Override
    public boolean offer(T t, long timeout, TimeUnit unit) throws InterruptedException {
        if (t == null) {
            throw new NullPointerException();
        }
        
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (size.get() >= capacity) {
                if (nanos <= 0) {
                    return false;
                }
                nanos = notFull.awaitNanos(nanos);
            }
            
            return offer(t);
        } finally {
            lock.unlock();
        }
    }
    
    @Override
    public T take() throws InterruptedException {
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (queue.isEmpty()) {
                notEmpty.await();
            }
            
            return poll();
        } finally {
            lock.unlock();
        }
    }
    
    @Override
    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (queue.isEmpty()) {
                if (nanos <= 0) {
                    return null;
                }
                nanos = notEmpty.awaitNanos(nanos);
            }
            
            return poll();
        } finally {
            lock.unlock();
        }
    }
    
    @Override
    public int remainingCapacity() {
        return capacity - size.get();
    }
    
    @Override
    public int drainTo(Collection<? super T> c) {
        return drainTo(c, Integer.MAX_VALUE);
    }
    
    @Override
    public int drainTo(Collection<? super T> c, int maxElements) {
        if (c == null) {
            throw new NullPointerException();
        }
        if (c == this) {
            throw new IllegalArgumentException();
        }
        if (maxElements <= 0) {
            return 0;
        }
        
        int n = 0;
        T item;
        while (n < maxElements && (item = poll()) != null) {
            c.add(item);
            n++;
        }
        return n;
    }
    
    @Override
    public void clear() {
        queue.clear();
        size.set(0);
        signalNotFull();
    }
    
    @Override
    public int size() {
        return size.get();
    }
    
    @Override
    public boolean isEmpty() {
        return queue.isEmpty();
    }
    
    @Override
    public boolean contains(Object o) {
        return queue.contains(o);
    }
    
    @Override
    public Iterator<T> iterator() {
        return queue.iterator();
    }
    
    @Override
    public Object[] toArray() {
        return queue.toArray();
    }
    
    @Override
    public <T1> T1[] toArray(T1[] a) {
        return queue.toArray(a);
    }
    
    @Override
    public boolean remove(Object o) {
        boolean removed = queue.remove(o);
        if (removed) {
            size.decrementAndGet();
            signalNotFull();
        }
        return removed;
    }
    
    @Override
    public boolean containsAll(Collection<?> c) {
        return queue.containsAll(c);
    }
    
    @Override
    public boolean addAll(Collection<? extends T> c) {
        if (c == null) {
            throw new NullPointerException();
        }
        if (c == this) {
            throw new IllegalArgumentException();
        }
        
        boolean modified = false;
        for (T e : c) {
            if (add(e)) {
                modified = true;
            }
        }
        return modified;
    }
    
    @Override
    public boolean removeAll(Collection<?> c) {
        boolean modified = queue.removeAll(c);
        if (modified) {
            size.set(queue.size());
            signalNotFull();
        }
        return modified;
    }
    
    @Override
    public boolean retainAll(Collection<?> c) {
        boolean modified = queue.retainAll(c);
        if (modified) {
            size.set(queue.size());
            signalNotFull();
        }
        return modified;
    }
    
    // Flow.Subscriber implementation
    
    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        if (this.subscription != null) {
            subscription.cancel();
            return;
        }
        
        this.subscription = subscription;
        subscription.request(remainingCapacity());
    }
    
    @Override
    public void onNext(T item) {
        if (offer(item)) {
            // Request another item if we have capacity
            if (remainingCapacity() > 0) {
                subscription.request(1);
            }
        } else {
            // If we couldn't accept the item, don't request more
            logger.debug("Could not accept item from publisher due to backpressure");
        }
    }
    
    @Override
    public void onError(Throwable throwable) {
        logger.error("Error in publisher", throwable);
    }
    
    @Override
    public void onComplete() {
        logger.debug("Publisher completed");
    }
    
    // Helper methods
    
    private void signalNotEmpty() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }
    
    private void signalNotFull() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            notFull.signal();
        } finally {
            lock.unlock();
        }
        
        // If we have a subscription, request more items
        if (subscription != null && remainingCapacity() > 0) {
            subscription.request(remainingCapacity());
        }
    }
}
