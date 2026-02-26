package com.cajunsystems.functional.capabilities;

import com.cajunsystems.roux.capability.CapabilityHandler;
import com.cajunsystems.roux.data.Unit;

/**
 * A {@link CapabilityHandler} for {@link LogCapability} that writes to the console.
 *
 * <ul>
 *   <li>{@link LogCapability.Info} and {@link LogCapability.Debug} &rarr; {@link System#out}
 *   <li>{@link LogCapability.Warn} &rarr; {@link System#out}
 *   <li>{@link LogCapability.Error} &rarr; {@link System#err}; prints stack trace if cause present
 * </ul>
 *
 * <p>To pass to {@link com.cajunsystems.roux.EffectRuntime#unsafeRunWithHandler}, call
 * {@link #widen()} to obtain a {@code CapabilityHandler<Capability<?>>}:
 * <pre>{@code
 * runtime.unsafeRunWithHandler(effect, new ConsoleLogHandler().widen());
 * }</pre>
 */
public class ConsoleLogHandler implements CapabilityHandler<LogCapability> {

    @Override
    @SuppressWarnings("unchecked")
    public <R> R handle(LogCapability capability) {
        return switch (capability) {
            case LogCapability.Info info -> {
                System.out.println("[INFO]  " + info.message());
                yield (R) Unit.INSTANCE;
            }
            case LogCapability.Debug debug -> {
                System.out.println("[DEBUG] " + debug.message());
                yield (R) Unit.INSTANCE;
            }
            case LogCapability.Warn warn -> {
                System.out.println("[WARN]  " + warn.message());
                yield (R) Unit.INSTANCE;
            }
            case LogCapability.Error error -> {
                System.err.println("[ERROR] " + error.message());
                if (error.cause() != null) {
                    error.cause().printStackTrace(System.err);
                }
                yield (R) Unit.INSTANCE;
            }
        };
    }
}
