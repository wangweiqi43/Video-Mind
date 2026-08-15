package com.videomind.module.chat.dto;

import com.videomind.module.agent.workflow.WorkflowEvent;

public record WorkflowSseEvent(String phase, String stepId, String status, String message) {
    public static WorkflowSseEvent from(WorkflowEvent event) {
        return new WorkflowSseEvent(event.phase(), event.stepId(), event.status(), event.message());
    }
}
