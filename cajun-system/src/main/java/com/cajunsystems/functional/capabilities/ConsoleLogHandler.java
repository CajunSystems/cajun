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

    private static final CapabilityHandler<Capability<?>> DELEGATE = CapabilityHandler.builder()
            .on(LogCapability.Info.class, info -> {
                System.out.println("[INFO]  " + info.message());
                return Unit.unit();
            })
            .on(LogCapability.Debug.class, debug -> {
                System.out.println("[DEBUG] " + debug.message());
                return Unit.unit();
            })
            .on(LogCapability.Warn.class, warn -> {
                System.out.println("[WARN]  " + warn.message());
                return Unit.unit();
            })
            .on(LogCapability.Error.class, error -> {
                System.err.println("[ERROR] " + error.message());
                if (error.cause() != null) {
                    error.cause().printStackTrace(System.err);
                }
                return Unit.unit();
            })
            .build();

    @Override
    public <R> R handle(Capability<?> cap) throws Exception {
        return DELEGATE.handle(cap);
    }
}
