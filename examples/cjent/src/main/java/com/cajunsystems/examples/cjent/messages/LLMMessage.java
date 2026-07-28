package com.cajunsystems.examples.cjent.messages;

import com.cajunsystems.Pid;
import java.io.Serializable;

public sealed interface LLMMessage extends Serializable {
    record AskLLM(String context, Pid replyToAgent) implements LLMMessage {}
}
