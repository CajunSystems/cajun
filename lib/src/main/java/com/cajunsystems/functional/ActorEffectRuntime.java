package com.cajunsystems.functional;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.roux.runtime.DefaultEffectRuntime;

import java.util.concurrent.ExecutorService;

/**
 * A Roux EffectRuntime backed by a Cajun ActorSystem's executor.
 *
 * <p>Unlike DefaultEffectRuntime#create() which allocates a fresh virtual-thread pool,
 * ActorEffectRuntime dispatches all effect execution through the actor system's executor,
 * integrating effect lifecycle with actor system lifecycle.
 *
 * <p>If the system has a shared executor enabled it is used directly; otherwise a dedicated
 * executor is created from the system's ThreadPoolFactory and owned by this runtime.
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>Shared executor path — {@link #close()} is a no-op; the executor is owned by
 *       {@link ActorSystem} and cleaned up by {@link ActorSystem#shutdown()}.
 *   <li>Dedicated executor path — {@link #close()} shuts down the executor created for
 *       this runtime. {@link EffectActorBuilder} calls {@code close()} automatically via
 *       the actor's {@code postStop} hook.
 * </ul>
 *
 * <pre>{@code
 * ActorSystem system = new ActorSystem();
 * ActorEffectRuntime runtime = new ActorEffectRuntime(system);
 *
 * Effect<RuntimeException, String> greet = Effect.succeed("hello from actor system");
 * String result = runtime.unsafeRun(greet);
 * }</pre>
 */
public class ActorEffectRuntime extends DefaultEffectRuntime {

    private final boolean ownsExecutor;

    /**
     * Creates an ActorEffectRuntime that dispatches effects through the given
     * actor system's executor.
     *
     * <p>If the system has a shared executor, it is used directly and this runtime
     * does not own its lifecycle. Otherwise a dedicated executor is created and
     * owned by this runtime — it will be shut down when {@link #close()} is called.
     *
     * @param system the actor system whose executor will run effects
     */
    public ActorEffectRuntime(ActorSystem system) {
        this(system, system.getSharedExecutor());
    }

    private ActorEffectRuntime(ActorSystem system, ExecutorService shared) {
        super(
            shared != null ? shared
                           : system.getThreadPoolFactory().createExecutorService("actor-effect-runtime"),
            true  // useTrampoline
        );
        this.ownsExecutor = (shared == null);
    }

    /**
     * Closes this runtime.
     *
     * <p>If this runtime owns a dedicated executor (created because no shared executor
     * was configured), the executor is shut down. If the runtime uses the actor system's
     * shared executor, this method is a no-op — the shared executor's lifecycle belongs
     * to {@link ActorSystem}.
     */
    @Override
    public void close() {
        if (ownsExecutor) {
            super.close();
        }
        // else: executor lifecycle belongs to ActorSystem.
    }
}
