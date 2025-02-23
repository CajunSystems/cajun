package systems.cajun;

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

        Thread.sleep(2000);

        assertEquals(6, actor.getHelloCount());
        assertEquals(3, actor.getByeCount());
        actor.stop();
    }

}