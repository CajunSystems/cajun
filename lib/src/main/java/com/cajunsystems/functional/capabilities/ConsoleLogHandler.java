package com.cajunsystems.functional.capabilities;

import com.cajunsystems.roux.capability.Capability;
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
 * <p>Implements {@code CapabilityHandler<Capability<?>>} so it can be combined with other
 * handlers via {@link CapabilityHandler#compose} or {@link CapabilityHandler#orElse}.
 * Non-{@link LogCapability} capabilities are signalled with {@link UnsupportedOperationException}
 * per the compose/orElse contract.
 *
 * <pre>{@code
 * runtime.unsafeRunWithHandler(effect, new ConsoleLogHandler());
 * }</pre>
 */
public class ConsoleLogHandler implements CapabilityHandler<Capability<?>> {

    @Override
    @SuppressWarnings("unchecked")
    public <R> R handle(Capability<?> capability) {
        if (!(capability instanceof LogCapability lc)) {
            throw new UnsupportedOperationException(
                    "ConsoleLogHandler cannot handle: " + capability.getClass().getName());
        }
        return switch (lc) {
            case LogCapability.Info info -> {
                System.out.println("[INFO]  " + info.message());
                yield (R) Unit.unit();
            }
            case LogCapability.Debug debug -> {
                System.out.println("[DEBUG] " + debug.message());
                yield (R) Unit.unit();
            }
            case LogCapability.Warn warn -> {
                System.out.println("[WARN]  " + warn.message());
                yield (R) Unit.unit();
            }
            case LogCapability.Error error -> {
                System.err.println("[ERROR] " + error.message());
                if (error.cause() != null) {
                    error.cause().printStackTrace(System.err);
                }
                yield (R) Unit.unit();
            }
        };
    }
}
