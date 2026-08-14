package com.videomind.module.knowledge.dto;

import com.videomind.common.enums.KnowledgeLifecycleStatus;

public record DocumentUploadResponse(
        Long documentId,
        Long versionId,
        String title,
        String sha256,
        KnowledgeLifecycleStatus status,
        String processingStage,
        boolean duplicated) {
}
