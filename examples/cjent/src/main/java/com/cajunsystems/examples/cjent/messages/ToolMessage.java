package com.cajunsystems.examples.cjent.messages;

import com.cajunsystems.Pid;
import java.io.Serializable;

public sealed interface ToolMessage extends Serializable {
    record ExecuteTool(String toolName, String input, Pid replyToAgent) implements ToolMessage {}
}
