package com.cajunsystems;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Centralizes supervision logic for handling actor and child errors according to the configured strategy.
 */
public final class Supervisor {
    private static final Logger logger = LoggerFactory.getLogger(Supervisor.class);

    private Supervisor() {
    }

    /**
     * Handles an exception thrown during message processing of an actor.
     * Delegates to the actor's supervision strategy (RESUME, RESTART, STOP, ESCALATE).
     *
     * @param actor    The actor that experienced the error
     * @param message  The message being processed when the error occurred
     * @param exception The exception that was thrown
     * @param <T>      The actor's message type
     */
    public static <T> void handleException(Actor<T> actor, T message, Throwable exception) {
        boolean shouldReprocess = actor.onError(message, exception);
        switch (actor.getSupervisionStrategy()) {
            case RESUME -> {
                logger.debug("Actor {} resuming after error", actor.getActorId());
            }
            case RESTART -> {
                logger.info("Restarting actor {}", actor.getActorId());
                // Schedule restart asynchronously to avoid ConcurrentModificationException
                // during batch processing
                Thread.ofVirtual().start(() -> {
                    actor.stop();
                    actor.start();
                    if (shouldReprocess) {
                        actor.tell(message);
                    }
                });
            }
            case STOP -> {
                logger.info("Stopping actor {} due to error", actor.getActorId());
                actor.stop();
            }
            case ESCALATE -> {
                logger.info("Escalating error from actor {}", actor.getActorId());
                Actor<?> parentRef = actor.getParent();
                actor.stop();
                if (parentRef != null) {
                    handleChildError(parentRef, actor, exception);
                } else {
                    throw new ActorException("Error in actor", exception, actor.getActorId());
                }
            }
        }
    }

    /**
     * Handles an error reported by a child actor, applying the parent's supervision strategy.
     *
     * @param parent    The parent actor handling the child's error
     * @param child     The child actor that experienced the error
     * @param exception The exception from the child actor
     */
    public static void handleChildError(Actor<?> parent, Actor<?> child, Throwable exception) {
        logger.info("Actor {} handling error from child {}", parent.getActorId(), child.getActorId());
        parent.removeChild(child.getActorId());
        switch (parent.getSupervisionStrategy()) {
            case RESUME -> {
                logger.debug("Actor {} allowing child {} to resume after error", parent.getActorId(), child.getActorId());
                if (!child.isRunning()) {
                    child.start();
                }
                parent.addChild(child);
            }
            case RESTART -> {
                logger.info("Actor {} restarting child {} after error", parent.getActorId(), child.getActorId());
                if (child.isRunning()) {
                    child.stop();
                }
                child.start();
                parent.addChild(child);
            }
            case STOP -> {
                logger.info("Actor {} confirming stop of child {} due to error", parent.getActorId(), child.getActorId());
                if (child.isRunning()) {
                    child.stop();
                }
            }
            case ESCALATE -> {
                logger.info("Actor {} escalating error from child {}", parent.getActorId(), child.getActorId());
                if (child.isRunning()) {
                    child.stop();
                }
                Actor<?> grandParent = parent.getParent();
                if (grandParent != null) {
                    handleChildError(grandParent, parent, exception);
                } else {
                    throw new ActorException("Error in child actor", exception, child.getActorId());
                }
            }
        }
    }
}
