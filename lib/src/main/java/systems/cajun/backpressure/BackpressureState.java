package systems.cajun.backpressure;

/**
 * Represents the different states of backpressure that an actor can experience.
 * This provides more granular information than a simple boolean flag.
 */
public enum BackpressureState {
    /**
     * The actor is operating normally with sufficient capacity.
     */
    NORMAL,
    
    /**
     * The actor is approaching its capacity limits but not yet applying backpressure.
     * This is a warning state that indicates potential future backpressure.
     */
    WARNING,
    
    /**
     * The actor is at or above its high watermark and actively applying backpressure.
     */
    CRITICAL,
    
    /**
     * The actor was recently in a CRITICAL state but is now recovering.
     * It's below the high watermark but still above the low watermark.
     */
    RECOVERY
}
