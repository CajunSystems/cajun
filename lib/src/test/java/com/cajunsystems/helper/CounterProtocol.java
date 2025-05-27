package com.cajunsystems.helper;

import com.cajunsystems.Pid;

public sealed interface CounterProtocol {

    record CountUp() implements CounterProtocol {
    }

    record GetCount(Pid replyTo) implements CounterProtocol {
    }
}
