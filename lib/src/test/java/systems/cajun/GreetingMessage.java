package systems.cajun;

public sealed interface GreetingMessage permits HelloMessage, ByeMessage {
}
