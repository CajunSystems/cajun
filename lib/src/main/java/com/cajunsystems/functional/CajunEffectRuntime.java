package com.cajunsystems.functional;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.roux.runtime.DefaultEffectRuntime;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A Roux {@link com.cajunsystems.roux.EffectRuntime} tailored for Cajun's stateful actors.
 *
 * <h2>Why a custom runtime instead of the Roux default?</h2>
 * <p>{@link DefaultEffectRuntime#create()} allocates a <em>fresh</em>
 * {@code newVirtualThreadPerTaskExecutor} executor whose lifecycle is completely independent of
 * the actor system. Using that default in {@code StatefulHandlerActor.processMessage()} would:
 * <ul>
 *   <li>Create an orphaned executor per call site with no lifecycle hook into the system</li>
 *   <li>Dispatch forked sub-effects to threads unrelated to the actor system</li>
 *   <li>Lose any actor-system-level monitoring or shutdown coordination</li>
 * </ul>
 *
 * <p>{@code CajunEffectRuntime} instead routes forked effect execution through an executor
 * obtained from the actor system so that:
 * <ul>
 *   <li>The executor lifecycle is tied to the actor system's lifetime</li>
 *   <li>Forked sub-effects participate in the same thread budget as actor processing</li>
 *   <li>A single shared runtime can be reused across all stateful actors in a system
 *       (see {@link #forSystem(ActorSystem)})</li>
 * </ul>
 *
 * <h2>Synchronous execution semantics</h2>
 * <p>{@code unsafeRun(effect)} is <em>synchronous</em>: it blocks the calling thread until the
 * root effect resolves, exactly as {@link DefaultEffectRuntime} does. Since Cajun actors already
 * execute {@code processMessage()} on a virtual thread this is safe and carries no
 * thread-pinning risk. The executor held here is used only when an {@code Effect} explicitly
 * forks a sub-computation (e.g. via {@code effect.fork()} or {@code zipPar}).
 *
 * <h2>Lifecycle</h2>
 * <p>Because {@link DefaultEffectRuntime} implements {@link AutoCloseable} in Roux 0.2.0+,
 * {@code CajunEffectRuntime} tracks whether it <em>owns</em> its executor. When it does, calling
 * {@link #close()} shuts down that executor cleanly. When the actor system's shared executor was
 * injected, {@link #close()} is a no-op — the system is responsible for shutting it down.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Shared instance per ActorSystem — call once, reuse across actors
 * CajunEffectRuntime runtime = CajunEffectRuntime.forSystem(actorSystem);
 *
 * // Inside StatefulHandlerActor.processMessage():
 * Effect<E, State> effect = handler.receive(message, currentState, context);
 * State newState = runtime.unsafeRun(effect);
 * }</pre>
 */
public final class CajunEffectRuntime extends DefaultEffectRuntime {

    /** Whether this instance owns (and should close) its executor. */
    private final boolean ownsExecutor;

    /**
     * Creates a {@code CajunEffectRuntime} bound to the given actor system.
     *
     * <p>If the actor system exposes a shared executor it is used directly and this runtime will
     * <em>not</em> close it on {@link #close()}. Otherwise a dedicated virtual-thread executor is
     * created and <em>will</em> be closed on {@link #close()}.
     *
     * @param system the actor system to bind to
     */
    public CajunEffectRuntime(ActorSystem system) {
        this(resolveExecutor(system), system.getSharedExecutor() == null);
    }

    private CajunEffectRuntime(ExecutorService executor, boolean ownsExecutor) {
        super(executor, /* useTrampoline */ true);
        this.ownsExecutor = ownsExecutor;
    }

    /**
     * Factory — preferred over the constructor for clarity at call sites.
     *
     * @param system the actor system to bind to
     * @return a new {@code CajunEffectRuntime} bound to the given system
     */
    public static CajunEffectRuntime forSystem(ActorSystem system) {
        return new CajunEffectRuntime(system);
    }

    /**
     * Closes this runtime.
     *
     * <p>If this runtime owns its executor (i.e. the actor system had no shared executor), the
     * executor is shut down. Otherwise this is a no-op — the actor system remains responsible for
     * the shared executor's lifecycle.
     */
    @Override
    public void close() {
        if (ownsExecutor) {
            super.close();
        }
        // When using the system's shared executor, do nothing — the system closes it.
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static ExecutorService resolveExecutor(ActorSystem system) {
        ExecutorService shared = system.getSharedExecutor();
        return shared != null ? shared : Executors.newVirtualThreadPerTaskExecutor();
    }
}
