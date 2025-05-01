package systems.cajun;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import systems.cajun.helper.*;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActorSystemTest {

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

    @Test
    void shouldBeAbleToBeDelayInSendingAMessage() throws InterruptedException {
        // Create a latch to wait for the test to complete
        java.util.concurrent.CountDownLatch testCompletionLatch = new java.util.concurrent.CountDownLatch(1);

        var counterActor = new FunctionalActor<Integer, CounterProtocol>();
        var counter = actorSystem.register(counterActor.receiveMessage((i, m) -> {
            switch (m) {
                case CounterProtocol.CountUp cu -> {
                    return i + 1;
                }
                case CounterProtocol.GetCount gc -> {
                    gc.replyTo().tell(new HelloCount(i));
                }
            }
            return i;
        }, 0), "Counter-Actor");

        // Create a functional actor for receiving the count
        var receiverActor = actorSystem.register(new FunctionalActor<Void, HelloCount>().receiveMessage((state, message) -> {
            assertEquals(4, message.count());
            testCompletionLatch.countDown(); // Signal that we received the message
            return null;
        }, null), "count-receiver-with-delay");

        counter.tell(new CounterProtocol.CountUp(), 1000, TimeUnit.MILLISECONDS);
        counter.tell(new CounterProtocol.CountUp(), 1000, TimeUnit.MILLISECONDS);
        counter.tell(new CounterProtocol.CountUp(), 1000, TimeUnit.MILLISECONDS);
        counter.tell(new CounterProtocol.CountUp(), 1000, TimeUnit.MILLISECONDS);
        counter.tell(new CounterProtocol.GetCount(receiverActor), 5000, TimeUnit.MILLISECONDS);

        // Wait for the test to complete with a timeout
        boolean completed = testCompletionLatch.await(10, TimeUnit.SECONDS);
        assertTrue(completed, "Test did not complete within the expected time");
    }
}
