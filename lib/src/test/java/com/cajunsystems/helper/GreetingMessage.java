package com.cajunsystems.helper;

public sealed interface GreetingMessage permits HelloMessage, ByeMessage, GetHelloCount, FinalBye, Shutdown {
}
