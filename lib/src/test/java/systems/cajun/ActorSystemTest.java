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
        // Create a latch to track when all count up operations have been processed
        java.util.concurrent.CountDownLatch countUpLatch = new java.util.concurrent.CountDownLatch(4);

        var counterActor = new FunctionalActor<Integer, CounterProtocol>();
        var counter = actorSystem.register(counterActor.receiveMessage((i, m) -> {
            switch (m) {
                case CounterProtocol.CountUp cu -> {
                    int newCount = i + 1;
                    countUpLatch.countDown(); // Signal that we processed a count up
                    return newCount;
                }
                case CounterProtocol.GetCount gc -> {
                    gc.replyTo().tell(new HelloCount(i));
                }
            }
            return i;
        }, 0), "Counter-Actor");

        // Create a functional actor for receiving the count
        var receiverActor = actorSystem.register(new FunctionalActor<Void, HelloCount>().receiveMessage((state, message) -> {
            assertEquals(4, message.count(), "Expected count to be 4, but was " + message.count());
            testCompletionLatch.countDown(); // Signal that we received the message
            return null;
        }, null), "count-receiver-with-delay");

        // Use shorter delays to make the test run faster but still test the functionality
        counter.tell(new CounterProtocol.CountUp(), 100, TimeUnit.MILLISECONDS);
        counter.tell(new CounterProtocol.CountUp(), 200, TimeUnit.MILLISECONDS);
        counter.tell(new CounterProtocol.CountUp(), 300, TimeUnit.MILLISECONDS);
        counter.tell(new CounterProtocol.CountUp(), 400, TimeUnit.MILLISECONDS);
        
        // Wait for all count up operations to be processed before sending GetCount
        boolean allCountsProcessed = countUpLatch.await(5, TimeUnit.SECONDS);
        assertTrue(allCountsProcessed, "Not all count up operations were processed within the expected time");
        
        // Now that we know all counts are processed, send the GetCount message
        counter.tell(new CounterProtocol.GetCount(receiverActor), 100, TimeUnit.MILLISECONDS);

        // Wait for the test to complete with a timeout
        boolean completed = testCompletionLatch.await(5, TimeUnit.SECONDS);
        assertTrue(completed, "Test did not complete within the expected time");
    }
}
