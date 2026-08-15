package com.videomind.module.chat.generation;

import com.videomind.module.agent.workflow.WorkflowCancelledException;

public class ChatGenerationCancelledException extends WorkflowCancelledException {
    private final Long generationId;

    public ChatGenerationCancelledException(Long generationId) {
        super("CHAT_GENERATION_CANCELLED");
        this.generationId = generationId;
    }

    public Long generationId() {
        return generationId;
    }
}
