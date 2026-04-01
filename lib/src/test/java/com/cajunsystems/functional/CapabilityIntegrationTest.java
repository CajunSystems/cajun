package com.cajunsystems.functional;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.functional.capabilities.ConsoleLogHandler;
import com.cajunsystems.functional.capabilities.LogCapability;
import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.capability.Capability;
import com.cajunsystems.roux.capability.CapabilityHandler;
import com.cajunsystems.roux.data.Unit;
import org.junit.jupiter.api.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CapabilityIntegrationTest {

    // Local test capability — returns String (not Unit) to verify typed result dispatch
    sealed interface EchoCapability extends Capability<String>
            permits EchoCapability.Echo {
        record Echo(String value) implements EchoCapability {}
    }

    static class EchoHandler implements CapabilityHandler<Capability<?>> {
        private static final CapabilityHandler<Capability<?>> DELEGATE = CapabilityHandler.builder()
                .on(EchoCapability.Echo.class, e -> "ECHO:" + e.value())
                .build();

        @Override
        public <R> R handle(Capability<?> cap) throws Exception {
            return DELEGATE.handle(cap);
        }
    }

    private ActorSystem system;
    private ActorEffectRuntime runtime;

    @BeforeEach
    void setUp() {
        system = new ActorSystem();
        runtime = new ActorEffectRuntime(system);
    }

    @AfterEach
    void tearDown() {
        system.shutdown();
    }

    @Test
    void echoCapabilityHandlerReturnsTypedValue() throws Throwable {
        CapabilityHandler<Capability<?>> handler = new EchoHandler().widen();

        Effect<RuntimeException, String> effect =
                Effect.<RuntimeException, String>from(new EchoCapability.Echo("hello"));

        String result = runtime.unsafeRunWithHandler(effect, handler);
        assertEquals("ECHO:hello", result);
    }

    @Test
    void composedHandlerDispatchesToCorrectHandler() throws Throwable {
        CapabilityHandler<Capability<?>> composed =
                CapabilityHandler.compose(new EchoHandler().widen(), new ConsoleLogHandler().widen());

        // EchoCapability → EchoHandler
        String echoResult = runtime.unsafeRunWithHandler(
                Effect.<RuntimeException, String>from(new EchoCapability.Echo("composed")),
                composed);
        assertEquals("ECHO:composed", echoResult);

        // LogCapability → ConsoleLogHandler (no exception = correct dispatch)
        Unit logResult = runtime.unsafeRunWithHandler(
                Effect.<RuntimeException, Unit>from(new LogCapability.Info("composed-log")),
                composed);
        assertEquals(Unit.unit(), logResult);
    }

    @Test
    void generateWithMultipleCapabilityTypesViaComposedHandler() throws Throwable {
        CapabilityHandler<Capability<?>> composed =
                CapabilityHandler.compose(new EchoHandler().widen(), new ConsoleLogHandler().widen());

        Effect<RuntimeException, String> effect = Effect.<RuntimeException, String>generate(
                ctx -> {
                    ctx.perform(new LogCapability.Info("starting"));
                    String echoed = ctx.perform(new EchoCapability.Echo("gen-test"));
                    ctx.perform(new LogCapability.Debug("got: " + echoed));
                    return echoed;
                },
                composed
        );

        String result = runtime.unsafeRun(effect);
        assertEquals("ECHO:gen-test", result);
    }

    @Test
    void effectActorWithComposedCapabilityHandler() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> captured = new AtomicReference<>();

        CapabilityHandler<Capability<?>> composed =
                CapabilityHandler.compose(new EchoHandler().widen(), new ConsoleLogHandler().widen());

        Pid pid = new EffectActorBuilder<>(
                system,
                (String msg) -> Effect.<RuntimeException, String>generate(
                        ctx -> {
                            ctx.perform(new LogCapability.Info("actor received: " + msg));
                            String result = ctx.perform(new EchoCapability.Echo(msg));
                            captured.set(result);
                            latch.countDown();
                            return result;
                        },
                        composed
                )
        ).withId("composed-cap-actor").spawn();

        pid.tell("integration");
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals("ECHO:integration", captured.get());
    }

    @Test
    void orElseHandlerChainDispatchesCorrectly() throws Throwable {
        CapabilityHandler<Capability<?>> chained =
                new EchoHandler().widen().orElse(new ConsoleLogHandler());

        Effect<RuntimeException, String> effect =
                Effect.<RuntimeException, String>from(new EchoCapability.Echo("orElse"));
        String result = runtime.unsafeRunWithHandler(effect, chained);
        assertEquals("ECHO:orElse", result);
    }
}
