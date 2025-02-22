package systems.cajun;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ActorTest {

    @Test
    void shouldBeAbleToCreateAGreetingActor() {
        var actor = new GreetingActor();
        actor.receive(new HelloMessage());
        actor.receive(new ByeMessage());
        actor.receive(new HelloMessage());

        assertEquals(2, actor.getHelloCount());
        assertEquals(1, actor.getByeCount());
    }

    @Test
    void shouldBeAbleToProcessMessagesFromMailbox() throws InterruptedException {
        var actor = new GreetingActor();
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