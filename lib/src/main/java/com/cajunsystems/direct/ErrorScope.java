package com.cajunsystems.direct;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.StructuredTaskScope;
import java.util.function.Function;

/**
 * Provides try-catch style error boundaries around actor groups in direct style.
 * <p>
 * ErrorScope manages the lifecycle of actors created within the scope, ensuring
 * all are cleaned up on error. It supports nested scopes that escalate to parent
 * on unhandled errors.
 * <p>
 * Inspired by Ox for Scala's structured error handling patterns.
 */
public class ErrorScope {

    /**
     * Functional interface for the body of an error scope.
     */
    @FunctionalInterface
    public interface ErrorScopeBody {
        void run() throws Exception;
    }

    /**
     * Functional interface for recovery logic when a SupervisionException occurs.
     */
    @FunctionalInterface
    public interface RecoveryHandler {
        Object recover(SupervisionException e);
    }

    private final ErrorScope parent;
    private final List<ErrorScope> children;
    private final List<AutoCloseable> resources;
    private volatile boolean failed;
    private volatile SupervisionException failure;

    /**
     * Creates a root error scope with no parent.
     */
    public ErrorScope() {
        this(null);
    }

    /**
     * Creates a child error scope with the given parent.
     *
     * @param parent the parent error scope, or null for a root scope
     */
    public ErrorScope(ErrorScope parent) {
        this.parent = parent;
        this.children = Collections.synchronizedList(new ArrayList<>());
        this.resources = Collections.synchronizedList(new ArrayList<>());
        this.failed = false;
        this.failure = null;
    }

    /**
     * Executes the body block within an error scope, catching any SupervisionException.
     * All resources and actors created within the scope are cleaned up on error.
     *
     * @param body the body block to execute
     * @throws SupervisionException if the body throws a SupervisionException and no recovery is defined
     */
    public static void run(ErrorScopeBody body) throws SupervisionException {
        ErrorScope scope = new ErrorScope();
        try (var taskScope = new StructuredTaskScope.ShutdownOnFailure()) {
            taskScope.fork(() -> {
                try {
                    body.run();
                } catch (SupervisionException e) {
                    scope.failed = true;
                    scope.failure = e;
                    throw e;
                } catch (Exception e) {
                    SupervisionException se = new SupervisionException(
                            "Error in scope: " + e.getMessage(),
                            e,
                            SupervisionException.Directive.STOP
                    );
                    scope.failed = true;
                    scope.failure = se;
                    throw se;
                }
                return null;
            });

            try {
                taskScope.join();
                taskScope.throwIfFailed();
            } catch (java.util.concurrent.ExecutionException e) {
                if (e.getCause() instanceof SupervisionException se) {
                    scope.cleanup();
                    throw se;
                }
                SupervisionException se = new SupervisionException(
                        "Scope execution failed: " + e.getMessage(),
                        e.getCause() != null ? e.getCause() : e,
                        SupervisionException.Directive.STOP
                );
                scope.cleanup();
                throw se;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                scope.cleanup();
                throw new SupervisionException(
                        "Scope interrupted",
                        e,
                        SupervisionException.Directive.STOP
                );
            }
        } finally {
            scope.cleanup();
        }
    }

    /**
     * Executes the body block within an error scope, using the provided recovery handler
     * if a SupervisionException occurs.
     *
     * @param body     the body block to execute
     * @param recovery the recovery handler to invoke on SupervisionException
     * @return the result of recovery if an error occurred, or null if the body completed successfully
     */
    public static Object runWithRecovery(ErrorScopeBody body, RecoveryHandler recovery) {
        try {
            run(body);
            return null;
        } catch (SupervisionException e) {
            return recovery.recover(e);
        }
    }

    /**
     * Creates a nested child error scope. If the child scope encounters an unhandled error,
     * it escalates to this parent scope.
     *
     * @return a new child ErrorScope linked to this parent
     */
    public ErrorScope nested() {
        ErrorScope child = new ErrorScope(this);
        children.add(child);
        return child;
    }

    /**
     * Runs the given body within a nested child scope. Unhandled errors escalate to the parent.
     *
     * @param body the body block to execute in the nested scope
     * @throws SupervisionException if the body throws and no recovery is possible
     */
    public void runNested(ErrorScopeBody body) throws SupervisionException {
        ErrorScope child = nested();
        try (var taskScope = new StructuredTaskScope.ShutdownOnFailure()) {
            taskScope.fork(() -> {
                try {
                    body.run();
                } catch (SupervisionException e) {
                    child.failed = true;
                    child.failure = e;
                    throw e;
                } catch (Exception e) {
                    SupervisionException se = new SupervisionException(
                            "Error in nested scope: " + e.getMessage(),
                            e,
                            SupervisionException.Directive.ESCALATE
                    );
                    child.failed = true;
                    child.failure = se;
                    throw se;
                }
                return null;
            });

            try {
                taskScope.join();
                taskScope.throwIfFailed();
            } catch (java.util.concurrent.ExecutionException e) {
                child.cleanup();
                if (e.getCause() instanceof SupervisionException se) {
                    if (se.getDirective() == SupervisionException.Directive.ESCALATE) {
                        this.failed = true;
                        this.failure = se;
                    }
                    throw se;
                }
                SupervisionException se = new SupervisionException(
                        "Nested scope execution failed: " + e.getMessage(),
                        e.getCause() != null ? e.getCause() : e,
                        SupervisionException.Directive.ESCALATE
                );
                this.failed = true;
                this.failure = se;
                throw se;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                child.cleanup();
                SupervisionException se = new SupervisionException(
                        "Nested scope interrupted",
                        e,
                        SupervisionException.Directive.STOP
                );
                throw se;
            }
        } finally {
            child.cleanup();
        }
    }

    /**
     * Registers a resource (e.g., an actor handle) to be cleaned up when this scope ends.
     *
     * @param resource the resource to register
     */
    public void register(AutoCloseable resource) {
        resources.add(resource);
    }

    /**
     * Returns whether this scope has failed.
     *
     * @return true if this scope encountered a failure
     */
    public boolean isFailed() {
        return failed;
    }

    /**
     * Returns the failure that caused this scope to fail, if any.
     *
     * @return the SupervisionException, or null if the scope has not failed
     */
    public SupervisionException getFailure() {
        return failure;
    }

    /**
     * Returns the parent scope, or null if this is a root scope.
     *
     * @return the parent ErrorScope
     */
    public ErrorScope getParent() {
        return parent;
    }

    /**
     * Cleans up all registered resources and child scopes.
     */
    private void cleanup() {
        // Clean up children first
        synchronized (children) {
            for (ErrorScope child : children) {
                child.cleanup();
            }
            children.clear();
        }

        // Clean up registered resources in reverse order
        synchronized (resources) {
            for (int i = resources.size() - 1; i >= 0; i--) {
                try {
                    resources.get(i).close();
                } catch (Exception e) {
                    // Best effort cleanup - log but don't throw
                }
            }
            resources.clear();
        }
    }
}