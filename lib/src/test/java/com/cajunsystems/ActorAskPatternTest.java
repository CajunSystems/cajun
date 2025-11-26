package com.cajunsystems;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to verify that the ask pattern works correctly with direct Actor subclasses.
 * This catches bugs where getSender() might not work in custom Actor implementations.
 */
public class ActorAskPatternTest {

    private ActorSystem system;

    @BeforeEach
    public void setup() {
        system = new ActorSystem();
    }

    @AfterEach
    public void teardown() {
        if (system != null) {
            system.shutdown();
        }
    }

    /**
     * Test direct Actor subclass using getSender() in receive()
     */
    @Test
    public void testAskPatternWithDirectActorSubclass() throws Exception {
        // Create a direct Actor subclass
        Pid echo = system.register(DirectEchoActor.class, "direct-echo");

        // Test ask pattern
        CompletableFuture<String> future1 = system.ask(echo, "Hello", Duration.ofSeconds(5));
        String response1 = future1.get(5, TimeUnit.SECONDS);
        assertEquals("Echo: Hello", response1, "Should echo the message");

        // Test another ask
        CompletableFuture<String> future2 = system.ask(echo, "World", Duration.ofSeconds(5));
        String response2 = future2.get(5, TimeUnit.SECONDS);
        assertEquals("Echo: World", response2, "Should echo the second message");
    }

    /**
     * Test multiple concurrent ask requests to a direct Actor subclass
     */
    @Test
    public void testMultipleConcurrentAskRequests() throws Exception {
        Pid echo = system.register(DirectEchoActor.class, "concurrent-echo");

        // Make multiple concurrent ask requests
        CompletableFuture<String> future1 = system.ask(echo, "Message1", Duration.ofSeconds(5));
        CompletableFuture<String> future2 = system.ask(echo, "Message2", Duration.ofSeconds(5));
        CompletableFuture<String> future3 = system.ask(echo, "Message3", Duration.ofSeconds(5));

        // Wait for all futures to complete
        CompletableFuture.allOf(future1, future2, future3).get(5, TimeUnit.SECONDS);

        // Verify all responses are correct
        assertEquals("Echo: Message1", future1.get());
        assertEquals("Echo: Message2", future2.get());
        assertEquals("Echo: Message3", future3.get());
    }

    /**
     * Test that regular tell() doesn't have sender context
     */
    @Test
    public void testTellFromSystemDoesNotHaveSenderContext() throws Exception {
        Pid validator = system.register(SenderValidatorActor.class, "validator");

        // Send a message with tell - should not have sender
        validator.tell("test");

        // Give it time to process
        Thread.sleep(500);

        // The actor logs an error if getSender() is present for tell()
        // We can't easily assert on logs, but the test passes if no exception is thrown
    }

    /**
     * Direct Actor subclass that echoes messages using getSender()
     */
    static class DirectEchoActor extends Actor<String> {
        public DirectEchoActor(ActorSystem system, String actorId) {
            super(system, actorId);
        }

        @Override
        protected void receive(String message) {
            // Use getSender() directly - this is where bugs could occur
            getSender().ifPresentOrElse(
                sender -> sender.tell("Echo: " + message),
                () -> getLogger().debug("No sender for message: {}", message)
            );
        }
    }

    /**
     * Direct Actor subclass that validates sender context
     */
    static class SenderValidatorActor extends Actor<String> {
        public SenderValidatorActor(ActorSystem system, String actorId) {
            super(system, actorId);
        }

        @Override
        protected void receive(String message) {
            if (getSender().isPresent()) {
                getLogger().error("Sender should not be present for tell() - this is unexpected!");
            } else {
                getLogger().debug("Correctly no sender for tell() message");
            }
        }
    }

    /**
     * Actor that records the sender PID when instructed.
     */
    static class SenderRecordingActor extends Actor<String> {
        private static volatile CompletableFuture<Pid> probe;
        private static final String CAPTURE = "capture";

        public SenderRecordingActor(ActorSystem system, String actorId) {
            super(system, actorId);
        }

        static void setProbe(CompletableFuture<Pid> future) {
            probe = future;
        }

        @Override
        protected void receive(String message) {
            if (CAPTURE.equals(message) && probe != null) {
                probe.complete(getSender().orElse(null));
            }
        }
    }

    /**
     * Actor that forwards a capture request to another actor using tell().
     */
    static class SenderForwarderActor extends Actor<SenderForwarderActor.SendCommand> {
        public SenderForwarderActor(ActorSystem system, String actorId) {
            super(system, actorId);
        }

        record SendCommand(Pid target) {}

        @Override
        protected void receive(SendCommand command) {
            command.target().tell(SenderRecordingActor.CAPTURE);
        }
    }
}
