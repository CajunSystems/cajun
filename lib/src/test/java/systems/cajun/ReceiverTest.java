package systems.cajun;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import systems.cajun.helper.CounterProtocol;
import systems.cajun.helper.HelloCount;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReceiverTest {


    private ActorSystem actorSystem;

    @BeforeEach
    void setUp() {
        actorSystem = new ActorSystem();
    }

    static class CountReceiver extends Actor<HelloCount> {

        public CountReceiver(ActorSystem system, String actorId) {
            super(system, actorId);
        }

        @Override
        protected void receive(HelloCount helloCount) {
            assertEquals(4, helloCount.count());
        }
    }

    @Test
    void shouldBeAbleToBeStatefulReceiver() {
        var counterActor = new FunctionalActor<Integer, CounterProtocol>();
        var counter = actorSystem.register(counterActor.receiveMessage((i, m) -> {
            switch (m) {
                case CounterProtocol.CountUp cu -> {
                    return i + 1;
                }
                case CounterProtocol.GetCount gc -> {
                    gc.replyTo().tell(i);
                }
            }
            return i;
        }, 0), "Counter-Actor");
        var receiverActor = actorSystem.register(CountReceiver.class, "count-receiver");
        counter.tell(new CounterProtocol.CountUp());
        counter.tell(new CounterProtocol.CountUp());
        counter.tell(new CounterProtocol.CountUp());
        counter.tell(new CounterProtocol.CountUp());
        counter.tell(new CounterProtocol.GetCount(receiverActor));
    }
}