package systems.cajun;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ActorSystemTest {

    private ActorSystem actorSystem;

    @BeforeEach
    void setUp() {
        actorSystem = new ActorSystem();
    }

    static class FinalByeHandler extends Actor<String> {

        public FinalByeHandler(ActorSystem system, String actorId) {
            super(system, actorId);
        }

        @Override
        protected void receive(String s) {
            assertEquals("Bye!", s);
        }
    }

    static class CountReceiver extends Actor<HelloCount> {

        public CountReceiver(ActorSystem system, String actorId) {
            super(system, actorId);
        }

        @Override
        protected void receive(HelloCount helloCount) {
            assertEquals(5, helloCount.count());
        }
    }

    @Test
    void shouldBeAbleToRegisterAnActorToTheSystem() {
        var pid1 = actorSystem.register(GreetingActor.class, "my-greeting-actor-1");
        var pid2 = actorSystem.register(GreetingActor.class, "my-greeting-actor-2");

        assertEquals("my-greeting-actor-1", pid1.actorId());
        assertEquals("my-greeting-actor-2", pid2.actorId());
    }

    @Test
    void shouldBeAbleToRouteMessagesToActorsBasedOnId() throws InterruptedException {
        var pid1 = actorSystem.register(GreetingActor.class, "my-greeting-actor-1");
        var receiverActor = actorSystem.register(CountReceiver.class, "count-receiver");
        pid1.tell(new HelloMessage());
        pid1.tell(new HelloMessage());
        pid1.tell(new HelloMessage());
        pid1.tell(new HelloMessage());
        pid1.tell(new HelloMessage());
        pid1.tell(new GetHelloCount(receiverActor));
    }

    @Test
    void shouldBeAbleToSendAMessageToSelf() {
        var pid1 = actorSystem.register(GreetingActor.class, "my-greet");
        var replyTo = actorSystem.register(FinalByeHandler.class, "byeHandler");
        pid1.tell(new FinalBye(replyTo));
    }
}