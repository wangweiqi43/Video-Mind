package com.videomind.module.knowledge.dto;

import com.videomind.common.enums.KnowledgeLifecycleStatus;

public record DocumentUploadResponse(
        Long documentId,
        Long versionId,
        String title,
        String sha256,
        KnowledgeLifecycleStatus status,
        String processingStage,
        boolean duplicated,
        String eventId,
        Long taskId,
        boolean reusedTask) {

    public DocumentUploadResponse withDispatch(String newEventId, Long newTaskId, boolean newReusedTask) {
        return new DocumentUploadResponse(documentId, versionId, title, sha256, status, processingStage,
                duplicated, newEventId, newTaskId, newReusedTask);
    }
}
