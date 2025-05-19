package systems.cajun.util;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;

/**
 * Utility class for concurrent operations.
 */
public class ConcurrentUtils {
    
    /**
     * Creates a single-threaded scheduled executor service with the specified thread name and daemon status.
     * 
     * @param threadName The name for the thread
     * @param daemon Whether the thread should be a daemon thread
     * @return A new scheduled executor service
     */
    public static ScheduledExecutorService createSingleThreadScheduledExecutor(String threadName, boolean daemon) {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, threadName);
            t.setDaemon(daemon);
            return t;
        });
    }
    
    /**
     * Creates a thread factory that creates threads with the specified name prefix and daemon status.
     * 
     * @param namePrefix The prefix for thread names
     * @param daemon Whether the threads should be daemon threads
     * @return A new thread factory
     */
    public static ThreadFactory createThreadFactory(String namePrefix, boolean daemon) {
        return r -> {
            Thread t = new Thread(r, namePrefix + "-" + System.currentTimeMillis());
            t.setDaemon(daemon);
            return t;
        };
    }
}
