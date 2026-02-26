package com.cajunsystems.functional.capabilities;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.functional.ActorEffectRuntime;
import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.capability.CapabilityHandler;
import com.cajunsystems.roux.data.Unit;
import org.junit.jupiter.api.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

class LogCapabilityTest {

    private ActorSystem system;
    private ActorEffectRuntime runtime;
    private ConsoleLogHandler handler;

    @BeforeEach
    void setUp() {
        system = new ActorSystem();
        runtime = new ActorEffectRuntime(system);
        handler = new ConsoleLogHandler();
    }

    @AfterEach
    void tearDown() {
        system.shutdown();
    }

    @Test
    void infoWritesToStdout() throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream oldOut = System.out;
        System.setOut(new PrintStream(buf));
        try {
            Effect<RuntimeException, Unit> effect =
                    Effect.<RuntimeException, Unit>from(new LogCapability.Info("hello-info"));
            Unit result = runtime.unsafeRunWithHandler(effect, handler.widen());
            assertEquals(Unit.INSTANCE, result);
        } finally {
            System.setOut(oldOut);
        }
        assertTrue(buf.toString().contains("[INFO]"));
        assertTrue(buf.toString().contains("hello-info"));
    }

    @Test
    void debugWritesToStdout() throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream oldOut = System.out;
        System.setOut(new PrintStream(buf));
        try {
            Effect<RuntimeException, Unit> effect =
                    Effect.<RuntimeException, Unit>from(new LogCapability.Debug("debug-msg"));
            runtime.unsafeRunWithHandler(effect, handler.widen());
        } finally {
            System.setOut(oldOut);
        }
        assertTrue(buf.toString().contains("[DEBUG]"));
        assertTrue(buf.toString().contains("debug-msg"));
    }

    @Test
    void warnWritesToStdout() throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream oldOut = System.out;
        System.setOut(new PrintStream(buf));
        try {
            Effect<RuntimeException, Unit> effect =
                    Effect.<RuntimeException, Unit>from(new LogCapability.Warn("warn-msg"));
            runtime.unsafeRunWithHandler(effect, handler.widen());
        } finally {
            System.setOut(oldOut);
        }
        assertTrue(buf.toString().contains("[WARN]"));
        assertTrue(buf.toString().contains("warn-msg"));
    }

    @Test
    void errorWithCauseWritesToStderr() throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream oldErr = System.err;
        System.setErr(new PrintStream(buf));
        try {
            Effect<RuntimeException, Unit> effect =
                    Effect.<RuntimeException, Unit>from(
                            new LogCapability.Error("error-msg", new RuntimeException("cause")));
            runtime.unsafeRunWithHandler(effect, handler.widen());
        } finally {
            System.setErr(oldErr);
        }
        assertTrue(buf.toString().contains("[ERROR]"));
        assertTrue(buf.toString().contains("error-msg"));
    }

    @Test
    void errorWithoutCauseWritesToStderrWithoutNpe() throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream oldErr = System.err;
        System.setErr(new PrintStream(buf));
        try {
            Effect<RuntimeException, Unit> effect =
                    Effect.<RuntimeException, Unit>from(new LogCapability.Error("no-cause"));
            runtime.unsafeRunWithHandler(effect, handler.widen());
        } finally {
            System.setErr(oldErr);
        }
        assertTrue(buf.toString().contains("[ERROR]"));
        assertTrue(buf.toString().contains("no-cause"));
    }

    @Test
    void generateWithMultipleLogCapabilities() throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream oldOut = System.out;
        System.setOut(new PrintStream(buf));
        try {
            Effect<RuntimeException, Unit> effect = Effect.<RuntimeException, Unit>generate(
                    ctx -> {
                        ctx.perform(new LogCapability.Info("gen-info"));
                        ctx.perform(new LogCapability.Debug("gen-debug"));
                        ctx.perform(new LogCapability.Warn("gen-warn"));
                        return Unit.INSTANCE;
                    },
                    handler.widen()
            );
            runtime.unsafeRun(effect);
        } finally {
            System.setOut(oldOut);
        }
        String output = buf.toString();
        assertTrue(output.contains("gen-info"));
        assertTrue(output.contains("gen-debug"));
        assertTrue(output.contains("gen-warn"));
    }

    @Test
    void capabilityHandlerComposeWorks() throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream oldOut = System.out;
        System.setOut(new PrintStream(buf));
        CapabilityHandler<com.cajunsystems.roux.capability.Capability<?>> composed =
                CapabilityHandler.compose(handler);
        try {
            Effect<RuntimeException, Unit> effect =
                    Effect.<RuntimeException, Unit>from(new LogCapability.Info("composed"));
            runtime.unsafeRunWithHandler(effect, composed);
        } finally {
            System.setOut(oldOut);
        }
        assertTrue(buf.toString().contains("composed"));
    }
}
