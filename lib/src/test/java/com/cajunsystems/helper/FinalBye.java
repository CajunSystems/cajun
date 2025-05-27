package com.cajunsystems.helper;

import com.cajunsystems.Pid;

public record FinalBye(Pid replyTo) implements GreetingMessage {
}
