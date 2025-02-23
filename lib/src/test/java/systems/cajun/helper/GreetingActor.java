package systems.cajun.helper;

import systems.cajun.*;

public class GreetingActor extends Actor<GreetingMessage> {

    private int helloCount;
    private int byeCount;

    public GreetingActor(ActorSystem system, String actorId) {
        super(system, actorId);
        this.helloCount = 0;
        this.byeCount = 0;
    }

    @Override
    public void receive(GreetingMessage message) {
        switch (message) {
            case HelloMessage ignored -> {
                helloCount++;
            }
            case ByeMessage ignored -> {
                byeCount++;
            }
            case GetHelloCount ghc -> {
                ghc.replyTo().tell(new HelloCount(helloCount));
            }
            case FinalBye fb -> {
                fb.replyTo().tell("Bye!");
                self().tell(new Shutdown());
            }
            case Shutdown ignored -> {
                stop();
            }
        }
    }

    public int getByeCount() {
        return byeCount;
    }

    public int getHelloCount() {
        return helloCount;
    }
}
