package systems.cajun.helper;

import systems.cajun.Pid;

public sealed interface CounterProtocol {

    record CountUp() implements CounterProtocol {
    }

    record GetCount(Pid replyTo) implements CounterProtocol {
    }
}
