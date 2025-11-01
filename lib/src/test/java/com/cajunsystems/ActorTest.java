package com.cajunsystems;

import com.cajunsystems.helper.ByeMessage;
import com.cajunsystems.helper.GreetingActor;
import com.cajunsystems.helper.HelloMessage;
import com.cajunsystems.test.AsyncAssertion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ActorTest {

    private ActorSystem actorSystem;

    @BeforeEach
    void setUp() {
        actorSystem = new ActorSystem();
    }

    @AfterEach
    void tearDown() {
        if (actorSystem != null) {
            actorSystem.shutdown();
        }
    }

    @Test
    void shouldBeAbleToCreateAGreetingActor() {
        var actor = new GreetingActor(actorSystem, "Greeting-Actor-1");
        actor.receive(new HelloMessage());
        actor.receive(new ByeMessage());
        actor.receive(new HelloMessage());

        assertEquals(2, actor.getHelloCount());
        assertEquals(1, actor.getByeCount());
    }

    @Test
    void shouldBeAbleToProcessMessagesFromMailbox() {
        var actor = new GreetingActor(actorSystem, "Greeting-Actor-2");
        actor.start();

        actor.tell(new HelloMessage());
        actor.tell(new HelloMessage());
        actor.receive(new ByeMessage());
        actor.tell(new HelloMessage());
        actor.tell(new HelloMessage());
        actor.receive(new ByeMessage());
        actor.tell(new HelloMessage());
        actor.tell(new HelloMessage());
        actor.receive(new ByeMessage());

        // Use AsyncAssertion to wait for messages to be processed
        int expectedHelloCount = 6;
        int expectedByeCount = 3;
        
        AsyncAssertion.eventually(
            () -> actor.getHelloCount() == expectedHelloCount && actor.getByeCount() == expectedByeCount,
            java.time.Duration.ofMillis(500)
        );

        assertEquals(expectedHelloCount, actor.getHelloCount());
        assertEquals(expectedByeCount, actor.getByeCount());
        // No need to manually stop the actor, it will be stopped by the ActorSystem.shutdown() in tearDown
    }

}
