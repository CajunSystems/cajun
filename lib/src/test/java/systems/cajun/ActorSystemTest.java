package systems.cajun;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ActorSystemTest {

    static class CountReceiver extends Actor<HelloCount> {

        @Override
        protected void receive(HelloCount helloCount) {
            assertEquals(5, helloCount.count());
        }
    }

    @Test
    void shouldBeAbleToRegisterAnActorToTheSystem() {
        var system = new ActorSystem();
        var pid1 = system.register(GreetingActor.class, "my-greeting-actor-1");
        var pid2 = system.register(GreetingActor.class, "my-greeting-actor-2");

        assertEquals("my-greeting-actor-1", pid1.actorId());
        assertEquals("my-greeting-actor-2", pid2.actorId());
    }

    @Test
    void shouldBeAbleToRouteMessagesToActorsBasedOnId() throws InterruptedException {
        var system = new ActorSystem();
        var pid1 = system.register(GreetingActor.class, "my-greeting-actor-1");
        var receiverActor = system.register(CountReceiver.class, "count-receiver");
        pid1.tell(new HelloMessage());
        pid1.tell(new HelloMessage());
        pid1.tell(new HelloMessage());
        pid1.tell(new HelloMessage());
        pid1.tell(new HelloMessage());
        Thread.sleep(1000);
        pid1.tell(new GetHelloCount(receiverActor));
    }
}