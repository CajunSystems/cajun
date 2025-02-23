package systems.cajun.helper;

import systems.cajun.Pid;

public record FinalBye(Pid replyTo) implements GreetingMessage {
}
