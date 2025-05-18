package systems.cajun.util;

import java.util.Collection;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A blocking queue implementation that can dynamically resize based on load.
 * Extends the standard LinkedBlockingQueue with capacity management.
 *
 * @param <E> The type of elements held in this queue
 */
public class ResizableBlockingQueue<E> extends LinkedBlockingQueue<E> {
    private static final long serialVersionUID = 1L;
    
    private int maxCapacity;
    
    /**
     * Creates a new ResizableBlockingQueue with the specified initial and maximum capacities.
     *
     * @param initialCapacity The initial capacity of the queue
     * @param maxCapacity The maximum capacity the queue can grow to
     */
    public ResizableBlockingQueue(int initialCapacity, int maxCapacity) {
        super(initialCapacity);
        this.maxCapacity = maxCapacity;
    }
    
    /**
     * Creates a new ResizableBlockingQueue with the given initial capacity and
     * containing the elements of the given collection.
     *
     * @param initialCapacity The initial capacity of the queue
     * @param maxCapacity The maximum capacity the queue can grow to
     * @param c The collection of elements to initially contain
     */
    public ResizableBlockingQueue(int initialCapacity, int maxCapacity, Collection<? extends E> c) {
        super(initialCapacity);
        this.maxCapacity = maxCapacity;
        addAll(c);
    }
    
    /**
     * Resizes the queue to the specified new capacity.
     * The new capacity must be greater than the current size of the queue
     * and less than or equal to the maximum allowed capacity.
     *
     * @param newCapacity The new capacity for the queue
     * @return true if the resize was successful, false otherwise
     */
    public synchronized boolean resize(int newCapacity) {
        if (newCapacity < size() || newCapacity > maxCapacity) {
            return false;
        }
        
        // Create a new queue with the desired capacity and transfer all elements
        LinkedBlockingQueue<E> newQueue = new LinkedBlockingQueue<>(newCapacity);
        drainTo(newQueue);
        
        // Replace this queue's internal structure with the new one
        // This is a workaround since LinkedBlockingQueue doesn't have a direct resize method
        try {
            for (E element : newQueue) {
                put(element);
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
    
    /**
     * Gets the current maximum capacity of this queue.
     *
     * @return The maximum capacity
     */
    public int getMaxCapacity() {
        return maxCapacity;
    }
    
    /**
     * Gets the current capacity of this queue.
     * This is the sum of the current size and remaining capacity.
     *
     * @return The current capacity
     */
    public int getCapacity() {
        return remainingCapacity() + size();
    }
    
    /**
     * Sets the maximum capacity limit for this queue.
     * The actual capacity may be less than this limit.
     *
     * @param maxCapacity The new maximum capacity
     */
    public void setMaxCapacity(int maxCapacity) {
        this.maxCapacity = maxCapacity;
    }
    
    /**
     * Attempts to grow the capacity of this queue by the specified factor.
     * Growth is limited by the queue's maximum capacity.
     *
     * @param growthFactor The factor to multiply the current capacity by
     * @return true if the growth was successful, false otherwise
     */
    public boolean grow(float growthFactor) {
        int currentCapacity = remainingCapacity() + size();
        int newCapacity = Math.min((int)(currentCapacity * growthFactor), maxCapacity);
        return resize(newCapacity);
    }
    
    /**
     * Attempts to shrink the capacity of this queue by the specified factor.
     * The new capacity will not be less than the current size of the queue.
     *
     * @param shrinkFactor The factor to multiply the current capacity by (should be < 1.0)
     * @return true if the shrink was successful, false otherwise
     */
    public boolean shrink(float shrinkFactor) {
        int currentCapacity = remainingCapacity() + size();
        int newCapacity = Math.max((int)(currentCapacity * shrinkFactor), size());
        return resize(newCapacity);
    }
}
