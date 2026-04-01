package com.cajunsystems.functional.capabilities;

import com.cajunsystems.roux.capability.Capability;
import com.cajunsystems.roux.data.Unit;

/**
 * Logging capability sealed interface for the Roux effect system.
 *
 * <p>Implementations are pure data — they describe logging operations without
 * performing them. A {@link ConsoleLogHandler} (or custom handler) interprets them.
 *
 * <p>Usage via {@code Effect.from()}:
 * <pre>{@code
 * Effect<RuntimeException, Unit> effect = Effect.from(new LogCapability.Info("hello"));
 * runtime.unsafeRunWithHandler(effect, new ConsoleLogHandler().widen());
 * }</pre>
 *
 * <p>Usage inside {@code Effect.generate()}:
 * <pre>{@code
 * Effect<RuntimeException, Unit> effect = Effect.generate(
 *     ctx -> {
 *         ctx.perform(new LogCapability.Info("step 1"));
 *         ctx.perform(new LogCapability.Warn("step 2"));
 *         return Unit.unit();
 *     },
 *     new ConsoleLogHandler().widen()
 * );
 * runtime.unsafeRun(effect);
 * }</pre>
 */
public sealed interface LogCapability extends Capability<Unit>
        permits LogCapability.Info, LogCapability.Debug, LogCapability.Warn, LogCapability.Error {

    /** Log an informational message. */
    record Info(String message) implements LogCapability {}

    /** Log a debug message. */
    record Debug(String message) implements LogCapability {}

    /** Log a warning message. */
    record Warn(String message) implements LogCapability {}

    /** Log an error message with an optional cause. */
    record Error(String message, Throwable cause) implements LogCapability {
        /** Convenience constructor for errors without a cause. */
        public Error(String message) {
            this(message, null);
        }
    }
}
