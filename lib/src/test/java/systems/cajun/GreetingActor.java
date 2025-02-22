package systems.cajun;

public class GreetingActor extends Actor<GreetingMessage> {

    private int helloCount;
    private int byeCount;

    public GreetingActor() {
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
        }
    }

    public int getByeCount() {
        return byeCount;
    }

    public int getHelloCount() {
        return helloCount;
    }
}
