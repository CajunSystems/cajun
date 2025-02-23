package systems.cajun.helper;

public sealed interface GreetingMessage permits HelloMessage, ByeMessage, GetHelloCount, FinalBye, Shutdown {
}
