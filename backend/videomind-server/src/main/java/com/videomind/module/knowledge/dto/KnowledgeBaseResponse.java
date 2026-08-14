package com.videomind.module.knowledge.dto;

import com.videomind.common.enums.KnowledgeBaseType;
import com.videomind.common.enums.KnowledgeLifecycleStatus;
import java.time.LocalDateTime;

public record KnowledgeBaseResponse(
        Long id,
        KnowledgeBaseType type,
        Long videoId,
        String name,
        KnowledgeLifecycleStatus status,
        long documentCount,
        LocalDateTime createdTime,
        LocalDateTime updatedTime) {
}
