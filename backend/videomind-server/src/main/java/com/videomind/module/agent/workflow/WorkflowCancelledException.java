package com.videomind.module.agent.workflow;

public class WorkflowCancelledException extends RuntimeException {
    public WorkflowCancelledException(String message) {
        super(message);
    }
}
