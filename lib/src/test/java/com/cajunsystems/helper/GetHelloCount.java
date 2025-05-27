package com.cajunsystems.helper;

import com.cajunsystems.Pid;

public record GetHelloCount(Pid replyTo) implements GreetingMessage {
}
