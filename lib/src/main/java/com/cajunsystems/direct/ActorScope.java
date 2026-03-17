package com.cajunsystems.direct;

import com.cajunsystems.ActorRef;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.StructuredTaskScope;
import java.util.function.Consumer;

/**
 * ActorScope wraps Java 21's StructuredTaskScope to manage the lifecycle of virtual threads
 * and actors spawned within a scope.
 *
 * <p>Supports two modes:
 * <ul>
 *   <li><b>supervised</b> (ShutdownOnFailure) - cancels all tasks on first error</li>
 *   <li><b>scoped</b> - waits for all tasks to complete</li>
 * </ul>
 *
 * <p>Implements AutoCloseable for use with try-with-resources.
 */
public class ActorScope implements AutoCloseable {

    private final StructuredTaskScope<Object> scope;
    private final List<ActorRef<?>> managedActors;
    private final boolean supervised;

    /**
     * Creates a new ActorScope.
     *
     * @param supervised if true, uses ShutdownOnFailure semantics (cancels all on first error);
     *                   if false, waits for all tasks to complete
     */
    public ActorScope(boolean supervised) {
        this.supervised = supervised;
        this.managedActors = new CopyOnWriteArrayList<>();
        if (supervised) {
            this.scope = new StructuredTaskScope.ShutdownOnFailure();
        } else {
            this.scope = new StructuredTaskScope<>();
        }
    }

    /**
     * Creates a supervised ActorScope (ShutdownOnFailure).
     *
     * @return a new supervised ActorScope
     */
    public static ActorScope supervised() {
        return new ActorScope(true);
    }

    /**
     * Creates a scoped ActorScope that waits for all tasks.
     *
     * @return a new scoped ActorScope
     */
    public static ActorScope scoped() {
        return new ActorScope(false);
    }

    /**
     * Executes a block with a ScopeContext, managing the full lifecycle.
     * The scope is joined and closed after the block completes.
     *
     * @param block the consumer that receives a ScopeContext to fork tasks and register actors
     * @throws Exception if the block or any forked task throws an exception
     */
    public void run(Consumer<ScopeContext> block) throws Exception {
        ScopeContext context = new ScopeContext(this);
        try {
            block.accept(context);
            joinScope();
        } finally {
            close();
        }
    }

    /**
     * Submits work to the underlying StructuredTaskScope as a forked subtask.
     *
     * @param callable the work to execute
     * @param <T>      the return type of the callable
     * @return a StructuredTaskScope.Subtask representing the forked work
     */
    @SuppressWarnings("unchecked")
    public <T> StructuredTaskScope.Subtask<T> fork(Callable<T> callable) {
        return (StructuredTaskScope.Subtask<T>) scope.fork(() -> {
            T result = callable.call();
            return (Object) result;
        });
    }

    /**
     * Registers an actor for cleanup when this scope is closed.
     *
     * @param actorRef the actor reference to track
     * @param <T>      the message type of the actor
     */
    public <T> void registerActor(ActorRef<T> actorRef) {
        managedActors.add(actorRef);
    }

    /**
     * Joins the underlying scope, waiting for tasks to complete.
     * In supervised mode, also checks for and rethrows any task failures.
     *
     * @throws Exception if joining fails or a supervised task threw an exception
     */
    private void joinScope() throws Exception {
        scope.join();
        if (supervised && scope instanceof StructuredTaskScope.ShutdownOnFailure failureScope) {
            failureScope.throwIfFailed();
        }
    }

    /**
     * Shuts down all managed actors and closes the underlying scope.
     */
    @Override
    public void close() {
        // Shut down all managed actors in reverse registration order
        for (int i = managedActors.size() - 1; i >= 0; i--) {
            try {
                managedActors.get(i).stop();
            } catch (Exception e) {
                // Best effort cleanup
            }
        }
        managedActors.clear();
        scope.close();
    }

    /**
     * Returns whether this scope is in supervised mode.
     *
     * @return true if supervised, false otherwise
     */
    public boolean isSupervised() {
        return supervised;
    }
}