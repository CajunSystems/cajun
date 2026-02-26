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
 * executor is created from the system's ThreadPoolFactory.
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

    /**
     * Creates an ActorEffectRuntime that dispatches effects through the given
     * actor system's executor.
     *
     * @param system the actor system whose executor will run effects
     */
    public ActorEffectRuntime(ActorSystem system) {
        super(resolveExecutor(system), true);
    }

    private static ExecutorService resolveExecutor(ActorSystem system) {
        ExecutorService shared = system.getSharedExecutor();
        return shared != null
                ? shared
                : system.getThreadPoolFactory().createExecutorService("actor-effect-runtime");
    }
}
