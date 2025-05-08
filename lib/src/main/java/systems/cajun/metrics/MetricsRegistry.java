package systems.cajun.metrics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Registry for actor metrics.
 * This class provides a central registry for all actor metrics in the system.
 */
public class MetricsRegistry {
    // Map of actor ID to metrics
    private static final Map<String, ActorMetrics> actorMetricsMap = new ConcurrentHashMap<>();
    
    // Private constructor to prevent instantiation
    private MetricsRegistry() {
    }
    
    /**
     * Registers actor metrics with the registry.
     *
     * @param actorId The ID of the actor
     * @param metrics The metrics to register
     */
    public static void registerActorMetrics(String actorId, ActorMetrics metrics) {
        actorMetricsMap.put(actorId, metrics);
        System.out.println("Registered metrics for actor " + actorId);
    }
    
    /**
     * Unregisters actor metrics from the registry.
     *
     * @param actorId The ID of the actor
     */
    public static void unregisterActorMetrics(String actorId) {
        actorMetricsMap.remove(actorId);
        System.out.println("Unregistered metrics for actor " + actorId);
    }
    
    /**
     * Gets the metrics for an actor.
     *
     * @param actorId The ID of the actor
     * @return The metrics for the actor, or null if not found
     */
    public static ActorMetrics getActorMetrics(String actorId) {
        return actorMetricsMap.get(actorId);
    }
    
    /**
     * Gets all actor metrics in the registry.
     *
     * @return A map of actor ID to metrics
     */
    public static Map<String, ActorMetrics> getAllActorMetrics() {
        return new ConcurrentHashMap<>(actorMetricsMap);
    }
    
    /**
     * Applies a function to all actor metrics in the registry.
     *
     * @param consumer The function to apply
     */
    public static void forEachActorMetrics(Consumer<ActorMetrics> consumer) {
        actorMetricsMap.values().forEach(consumer);
    }
    
    /**
     * Gets the number of actors with registered metrics.
     *
     * @return The number of actors
     */
    public static int getActorCount() {
        return actorMetricsMap.size();
    }
    
    /**
     * Gets the total number of messages processed by all actors.
     *
     * @return The total message count
     */
    public static long getTotalMessageCount() {
        return actorMetricsMap.values().stream()
                .mapToLong(ActorMetrics::getMessageCount)
                .sum();
    }
    
    /**
     * Gets the total number of state changes across all actors.
     *
     * @return The total state change count
     */
    public static long getTotalStateChangeCount() {
        return actorMetricsMap.values().stream()
                .mapToLong(ActorMetrics::getStateChangeCount)
                .sum();
    }
    
    /**
     * Gets the total number of snapshots taken across all actors.
     *
     * @return The total snapshot count
     */
    public static long getTotalSnapshotCount() {
        return actorMetricsMap.values().stream()
                .mapToLong(ActorMetrics::getSnapshotCount)
                .sum();
    }
    
    /**
     * Gets the total number of errors across all actors.
     *
     * @return The total error count
     */
    public static long getTotalErrorCount() {
        return actorMetricsMap.values().stream()
                .mapToLong(ActorMetrics::getErrorCount)
                .sum();
    }
    
    /**
     * Gets the average message processing time across all actors, in nanoseconds.
     *
     * @return The average processing time in nanoseconds
     */
    public static double getAverageProcessingTimeNs() {
        long totalMessages = getTotalMessageCount();
        if (totalMessages == 0) {
            return 0.0;
        }
        
        long totalProcessingTime = actorMetricsMap.values().stream()
                .mapToLong(ActorMetrics::getTotalProcessingTimeNs)
                .sum();
        
        return (double) totalProcessingTime / totalMessages;
    }
    
    /**
     * Gets the average message processing time across all actors, in milliseconds.
     *
     * @return The average processing time in milliseconds
     */
    public static double getAverageProcessingTimeMs() {
        return getAverageProcessingTimeNs() / 1_000_000.0;
    }
    
    /**
     * Gets the actor with the highest message count.
     *
     * @return The actor metrics, or null if no actors are registered
     */
    public static ActorMetrics getActorWithHighestMessageCount() {
        return actorMetricsMap.values().stream()
                .max((a, b) -> Long.compare(a.getMessageCount(), b.getMessageCount()))
                .orElse(null);
    }
    
    /**
     * Gets the actor with the highest error count.
     *
     * @return The actor metrics, or null if no actors are registered
     */
    public static ActorMetrics getActorWithHighestErrorCount() {
        return actorMetricsMap.values().stream()
                .max((a, b) -> Long.compare(a.getErrorCount(), b.getErrorCount()))
                .orElse(null);
    }
    
    /**
     * Gets the actor with the highest average processing time.
     *
     * @return The actor metrics, or null if no actors are registered
     */
    public static ActorMetrics getActorWithHighestAverageProcessingTime() {
        return actorMetricsMap.values().stream()
                .max((a, b) -> Long.compare(a.getAverageProcessingTimeNs(), b.getAverageProcessingTimeNs()))
                .orElse(null);
    }
    
    /**
     * Resets all metrics in the registry.
     * This is primarily useful for testing.
     */
    public static void resetAllMetrics() {
        actorMetricsMap.clear();
        System.out.println("Reset all metrics in the registry");
    }
}
