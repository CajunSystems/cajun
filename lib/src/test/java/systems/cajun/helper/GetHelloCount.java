package systems.cajun.helper;

import systems.cajun.Pid;

public record GetHelloCount(Pid replyTo) implements GreetingMessage {
}
