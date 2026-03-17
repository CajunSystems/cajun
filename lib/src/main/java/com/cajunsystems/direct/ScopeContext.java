package com.cajunsystems.direct;

import com.cajunsystems.Actor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;

/**
 * ScopeContext is the user-facing object passed into supervised/scoped blocks.
 * It provides methods to fork tasks on virtual threads, spawn managed actors,
 * and request scope cancellation.
 *
 * @param <T> the result type of forked tasks within this scope
 */
public class ScopeContext<T> {

    private final StructuredTaskScope<T> taskScope;
    private final List<StructuredTaskScope.Subtask<T>> forkedTasks;
    private final List<Actor<?, ?>> managedActors;
    private final AtomicBoolean cancelled;

    public ScopeContext(StructuredTaskScope<T> taskScope) {
        this.taskScope = taskScope;
        this.forkedTasks = Collections.synchronizedList(new ArrayList<>());
        this.managedActors = Collections.synchronizedList(new ArrayList<>());
        this.cancelled = new AtomicBoolean(false);
    }

    /**
     * Fork a task to run on a virtual thread within this scope.
     *
     * @param task the callable to execute
     * @return a Subtask representing the forked computation
     */
    public StructuredTaskScope.Subtask<T> fork(Callable<T> task) {
        if (cancelled.get()) {
            throw new IllegalStateException("Scope has been cancelled");
        }
        StructuredTaskScope.Subtask<T> subtask = taskScope.fork(task);
        forkedTasks.add(subtask);
        return subtask;
    }

    /**
     * Spawn a managed actor within this scope. The actor will be stopped
     * when the scope completes or is cancelled.
     *
     * @param name         the actor name/id
     * @param initialState the initial state for the actor
     * @param behavior     the behavior function (state, message) -> newState
     * @param <S>          the state type
     * @param <M>          the message type
     * @return the spawned Actor
     */
    public <S, M> Actor<S, M> forkActor(String name, S initialState, BiFunction<S, M, S> behavior) {
        if (cancelled.get()) {
            throw new IllegalStateException("Scope has been cancelled");
        }
        Actor<S, M> actor = new Actor<>(name, initialState, behavior);
        managedActors.add(actor);
        return actor;
    }

    /**
     * Request cancellation of this scope. This will shut down the underlying
     * task scope, causing all forked tasks to be interrupted.
     */
    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            taskScope.shutdown();
        }
    }

    /**
     * Returns whether this scope has been cancelled.
     *
     * @return true if cancelled
     */
    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * Returns an unmodifiable view of all forked tasks.
     *
     * @return list of forked subtasks
     */
    public List<StructuredTaskScope.Subtask<T>> getForkedTasks() {
        return Collections.unmodifiableList(new ArrayList<>(forkedTasks));
    }

    /**
     * Returns an unmodifiable view of all managed actors.
     *
     * @return list of managed actors
     */
    public List<Actor<?, ?>> getManagedActors() {
        return Collections.unmodifiableList(new ArrayList<>(managedActors));
    }

    /**
     * Cleanup all managed actors by stopping them. Called by ActorScope
     * when the scope completes or is cancelled.
     */
    public void cleanup() {
        synchronized (managedActors) {
            for (Actor<?, ?> actor : managedActors) {
                try {
                    actor.stop();
                } catch (Exception e) {
                    // Best effort cleanup — log and continue
                }
            }
        }
    }
}