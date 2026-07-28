package com.cajunsystems.examples.callcenter.states;

import com.cajunsystems.Pid;

import java.io.Serializable;

public class AgentState implements Serializable {
    public enum Status {
        OFFLINE, AVAILABLE, BUSY, WRAP_UP
    }

    private Status status = Status.OFFLINE;
    private final String name;
    private int callsHandled = 0;
    private Pid activeWorkItem = null;

    public AgentState(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public int getCallsHandled() {
        return callsHandled;
    }

    public void incrementCallsHandled() {
        this.callsHandled++;
    }

    public Pid getActiveWorkItem() {
        return activeWorkItem;
    }

    public void setActiveWorkItem(Pid activeWorkItem) {
        this.activeWorkItem = activeWorkItem;
    }
}
