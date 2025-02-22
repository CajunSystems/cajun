package systems.cajun;

public record GetHelloCount(Pid replyTo) implements GreetingMessage {
}
