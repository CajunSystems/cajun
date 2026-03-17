package com.cajunsystems.direct;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Translates actor failures into structured concurrency scope failures.
 * Provides supervision strategies for DirectActorRef instances, monitoring them
 * and applying configured supervision decisions when failures occur.
 *
 * <p>Uses virtual threads internally to monitor actors.</p>
 */
public class DirectSupervisor {

    private static final Logger logger = LoggerFactory.getLogger(DirectSupervisor.class);

    /**
     * Supervision decisions that determine how to handle actor failures.
     */
    public enum SupervisionDecision {
        /** Swallow the error and continue processing messages. */
        RESUME,
        /** Recreate the actor and continue. */
        RESTART,
        /** Terminate the actor gracefully. */
        STOP,
        /** Wrap the error in a SupervisionException and propagate to the enclosing scope. */
        ESCALATE
    }

    /**
     * Functional interface for handling supervision decisions.
     *
     * @param <T> the message type of the actor
     */
    @FunctionalInterface
    public interface SupervisionHandler<T> {
        /**
         * Determine the supervision decision for a failed actor.
         *
         * @param actorId   the ID of the actor that failed
         * @param message   the message that caused the failure, or null if unavailable
         * @param exception the exception that was thrown
         * @return the supervision decision
         */
        SupervisionDecision handle(String actorId, T message, Throwable exception);
    }

    /**
     * Exception thrown when a supervision decision is ESCALATE.
     */
    public static class SupervisionException extends RuntimeException {
        private final String actorId;

        public SupervisionException(String actorId, Throwable cause) {
            super("Supervision escalation for actor: " + actorId, cause);
            this.actorId = actorId;
        }

        public String getActorId() {
            return actorId;
        }
    }

    /**
     * Monitors a list of DirectActorRef instances with a single supervision handler.
     * Blocks the calling thread until all actors have stopped or an ESCALATE decision is made.
     *
     * @param actors  the list of actors to supervise
     * @param handler the supervision handler to apply on failure
     * @param <T>     the message type
     * @throws SupervisionException if any actor failure results in an ESCALATE decision
     */
    public static <T> void supervise(List<DirectActorRef<T>> actors, SupervisionHandler<T> handler) {
        if (actors == null || actors.isEmpty()) {
            return;
        }

        AtomicBoolean running = new AtomicBoolean(true);
        CountDownLatch completionLatch = new CountDownLatch(actors.size());
        ConcurrentHashMap<String, DirectActorRef<T>> activeActors = new ConcurrentHashMap<>();

        for (DirectActorRef<T> actor : actors) {
            activeActors.put(actor.getActorId(), actor);
        }

        List<Thread> monitorThreads = new CopyOnWriteArrayList<>();

        for (DirectActorRef<T> actor : actors) {
            Thread monitor = Thread.ofVirtual()
                    .name("supervisor-monitor-" + actor.getActorId())
                    .start(() -> monitorActor(actor, handler, activeActors, running, completionLatch));
            monitorThreads.add(monitor);
        }

        try {
            completionLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running.set(false);
            for (DirectActorRef<T> actor : activeActors.values()) {
                actor.stop();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> void monitorActor(
            DirectActorRef<T> actor,
            SupervisionHandler<T> handler,
            ConcurrentHashMap<String, DirectActorRef<T>> activeActors,
            AtomicBoolean running,
            CountDownLatch completionLatch) {
        String actorId = actor.getActorId();
        try {
            while (running.get() && activeActors.containsKey(actorId)) {
                Throwable failure = actor.getFailure();
                if (failure != null) {
                    T failedMessage = actor.getLastFailedMessage();
                    SupervisionDecision decision = handler.handle(actorId, failedMessage, failure);
                    logger.debug("Supervision decision for actor {}: {}", actorId, decision);

                    switch (decision) {
                        case RESUME:
                            actor.clearFailure();
                            actor.resume();
                            break;
                        case RESTART:
                            actor.restart();
                            break;
                        case STOP:
                            actor.stop();
                            activeActors.remove(actorId);
                            completionLatch.countDown();
                            return;
                        case ESCALATE:
                            running.set(false);
                            // Stop all active actors
                            for (DirectActorRef<T> a : activeActors.values()) {
                                a.stop();
                            }
                            // Count down for all remaining actors
                            long remaining = completionLatch.getCount();
                            for (long i = 0; i < remaining; i++) {
                                completionLatch.countDown();
                            }
                            throw new SupervisionException(actorId, failure);
                        default:
                            break;
                    }
                } else {
                    // Check if the actor has naturally stopped
                    if (!actor.isRunning()) {
                        activeActors.remove(actorId);
                        completionLatch.countDown();
                        return;
                    }
                }
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } catch (SupervisionException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Unexpected error in supervision monitor for actor {}", actorId, e);
        } finally {
            if (activeActors.remove(actorId) != null) {
                completionLatch.countDown();
            }
        }
    }

    /**
     * Creates a new builder for configuring supervision per actor or per group.
     *
     * @param <T> the message type
     * @return a new SupervisionBuilder
     */
    public static <T> SupervisionBuilder<T> builder() {
        return new SupervisionBuilder<>();
    }

    /**
     * Builder-style API for configuring supervision per actor or per group.
     *
     * @param <T> the message type
     */
    public static class SupervisionBuilder<T> {

        private final List<DirectActorRef<T>> actors = new ArrayList<>();
        private final Map<String, SupervisionHandler<T>> perActorHandlers = new HashMap<>();
        private SupervisionHandler<T> defaultHandler;

        SupervisionBuilder() {
        }

        /**
         * Add an actor to be supervised with the default handler.
         *
         * @param actor the actor to supervise
         * @return this builder
         */
        public SupervisionBuilder<T> supervise(DirectActorRef<T> actor) {
            actors.add(actor);
            return this;
        }

        /**
         * Add an actor to be supervised with a specific handler.
         *
         * @param actor   the actor to supervise
         * @param handler the supervision handler for this actor
         * @return this builder
         */
        public SupervisionBuilder<T> supervise(DirectActorRef<T> actor, SupervisionHandler<T> handler) {
            actors.add(actor);
            perActorHandlers.put(actor.getActorId(), handler);
            return this;
        }

        /**
         * Add a group of actors to be supervised with the default handler.
         *
         * @param actorGroup the actors to supervise
         * @return this builder
         */
        public SupervisionBuilder<T> superviseAll(List<DirectActorRef<T>> actorGroup) {
            actors.addAll(actorGroup);
            return this;
        }

        /**
         * Add a group of actors to be supervised with a specific handler.
         *
         * @param actorGroup the actors to supervise
         * @param handler    the supervision handler for this group
         * @return this builder
         */
        public SupervisionBuilder<T> superviseAll(List<DirectActorRef<T>> actorGroup, SupervisionHandler<T> handler) {
            for (DirectActorRef<T> actor : actorGroup) {
                actors.add(actor);
                perActorHandlers.put(actor.getActorId(), handler);
            }
            return this;
        }

        /**
         * Set the default supervision handler for actors that don't have a specific handler.
         *
         * @param handler the default handler
         * @return this builder
         */
        public SupervisionBuilder<T> withDefaultHandler(SupervisionHandler<T> handler) {
            this.defaultHandler = handler;
            return this;
        }

        /**
         * Start supervision. Blocks until all actors have stopped or an ESCALATE decision is made.
         *
         * @throws SupervisionException     if any actor failure results in an ESCALATE decision
         * @throws IllegalStateException    if no default handler is set and an actor has no specific handler
         */
        public void start() {
            if (actors.isEmpty()) {
                return;
            }

            // Create a composite handler that delegates to per-actor or default handler
            SupervisionHandler<T> compositeHandler = (actorId, message, exception) -> {
                SupervisionHandler<T> handler = perActorHandlers.get(actorId);
                if (handler != null) {
                    return handler.handle(actorId, message, exception);
                }
                if (defaultHandler != null) {
                    return defaultHandler.handle(actorId, message, exception);
                }
                throw new IllegalStateException(
                        "No supervision handler configured for actor: " + actorId);
            };

            DirectSupervisor.supervise(actors, compositeHandler);
        }
    }
}