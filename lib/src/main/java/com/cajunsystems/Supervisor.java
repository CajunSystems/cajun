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
     * Re-registers a restarted child with its actor system.
     * <p>
     * When a child is handled via ESCALATE, {@link Actor#stop()} runs, which removes the actor
     * from the system registry (via {@code ActorSystem.shutdown(actorId)}). When the parent then
     * revives the child with a raw {@code start()}, the actor's mailbox thread runs again but the
     * system can no longer route messages to it, so every subsequent message is silently dropped.
     * Re-registering restores routing. The operation is idempotent for children that were never
     * unregistered (e.g. the RESUME path where the child was still running).
     *
     * @param child The child actor that was (re)started by a supervisor
     */
    private static void reregisterChild(Actor<?> child) {
        ActorSystem system = child.getSystem();
        if (system != null) {
            system.registerActor(child);
        }
    }

    /**
     * Notifies the parent that a child failed, guarding against exceptions thrown by the
     * observation callback so supervision itself is never derailed by observer code.
     */
    private static void notifyChildFailed(Actor<?> parent, Actor<?> child, Throwable cause) {
        try {
            parent.onChildFailed(child, cause);
        } catch (Throwable t) {
            logger.warn("Parent {} onChildFailed observer threw for child {}",
                    parent.getActorId(), child.getActorId(), t);
        }
    }

    /**
     * Notifies the parent that a child was restarted/resumed, guarding against observer
     * exceptions as with {@link #notifyChildFailed}.
     */
    private static void notifyChildRestarted(Actor<?> parent, Actor<?> child) {
        try {
            parent.onChildRestarted(child);
        } catch (Throwable t) {
            logger.warn("Parent {} onChildRestarted observer threw for child {}",
                    parent.getActorId(), child.getActorId(), t);
        }
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
                // Request restart to happen after current batch completes
                // This avoids ConcurrentModificationException during batch processing
                // Preserve mailbox messages during restart (no message loss)
                actor.requestRestart(() -> {
                    actor.stopForRestart();
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
        // Let the parent observe the failure (metrics/alerting/circuit-breaking) before acting.
        notifyChildFailed(parent, child, exception);
        switch (parent.getSupervisionStrategy()) {
            case RESUME -> {
                logger.debug("Actor {} allowing child {} to resume after error", parent.getActorId(), child.getActorId());
                if (!child.isRunning()) {
                    child.start();
                }
                reregisterChild(child);
                parent.addChild(child);
                notifyChildRestarted(parent, child);
            }
            case RESTART -> {
                logger.info("Actor {} restarting child {} after error", parent.getActorId(), child.getActorId());
                if (child.isRunning()) {
                    child.stop();
                }
                child.start();
                reregisterChild(child);
                parent.addChild(child);
                notifyChildRestarted(parent, child);
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
