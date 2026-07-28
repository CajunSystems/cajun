package com.cajunsystems.examples.cjent;

import java.util.ArrayList;
import java.util.List;
import com.cajunsystems.Pid;
import java.io.Serializable;

public class AgentState implements Serializable {
    private static final long serialVersionUID = 1L;
    private final List<String> conversationHistory = new ArrayList<>();
    private Pid currentReplyTo = null;

    public void addHistory(String entry) {
        conversationHistory.add(entry);
    }

    public String getFullHistory() {
        return String.join("\n", conversationHistory);
    }

    public void setCurrentReplyTo(Pid replyTo) {
        this.currentReplyTo = replyTo;
    }

    public Pid getCurrentReplyTo() {
        return currentReplyTo;
    }
}
