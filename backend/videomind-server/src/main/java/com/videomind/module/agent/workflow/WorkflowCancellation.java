package com.videomind.module.agent.workflow;

@FunctionalInterface
public interface WorkflowCancellation {
    WorkflowCancellation NONE = () -> { };

    void check();
}
