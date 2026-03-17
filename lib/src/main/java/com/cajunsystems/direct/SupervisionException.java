package com.cajunsystems.direct;

/**
 * A custom exception class that wraps actor failures with structured context.
 * Extends RuntimeException so it can propagate through virtual thread boundaries
 * without checked exception noise.
 */
public class SupervisionException extends RuntimeException {

    private final String actorId;
    private final Throwable originalCause;
    private final SupervisionStrategy supervisionStrategy;

    /**
     * Creates a new SupervisionException with full context.
     *
     * @param actorId             the identifier of the failed actor
     * @param originalCause       the original throwable that caused the failure
     * @param supervisionStrategy the supervision strategy that was in effect
     */
    public SupervisionException(String actorId, Throwable originalCause, SupervisionStrategy supervisionStrategy) {
        super("Actor '" + actorId + "' failed with strategy " + supervisionStrategy + ": " + originalCause.getMessage(),
                originalCause);
        this.actorId = actorId;
        this.originalCause = originalCause;
        this.supervisionStrategy = supervisionStrategy;
    }

    /**
     * Creates a new SupervisionException with a custom message.
     *
     * @param message             a custom message describing the failure
     * @param actorId             the identifier of the failed actor
     * @param originalCause       the original throwable that caused the failure
     * @param supervisionStrategy the supervision strategy that was in effect
     */
    public SupervisionException(String message, String actorId, Throwable originalCause,
                                SupervisionStrategy supervisionStrategy) {
        super(message, originalCause);
        this.actorId = actorId;
        this.originalCause = originalCause;
        this.supervisionStrategy = supervisionStrategy;
    }

    /**
     * Returns the identifier of the failed actor.
     *
     * @return the actor id
     */
    public String getActorId() {
        return actorId;
    }

    /**
     * Returns the original throwable that caused the failure.
     *
     * @return the original cause
     */
    public Throwable getOriginalCause() {
        return originalCause;
    }

    /**
     * Returns the supervision strategy that was in effect when the failure occurred.
     *
     * @return the supervision strategy
     */
    public SupervisionStrategy getSupervisionStrategy() {
        return supervisionStrategy;
    }
}