package systems.cajun;

import java.util.AbstractQueue;
import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A blocking queue implementation that can be resized at runtime.
 * This queue is backed by an ArrayBlockingQueue and provides thread-safe
 * resizing operations by creating a new backing queue when needed.
 * 
 * @param <E> the type of elements held in this queue
 */
public class ResizableBlockingQueue<E> extends AbstractQueue<E> implements BlockingQueue<E> {
    
    private final int maxCapacity;
    private BlockingQueue<E> delegate;
    private final Object resizeLock = new Object();
    
    // Default values for resize behavior
    private float resizeThreshold = 0.75f; // Resize when queue is 75% full
    private float resizeFactor = 2.0f;    // Double the size when resizing
    
    /**
     * Creates a new resizable blocking queue with the specified initial capacity
     * and maximum capacity.
     * 
     * @param initialCapacity the initial capacity of the queue
     * @param maxCapacity the maximum capacity the queue can grow to
     */
    public ResizableBlockingQueue(int initialCapacity, int maxCapacity) {
        this.maxCapacity = maxCapacity;
        this.delegate = new ArrayBlockingQueue<>(initialCapacity);
    }
    
    /**
     * Resizes the queue to a new capacity.
     * This operation is thread-safe but may temporarily block producers.
     * 
     * @param newCapacity The new capacity for the queue
     */
    public void resize(int newCapacity) {
        if (newCapacity <= 0 || newCapacity > maxCapacity) {
            throw new IllegalArgumentException("New capacity must be between 1 and " + maxCapacity);
        }
        
        synchronized (resizeLock) {
            int currentSize = delegate.size();
            if (newCapacity < currentSize) {
                // Can't resize smaller than current content
                return;
            }
            
            // Create a new backing queue with the desired capacity
            BlockingQueue<E> newQueue = new ArrayBlockingQueue<>(newCapacity);
            
            // Transfer all elements to the new queue
            delegate.drainTo(newQueue);
            
            // Replace the delegate
            delegate = newQueue;
        }
    }
    
    /**
     * Gets the current capacity of the queue.
     * 
     * @return The current capacity
     */
    public int getCapacity() {
        return delegate.remainingCapacity() + delegate.size();
    }
    
    /**
     * Sets the resize threshold for this queue. When the queue reaches this threshold
     * of capacity, it will attempt to resize automatically during offer operations.
     * 
     * @param resizeThreshold The resize threshold as a fraction between 0 and 1
     */
    public void setResizeThreshold(float resizeThreshold) {
        if (resizeThreshold <= 0 || resizeThreshold >= 1) {
            throw new IllegalArgumentException("Resize threshold must be between 0 and 1");
        }
        this.resizeThreshold = resizeThreshold;
    }
    
    /**
     * Gets the resize threshold for this queue.
     * 
     * @return The resize threshold
     */
    public float getResizeThreshold() {
        return resizeThreshold;
    }
    
    /**
     * Sets the resize factor for this queue. When the queue resizes, it will
     * multiply its capacity by this factor.
     * 
     * @param resizeFactor The resize factor (must be greater than 1)
     */
    public void setResizeFactor(float resizeFactor) {
        if (resizeFactor <= 1) {
            throw new IllegalArgumentException("Resize factor must be greater than 1");
        }
        this.resizeFactor = resizeFactor;
    }
    
    /**
     * Gets the resize factor for this queue.
     * 
     * @return The resize factor
     */
    public float getResizeFactor() {
        return resizeFactor;
    }
    
    // BlockingQueue implementation methods
    
    @Override
    public boolean add(E e) {
        return delegate.add(e);
    }
    
    @Override
    public boolean offer(E e) {
        // Check if we need to resize the queue
        synchronized (resizeLock) {
            int capacity = getCapacity();
            int size = delegate.size();
            float fillRatio = (float) size / capacity;
            
            // If the queue is getting full and we're not at max capacity, resize it
            if (fillRatio >= resizeThreshold && capacity < maxCapacity) {
                int newCapacity = Math.min(maxCapacity, (int) (capacity * resizeFactor));
                if (newCapacity > capacity) {
                    resize(newCapacity);
                }
            }
        }
        
        return delegate.offer(e);
    }
    
    @Override
    public void put(E e) throws InterruptedException {
        delegate.put(e);
    }
    
    @Override
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.offer(e, timeout, unit);
    }
    
    @Override
    public E take() throws InterruptedException {
        return delegate.take();
    }
    
    @Override
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.poll(timeout, unit);
    }
    
    @Override
    public int remainingCapacity() {
        return delegate.remainingCapacity();
    }
    
    @Override
    public boolean remove(Object o) {
        return delegate.remove(o);
    }
    
    @Override
    public boolean contains(Object o) {
        return delegate.contains(o);
    }
    
    @Override
    public int drainTo(Collection<? super E> c) {
        return delegate.drainTo(c);
    }
    
    @Override
    public int drainTo(Collection<? super E> c, int maxElements) {
        return delegate.drainTo(c, maxElements);
    }
    
    // Queue implementation methods
    
    @Override
    public E remove() {
        return delegate.remove();
    }
    
    @Override
    public E poll() {
        return delegate.poll();
    }
    
    @Override
    public E element() {
        return delegate.element();
    }
    
    @Override
    public E peek() {
        return delegate.peek();
    }
    
    @Override
    public int size() {
        return delegate.size();
    }
    
    @Override
    public boolean isEmpty() {
        return delegate.isEmpty();
    }
    
    @Override
    public Iterator<E> iterator() {
        return delegate.iterator();
    }
    
    @Override
    public Object[] toArray() {
        return delegate.toArray();
    }
    
    @Override
    public <T> T[] toArray(T[] a) {
        return delegate.toArray(a);
    }
    
    @Override
    public boolean containsAll(Collection<?> c) {
        return delegate.containsAll(c);
    }
    
    @Override
    public boolean addAll(Collection<? extends E> c) {
        return delegate.addAll(c);
    }
    
    @Override
    public boolean removeAll(Collection<?> c) {
        return delegate.removeAll(c);
    }
    
    @Override
    public boolean retainAll(Collection<?> c) {
        return delegate.retainAll(c);
    }
    
    @Override
    public void clear() {
        delegate.clear();
    }
}
