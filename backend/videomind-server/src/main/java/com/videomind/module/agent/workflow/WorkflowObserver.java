package com.videomind.module.agent.workflow;

@FunctionalInterface
public interface WorkflowObserver {
    WorkflowObserver NOOP = event -> { };

    void onEvent(WorkflowEvent event);
}
