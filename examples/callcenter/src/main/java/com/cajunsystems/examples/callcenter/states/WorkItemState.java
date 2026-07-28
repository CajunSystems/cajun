package com.cajunsystems.examples.callcenter.states;

import com.cajunsystems.Pid;

import java.io.Serializable;

public class WorkItemState implements Serializable {
    public enum Status {
        NEW, WAITING, CONNECTED, ENDED
    }

    private Status status = Status.NEW;
    private final String callerId;
    private String callSid;
    private Pid assignedAgent = null;

    public WorkItemState(String callerId) {
        this.callerId = callerId;
    }

    public String getCallSid() {
        return callSid;
    }

    public void setCallSid(String callSid) {
        this.callSid = callSid;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getCallerId() {
        return callerId;
    }

    public Pid getAssignedAgent() {
        return assignedAgent;
    }

    public void setAssignedAgent(Pid assignedAgent) {
        this.assignedAgent = assignedAgent;
    }
}
