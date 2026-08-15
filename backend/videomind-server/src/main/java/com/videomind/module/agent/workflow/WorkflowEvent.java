package com.videomind.module.agent.workflow;

import java.util.List;

public record WorkflowEvent(String phase, int planGeneration, String stepId, String tool,
                            String status, String message, long elapsedMs, List<String> evidenceIds) {
    public WorkflowEvent {
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
    }
}
