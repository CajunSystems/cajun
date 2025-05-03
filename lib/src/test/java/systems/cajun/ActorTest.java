package systems.cajun;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import systems.cajun.helper.ByeMessage;
import systems.cajun.helper.GreetingActor;
import systems.cajun.helper.HelloMessage;

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
    void shouldBeAbleToProcessMessagesFromMailbox() throws InterruptedException {
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

        // Wait for messages to be processed with a timeout
        int expectedHelloCount = 6;
        int expectedByeCount = 3;
        int maxWaitTimeMs = 500; // Reduced from 2000ms to 500ms
        int pollIntervalMs = 10;
        int elapsedTime = 0;
        
        while ((actor.getHelloCount() < expectedHelloCount || actor.getByeCount() < expectedByeCount) 
                && elapsedTime < maxWaitTimeMs) {
            Thread.sleep(pollIntervalMs);
            elapsedTime += pollIntervalMs;
        }

        assertEquals(expectedHelloCount, actor.getHelloCount());
        assertEquals(expectedByeCount, actor.getByeCount());
        // No need to manually stop the actor, it will be stopped by the ActorSystem.shutdown() in tearDown
    }

}
