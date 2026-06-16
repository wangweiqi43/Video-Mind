package com.videomind.module.knowledge.repository;

import com.videomind.module.knowledge.dto.KnowledgeStatusResponse;

public interface KnowledgeStatusRepository {

    void saveStatus(Long taskId, boolean vectorized, String status, String message, int chunkCount);

    KnowledgeStatusResponse getStatus(Long taskId);

    void deleteStatus(Long taskId);
}
