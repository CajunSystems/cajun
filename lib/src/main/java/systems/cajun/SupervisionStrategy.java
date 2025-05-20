package systems.cajun;

/**
 * Supervision strategies for handling actor failures.
 */
public enum SupervisionStrategy {
    /**
     * Resume processing the next message, ignoring the failure.
     */
    RESUME,

    /**
     * Restart the actor, then continue processing messages.
     */
    RESTART,

    /**
     * Stop the actor.
     */
    STOP,

    /**
     * Escalate the failure to the parent/system.
     */
    ESCALATE
}